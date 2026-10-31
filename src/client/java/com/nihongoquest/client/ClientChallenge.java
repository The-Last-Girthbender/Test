package com.nihongoquest.client;

import com.nihongoquest.client.speech.SpeechService;
import com.nihongoquest.client.speech.VoiceService;
import com.nihongoquest.config.ModConfig;
import com.nihongoquest.network.Payloads;
import com.nihongoquest.network.Payloads.ChallengeAnswer;
import com.nihongoquest.network.Payloads.ChallengeResolved;
import com.nihongoquest.network.Payloads.ChallengeStart;
import com.nihongoquest.vocab.Kana;
import com.nihongoquest.vocab.Pronunciation;
import com.nihongoquest.vocab.Vocabulary.Word;
import com.nihongoquest.vocab.Vocabulary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The quiz currently on screen (client side). All methods run on the game thread. */
public final class ClientChallenge {
    public enum State { LISTENING, WAITING, PASSED, FAILED }

    private static ClientChallenge current;
    private static Word lastWord;       // for the "hear it" key after a quiz ends
    private static long lastVoiceNotice;
    private static long lastUnavailableNotice;

    final int id;
    final ItemStack icon;
    final Component itemName;
    final Word word;
    final int totalTicks;
    final List<String> targets;
    int ticksLeft;
    int amount;
    int removed;
    int afterTicks; // countdown for how long the result stays on screen / how long to wait for the server
    State state = State.LISTENING;
    volatile String heard = "";
    final String guide; // English "sounds like" respelling, or null
    final String tip;

    private ClientChallenge(int id, ItemStack icon, Word word, int totalTicks, int amount) {
        this.id = id;
        this.icon = icon;
        this.itemName = icon.getHoverName();
        this.word = word;
        this.totalTicks = Math.max(1, totalTicks);
        this.ticksLeft = this.totalTicks;
        this.amount = amount;
        Set<String> t = new LinkedHashSet<>();
        for (String form : word.accepted()) {
            String n = Kana.normalize(form);
            if (!n.isEmpty()) t.add(n);
        }
        if (word.kana() != null) t.add(Kana.normalize(word.kana()));
        this.targets = new ArrayList<>(t);
        this.guide = word.kana() != null ? Pronunciation.guide(word.kana()) : null;
        this.tip = word.kana() != null ? Pronunciation.tip(word.kana(), word.romaji()) : null;
    }

    /** What to give the voice: the kana reading if known (always pronounced correctly), else the written form. */
    static String spoken(Word w) {
        return w.kana() != null && !w.kana().isBlank() ? w.kana() : w.primary();
    }

    /** "Hear it" key: reads the current (or last) quiz word aloud. */
    static void hear() {
        ClientChallenge c = current;
        Word w = c != null ? c.word : lastWord;
        if (w == null) return;
        if (!VoiceService.speak(spoken(w))) {
            long now = System.currentTimeMillis();
            if (now - lastVoiceNotice > 20_000) {
                lastVoiceNotice = now;
                chat(Component.literal("[日本語クエスト] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(VoiceService.unavailableReason()).withStyle(ChatFormatting.GRAY)));
            }
        }
    }

    public static ClientChallenge current() {
        return current;
    }

    // ------------------------------------------------------------------ events

    static void onStart(ChallengeStart p) {
        Minecraft mc = Minecraft.getInstance();
        ModConfig cfg = ModConfig.get();
        clear();

        Identifier itemKey = Identifier.tryParse(p.itemId());
        Item item = itemKey == null ? null : BuiltInRegistries.ITEM.getValue(itemKey);
        if (item == null) {
            answer(p.challengeId(), Payloads.ANSWER_UNAVAILABLE);
            return;
        }
        ItemStack icon = new ItemStack(item);

        Word word = Vocabulary.lookup(p.itemId(), cfg.includeColorWords);
        if (word == null && !cfg.onlyItemsWithBuiltInWords) word = GameJapanese.wordFor(item);
        if (word == null) {
            answer(p.challengeId(), Payloads.ANSWER_UNAVAILABLE);
            return;
        }

        if (!SpeechService.isReady()) {
            answer(p.challengeId(), Payloads.ANSWER_UNAVAILABLE);
            SpeechService.retryIfFailed();
            long now = System.currentTimeMillis();
            if (now - lastUnavailableNotice > 60_000) {
                lastUnavailableNotice = now;
                chat(Component.literal("[日本語クエスト] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(SpeechService.unavailableReason()).withStyle(ChatFormatting.GRAY)));
            }
            return;
        }

        ClientChallenge c = new ClientChallenge(p.challengeId(), icon, word, p.timeLimitTicks(), p.amountAtStake());
        current = c;
        lastWord = word;
        sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6f);

        double strictness = cfg.matchStrictness();
        SpeechService.listen(
                word.accepted(),
                heardText -> {
                    String h = Kana.normalize(heardText);
                    for (String t : c.targets) if (Kana.matches(h, t, strictness)) return true;
                    return false;
                },
                heardText -> c.heard = heardText,
                () -> mc.execute(() -> c.onRecognised()),
                error -> mc.execute(() -> {
                    if (current == c && c.state == State.LISTENING) {
                        chat(Component.literal("[日本語クエスト] ").withStyle(ChatFormatting.GOLD)
                                .append(Component.literal(error).withStyle(ChatFormatting.GRAY)));
                        answer(c.id, Payloads.ANSWER_UNAVAILABLE);
                        c.state = State.WAITING;
                        c.afterTicks = 100;
                    }
                }));
        if (cfg.speakAtQuizStart()) VoiceService.speak(spoken(word));
    }

    private void onRecognised() {
        if (current != this || state != State.LISTENING) return;
        state = State.PASSED;
        afterTicks = 60;
        SpeechService.stop();
        answer(id, Payloads.ANSWER_PASSED);
        sound(SoundEvents.PLAYER_LEVELUP, 1.4f);
    }

    static void onResolved(ChallengeResolved p) {
        ClientChallenge c = current;
        if (c == null || c.id != p.challengeId()) return;
        SpeechService.stop();
        switch (p.outcome()) {
            case Payloads.OUTCOME_PASSED -> {
                c.state = State.PASSED;
                c.afterTicks = Math.max(c.afterTicks, 40);
                chat(Component.literal("正解！ ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                        .append(c.itemName.copy().withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" = ").withStyle(ChatFormatting.GRAY))
                        .append(wordLine(c.word)));
            }
            case Payloads.OUTCOME_FAILED -> {
                c.state = State.FAILED;
                c.removed = p.removed();
                c.afterTicks = 100;
                sound(SoundEvents.VILLAGER_NO, 1.0f);
                MutableComponent msg = Component.literal("時間切れ… ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
                if (p.removed() > 0) {
                    msg.append(Component.literal("Lost " + p.removed() + "× ").withStyle(ChatFormatting.RED))
                            .append(c.itemName.copy().withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(". ").withStyle(ChatFormatting.RED));
                }
                msg.append(Component.literal("The word was ").withStyle(ChatFormatting.GRAY)).append(wordLine(c.word));
                chat(msg);
                if (ModConfig.get().speakOnFail) VoiceService.speak(spoken(c.word));
            }
            default -> clear(); // cancelled
        }
    }

    static void tick(Minecraft mc) {
        ClientChallenge c = current;
        if (c == null || mc.isPaused()) return;
        switch (c.state) {
            case LISTENING -> {
                if (--c.ticksLeft <= 0) {
                    // Time's up here; the server makes the final call and removes the items.
                    SpeechService.stop();
                    c.state = State.WAITING;
                    c.afterTicks = 100;
                }
            }
            case WAITING, PASSED, FAILED -> {
                if (--c.afterTicks <= 0) clear();
            }
        }
    }

    static void clear() {
        SpeechService.stop();
        current = null;
    }

    // ------------------------------------------------------------------ helpers

    static MutableComponent wordLine(Word w) {
        MutableComponent line = Component.literal(w.primary()).withStyle(ChatFormatting.YELLOW);
        StringBuilder reading = new StringBuilder();
        if (w.hasSeparateKanji()) reading.append(w.kana());
        if (w.romaji() != null && !w.romaji().isBlank()) {
            if (!reading.isEmpty()) reading.append(", ");
            reading.append(w.romaji());
        }
        if (!reading.isEmpty()) line.append(Component.literal(" (" + reading + ")").withStyle(ChatFormatting.AQUA));
        String guide = w.kana() != null ? Pronunciation.guide(w.kana()) : null;
        if (guide != null && ModConfig.get().showPronunciation()) {
            line.append(Component.literal(" ≈ " + guide).withStyle(ChatFormatting.GREEN));
        }
        return line;
    }

    private static void answer(int id, int status) {
        if (ClientPlayNetworking.canSend(ChallengeAnswer.TYPE)) {
            ClientPlayNetworking.send(new ChallengeAnswer(id, status));
        }
    }

    private static void chat(Component msg) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.sendSystemMessage(msg);
    }

    private static void sound(SoundEvent event, float pitch) {
        if (!ModConfig.get().playSounds) return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch));
    }
}
