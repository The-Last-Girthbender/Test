package com.nihongoquest.client;

import com.nihongoquest.NihongoQuest;
import com.nihongoquest.client.speech.SpeechService;
import com.nihongoquest.client.speech.VoiceService;
import com.nihongoquest.config.ModConfig;
import com.nihongoquest.config.ModConfig.Preset;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * In-game settings. Opened from Mod Menu's "Config" button or with /nihongo.
 * Edits {@link ModConfig#get()} directly and writes config.json when closed.
 */
public class ConfigScreen extends Screen {
    private static final int COL_W = 150;
    private static final int ROW_H = 24;
    private static int page = 0; // remembered while the game runs

    private final Screen parent;
    private final List<Runnable> refreshers = new ArrayList<>();
    private List<String> microphones;
    private int itemsBottom;

    // Microphone test state (callbacks arrive on the microphone thread)
    private boolean testing;
    private volatile String testHeard = "";
    private volatile String testError;

    public ConfigScreen(Screen parent) {
        super(Component.literal("Nihongo Quest (日本語クエスト) Settings"));
        this.parent = parent;
    }

    /** Set by the /nihongo command; the screen opens on the next tick, after chat has closed. */
    static volatile boolean openNextTick;

    static void tick(Minecraft mc) {
        if (openNextTick) {
            openNextTick = false;
            mc.gui.setScreen(new ConfigScreen(null));
        }
    }

    private ModConfig cfg() {
        return ModConfig.get();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        refreshers.clear();
        if (microphones == null) microphones = SpeechService.microphoneNames();
        int cx = this.width / 2;

        // Page tabs
        String[] tabs = {"Quiz", "Display & Mic", "Pronunciation"};
        int totalW = COL_W * 2 + 10;
        int tabW = (totalW - 10) / 3;
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            Button tab = Button.builder(Component.literal(tabs[i]), b -> {
                stopTest();
                page = index;
                Minecraft.getInstance().gui.setScreen(new ConfigScreen(parent));
            }).bounds(cx - COL_W - 5 + i * (tabW + 5), 26, tabW, 20).build();
            tab.active = page != i;
            addRenderableWidget(tab);
        }

        int top = 56;
        List<Consumer<int[]>> items = switch (page) {
            case 0 -> quizPage();
            case 1 -> displayPage();
            default -> pronunciationPage();
        };
        itemsBottom = top + ((items.size() + 1) / 2) * ROW_H;
        for (int i = 0; i < items.size(); i++) {
            int col = i % 2;
            int row = i / 2;
            int x = cx - COL_W - 5 + col * (COL_W + 10);
            int y = top + row * ROW_H;
            items.get(i).accept(new int[]{x, y});
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx - 100, this.height - 28, 200, 20).build());
        refresh();
    }

    private List<Consumer<int[]>> quizPage() {
        List<Consumer<int[]>> items = new ArrayList<>();
        items.add(at -> {
            Button b = Button.builder(Component.empty(), btn -> {
                List<Preset> all = ModConfig.PRESETS;
                int idx = all.indexOf(cfg().preset());
                cfg().applyPreset(all.get((idx + 1) % all.size()));
                refresh();
            }).bounds(at[0], at[1], COL_W, 20).tooltip(Tooltip.create(Component.literal(
                    "Beginner: 45 s, everything shown, forgiving.\n"
                            + "Normal: 30 s, everything shown.\n"
                            + "Advanced: 20 s, every 5 pickups, no romaji, strict.\n"
                            + "Expert: 15 s, every 3 pickups, English name only.\n\n"
                            + "Choosing a preset resets the custom values on this page."))).build();
            addRenderableWidget(b);
            refreshers.add(() -> b.setMessage(label("Difficulty", cap(cfg().preset().name())
                    + (cfg().hasOverrides() ? "*" : ""))));
        });
        items.add(at -> {
            Button b = Button.builder(Component.literal("Reset to preset"), btn -> {
                cfg().clearOverrides();
                refresh();
            }).bounds(at[0], at[1], COL_W, 20).tooltip(Tooltip.create(Component.literal(
                    "Undo your custom values and use the difficulty preset's settings again."))).build();
            addRenderableWidget(b);
            refreshers.add(() -> b.active = cfg().hasOverrides());
        });
        items.add(number("Time limit", "s", 5, 600, 5,
                () -> cfg().timeLimitSeconds(), v -> cfg().timeLimitSeconds = v,
                "Seconds you get to say the word."));
        items.add(number("Quiz every", " pickups", 1, 1000, 1,
                () -> cfg().quizEveryNPickups(), v -> cfg().quizEveryNPickups = v,
                "A quiz happens on every Nth pickup of the same kind of item."));
        items.add(number("Match needed", "%", 50, 100, 5,
                () -> (int) Math.round(cfg().matchStrictness() * 100), v -> cfg().matchStrictness = v / 100.0,
                "How close your pronunciation must be.\n50% = most forgiving, 100% = exact.\nOnly finished words are judged (pause briefly after speaking)."));
        items.add(cycle("Count", () -> cfg().countEveryItem() ? "Each item" : "Each pickup",
                () -> cfg().countMode = cfg().countEveryItem() ? "pickups" : "items",
                "Each pickup: picking up a stack counts once.\nEach item: a stack of 64 counts as 64."));
        items.add(toggle("Quiz first pickup", () -> cfg().quizOnFirstPickup, v -> cfg().quizOnFirstPickup = v,
                "Also quiz the very first time you get an item."));
        items.add(toggle("Skip in Creative", () -> cfg().ignoreCreativeMode, v -> cfg().ignoreCreativeMode = v,
                "No quizzes in Creative or Spectator mode."));
        items.add(toggle("Built-in words only", () -> cfg().onlyItemsWithBuiltInWords,
                v -> cfg().onlyItemsWithBuiltInWords = v,
                "ON: only quiz items from the mod's hand-picked word list.\n"
                        + "OFF: other items use Minecraft's own Japanese name."));
        items.add(toggle("Color words", () -> cfg().includeColorWords, v -> cfg().includeColorWords = v,
                "Add the color to colored items, e.g. red wool = 赤い羊毛 (akai youmou)."));
        return items;
    }

    private List<Consumer<int[]>> pronunciationPage() {
        List<Consumer<int[]>> items = new ArrayList<>();
        items.add(toggle("Sounds-like guide", () -> cfg().showPronunciation(), v -> cfg().showPronunciation = v,
                "Show an English respelling under the word, e.g.\n"
                        + "りんご = reen-goh, はっぱ = hah.pah, ケーキ = kehh-kee.\n"
                        + "Say it in one breath: dashes are not pauses.\n"
                        + "A \".\" is a tiny silent beat (the small っ).\n"
                        + "Doubled vowels (ahh, ooo) are held twice as long."));
        items.add(toggle("Read aloud at start", () -> cfg().speakAtQuizStart(), v -> cfg().speakAtQuizStart = v,
                "Your computer says the word when a quiz starts, then you repeat it.\n"
                        + "The microphone ignores the computer's voice."));
        items.add(toggle("Read aloud on fail", () -> cfg().speakOnFail, v -> cfg().speakOnFail = v,
                "Hear the correct word when you run out of time."));
        items.add(number("Voice speed", "", -5, 5, 1,
                () -> cfg().voiceSpeed(), v -> cfg().voiceSpeed = v,
                "-5 = slowest, 0 = normal, 5 = fastest."));
        items.add(at -> addRenderableWidget(Button.builder(Component.literal("Test voice: こんにちは"), btn -> {
            if (!VoiceService.speak("こんにちは")) voiceMessage = VoiceService.unavailableReason();
            else voiceMessage = null;
        }).bounds(at[0], at[1], COL_W, 20).tooltip(Tooltip.create(Component.literal(
                "Plays \"konnichiwa\" with your computer's Japanese voice."))).build()));
        items.add(at -> {
            Button b = Button.builder(Component.empty(), btn -> {})
                    .bounds(at[0], at[1], COL_W, 20).tooltip(Tooltip.create(Component.literal(
                            "Press this key during a quiz to hear the word.\n"
                                    + "Change it in Options > Controls > Key Binds."))).build();
            b.active = false;
            addRenderableWidget(b);
            refreshers.add(() -> {
                String k = NihongoQuestClient.hearKeyName();
                b.setMessage(label("Hear-it key", k == null ? "not set" : k));
            });
        });
        return items;
    }

    private volatile String voiceMessage;

    private List<Consumer<int[]>> displayPage() {
        List<Consumer<int[]>> items = new ArrayList<>();
        items.add(toggle("Show kanji", () -> cfg().showKanji(), v -> cfg().showKanji = v, "Show the kanji spelling, e.g. 林檎."));
        items.add(toggle("Show kana", () -> cfg().showKana(), v -> cfg().showKana = v, "Show the kana spelling, e.g. りんご."));
        items.add(toggle("Show romaji", () -> cfg().showRomaji(), v -> cfg().showRomaji = v, "Show the reading in Latin letters, e.g. ringo."));
        items.add(toggle("Show English", () -> cfg().showEnglish(), v -> cfg().showEnglish = v, "Show the item's normal name."));
        items.add(toggle("Show what I said", () -> cfg().showWhatWasHeard, v -> cfg().showWhatWasHeard = v,
                "Show what the microphone heard while you speak."));
        items.add(toggle("Sounds", () -> cfg().playSounds, v -> cfg().playSounds = v, "Chimes when a quiz starts, passes or fails."));
        items.add(number("Panel position", "", 0, 200, 4,
                () -> cfg().hudYOffset, v -> cfg().hudYOffset = v,
                "Distance of the quiz panel from the top of the screen."));
        items.add(at -> {
            Button b = Button.builder(Component.empty(), btn -> {
                List<String> options = new ArrayList<>();
                options.add("");
                options.addAll(microphones);
                int idx = 0;
                String current = cfg().microphone == null ? "" : cfg().microphone;
                for (int i = 0; i < options.size(); i++) if (options.get(i).equalsIgnoreCase(current)) idx = i;
                cfg().microphone = options.get((idx + 1) % options.size());
                if (testing) startTest(); // re-open with the newly chosen mic
                refresh();
            }).bounds(at[0], at[1], COL_W, 20).tooltip(Tooltip.create(Component.literal(
                    "Click to switch between microphones.\n"
                            + (microphones.isEmpty() ? "No microphones were found." : "Found: " + String.join(", ", microphones))))).build();
            addRenderableWidget(b);
            refreshers.add(() -> {
                String m = cfg().microphone == null || cfg().microphone.isBlank() ? "Default" : cfg().microphone;
                if (m.length() > 14) m = m.substring(0, 13) + "…";
                b.setMessage(label("Mic", m));
            });
        });
        items.add(toggle("No mic = fail", () -> cfg().penalizeWhenSpeechUnavailable,
                v -> cfg().penalizeWhenSpeechUnavailable = v,
                "If the microphone or speech model isn't working:\nOFF = skip the quiz, ON = lose the items."));
        items.add(at -> {
            Button b = Button.builder(Component.empty(), btn -> {
                if (testing) stopTest();
                else startTest();
                refresh();
            }).bounds(at[0], at[1], COL_W, 20).tooltip(Tooltip.create(Component.literal(
                    "Listen to your microphone without a quiz.\n"
                            + "The waves show your volume: 2-3 green waves is ideal,\n"
                            + "red waves mean too loud. Also shows what was understood."))).build();
            addRenderableWidget(b);
            refreshers.add(() -> {
                b.setMessage(Component.literal(testing ? "Stop mic test" : "Test microphone"));
                ClientChallenge quiz = ClientChallenge.current();
                b.active = testing || quiz == null || quiz.state != ClientChallenge.State.LISTENING;
            });
        });
        items.add(at -> addRenderableWidget(Button.builder(Component.literal("Open config folder"), btn -> {
            try {
                openFolder(NihongoQuest.configDir());
            } catch (Throwable t) {
                NihongoQuest.LOGGER.warn("Could not open folder: {}", t.getMessage());
            }
        }).bounds(at[0], at[1], COL_W, 20).tooltip(Tooltip.create(Component.literal(
                "Opens the folder with config.json and vocabulary.json,\n"
                        + "for editing words and excluded items."))).build()));
        return items;
    }

    /** Opens a folder in the system file manager (Explorer / Finder / xdg-open). */
    private static void openFolder(java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Files.createDirectories(dir);
        String path = dir.toAbsolutePath().toString();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[]{"explorer.exe", path}
                : os.contains("mac") ? new String[]{"open", path}
                : new String[]{"xdg-open", path};
        new ProcessBuilder(cmd).start();
    }

    // ------------------------------------------------------------------ widget helpers

    private Consumer<int[]> toggle(String name, Supplier<Boolean> get, Consumer<Boolean> set, String help) {
        return at -> {
            Button b = Button.builder(Component.empty(), btn -> {
                set.accept(!get.get());
                refresh();
            }).bounds(at[0], at[1], COL_W, 20).tooltip(Tooltip.create(Component.literal(help))).build();
            addRenderableWidget(b);
            refreshers.add(() -> {
                boolean on = get.get();
                b.setMessage(Component.literal(name + ": ")
                        .append(Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED)));
            });
        };
    }

    private Consumer<int[]> cycle(String name, Supplier<String> get, Runnable next, String help) {
        return at -> {
            Button b = Button.builder(Component.empty(), btn -> {
                next.run();
                refresh();
            }).bounds(at[0], at[1], COL_W, 20).tooltip(Tooltip.create(Component.literal(help))).build();
            addRenderableWidget(b);
            refreshers.add(() -> b.setMessage(label(name, get.get())));
        };
    }

    /** [-] Name: value [+] */
    private Consumer<int[]> number(String name, String unit, int min, int max, int step,
                                   IntSupplier get, Consumer<Integer> set, String help) {
        return at -> {
            int x = at[0], y = at[1];
            Button minus = Button.builder(Component.literal("-"), btn -> {
                set.accept(Math.max(min, get.getAsInt() - step));
                refresh();
            }).bounds(x, y, 20, 20).build();
            Button value = Button.builder(Component.empty(), btn -> {})
                    .bounds(x + 22, y, COL_W - 44, 20).tooltip(Tooltip.create(Component.literal(help))).build();
            Button plus = Button.builder(Component.literal("+"), btn -> {
                set.accept(Math.min(max, get.getAsInt() + step));
                refresh();
            }).bounds(x + COL_W - 20, y, 20, 20).build();
            addRenderableWidget(minus);
            addRenderableWidget(value);
            addRenderableWidget(plus);
            refreshers.add(() -> {
                int v = get.getAsInt();
                value.setMessage(label(name, v + unit));
                minus.active = v > min;
                plus.active = v < max;
            });
        };
    }

    private static MutableComponent label(String name, String value) {
        return Component.literal(name + ": ").append(Component.literal(value).withStyle(ChatFormatting.YELLOW));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    // ------------------------------------------------------------------ microphone test

    private void startTest() {
        testHeard = "";
        testError = null;
        if (!SpeechService.isReady()) {
            testing = false;
            testError = SpeechService.unavailableReason();
            SpeechService.retryIfFailed();
            return;
        }
        testing = true;
        SpeechService.listenTest(h -> testHeard = h, e -> {
            testError = e;
            testing = false;
        });
    }

    private void stopTest() {
        if (testing) SpeechService.stop();
        testing = false;
    }

    private void drawTest(GuiGraphicsExtractor g) {
        int cx = this.width / 2;
        String error = testError;
        if (error != null) {
            int y = this.height - 60;
            g.centeredText(this.font, error.length() > 90 ? error.substring(0, 89) + "…" : error, cx, y, 0xFFFF5555);
            return;
        }
        // Big microphone icon: waves stack up (1-3) with your volume, red = too loud.
        // 2x size if there's room under the buttons, otherwise normal size.
        int scale = this.height - 52 - itemsBottom >= MicIcon.HEIGHT * 2 ? 2 : 1;
        int iconW = MicIcon.WIDTH * scale;
        int iconH = MicIcon.HEIGHT * scale;
        int iconY = this.height - 52 - iconH;
        long since = SpeechService.listeningSince;
        boolean silent = since > 0 && System.currentTimeMillis() - since > 3000 && SpeechService.peakLevel < 0.01f;
        MicIcon.drawScaled(g, cx - iconW / 2, iconY, scale, true, silent);

        String mic = SpeechService.openedMic == null || SpeechService.openedMic.isEmpty() ? "…" : SpeechService.openedMic;
        String note = MicIcon.levelNote(true);
        String line;
        int color = 0xFFFFFFFF;
        if (note != null) {
            line = note;
            color = 0xFFFF7755;
        } else if (testHeard == null || testHeard.isBlank()) {
            line = "Listening on \"" + mic + "\"… speak at your normal volume (aim for 2-3 waves)";
            color = 0xFFAAAAAA;
        } else {
            String h = testHeard.trim();
            line = "聞こえた: " + (h.length() > 40 ? "…" + h.substring(h.length() - 40) : h);
        }
        g.centeredText(this.font, line, cx, this.height - 48, color);
    }

    // ------------------------------------------------------------------ drawing & closing

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (testing || testError != null) drawTest(graphics);
        if (page == 2) {
            String msg = voiceMessage;
            VoiceService.Status vs = VoiceService.status();
            if (msg == null && (vs == VoiceService.Status.NO_JAPANESE_VOICE || vs == VoiceService.Status.UNSUPPORTED)) {
                msg = VoiceService.unavailableReason();
            }
            if (msg != null) {
                int y = this.height - 76;
                for (var line : this.font.split(Component.literal(msg), Math.min(this.width - 20, 320))) {
                    graphics.centeredText(this.font, line, this.width / 2, y, 0xFFFFAA55);
                    y += 10;
                }
            }
        }
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
        String hint = "Hover over a setting for an explanation. Saved when you click Done.";
        graphics.centeredText(this.font, hint, this.width / 2, this.height - 42, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        stopTest();
        ModConfig.save();
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
