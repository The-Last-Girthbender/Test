package com.nihongoquest;

import com.nihongoquest.config.ModConfig;
import com.nihongoquest.network.Payloads;
import com.nihongoquest.server.ChallengeManager;
import com.nihongoquest.vocab.Vocabulary;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** Common entrypoint: runs on both the client and dedicated servers. */
public class NihongoQuest implements ModInitializer {
    public static final String MOD_ID = "nihongoquest";
    public static final Logger LOGGER = LoggerFactory.getLogger("Nihongo Quest");

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** .minecraft/config/nihongoquest/ */
    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
    }

    @Override
    public void onInitialize() {
        ModConfig.load();
        Vocabulary.load();
        Payloads.register();
        ChallengeManager.init();
        LOGGER.info("Nihongo Quest loaded — がんばって! (Good luck!)");
    }
}
