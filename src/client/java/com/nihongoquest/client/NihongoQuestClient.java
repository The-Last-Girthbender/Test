package com.nihongoquest.client;

import com.nihongoquest.NihongoQuest;
import com.nihongoquest.client.speech.SpeechService;
import com.nihongoquest.config.ModConfig;
import com.nihongoquest.network.Payloads.ChallengeResolved;
import com.nihongoquest.network.Payloads.ChallengeStart;
import com.nihongoquest.vocab.Vocabulary;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class NihongoQuestClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ChallengeStart.TYPE,
                (payload, context) -> ClientChallenge.onStart(payload));
        ClientPlayNetworking.registerGlobalReceiver(ChallengeResolved.TYPE,
                (payload, context) -> ClientChallenge.onResolved(payload));

        ClientTickEvents.END_CLIENT_TICK.register(ClientChallenge::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ConfigScreen::tick);

        // /nihongo (or /nihongo config) opens the settings screen.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("nihongo")
                    .executes(context -> {
                        ConfigScreen.openNextTick = true;
                        return 1;
                    })
                    .then(ClientCommands.literal("config").executes(context -> {
                        ConfigScreen.openNextTick = true;
                        return 1;
                    })));
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, NihongoQuest.id("challenge"),
                ChallengeHud::extract);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Pick up config/vocabulary edits each time you join a world.
            ModConfig.load();
            Vocabulary.load();
            GameJapanese.invalidate();
            SpeechService.retryIfFailed();
            client.execute(() -> {
                String msg = switch (SpeechService.status()) {
                    case READY -> null;
                    case DOWNLOADING -> "Downloading the Japanese speech model in the background — quizzes start once it's done.";
                    default -> SpeechService.unavailableReason();
                };
                if (msg != null && !msg.isBlank() && client.player != null) {
                    client.player.sendSystemMessage(Component.literal("[日本語クエスト] ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(msg).withStyle(ChatFormatting.GRAY)));
                }
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientChallenge::clear));

        SpeechService.logMicrophones();
        SpeechService.prepare(); // download (first launch) + load the model in the background
    }
}
