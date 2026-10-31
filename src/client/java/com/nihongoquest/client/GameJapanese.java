package com.nihongoquest.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.nihongoquest.NihongoQuest;
import com.nihongoquest.vocab.Vocabulary;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/** Fallback words from Minecraft's own Japanese translation (ja_jp), for items without an entry. */
final class GameJapanese {
    private GameJapanese() {}

    private static Map<String, String> names;

    static void invalidate() {
        names = null;
    }

    static Vocabulary.Word wordFor(Item item) {
        if (names == null) load();
        String name = names.get(item.getDescriptionId());
        if (name == null || name.isBlank()) return null;
        // Kanji names have no reading in the game files, so the player couldn't know how to
        // say them. Only kana-only names (which spell out their own reading) are usable.
        if (!com.nihongoquest.vocab.Kana.isAllKana(name)) return null;
        return Vocabulary.fromGameTranslation(name);
    }

    private static void load() {
        Map<String, String> map = new HashMap<>();
        try {
            ResourceManager rm = Minecraft.getInstance().getResourceManager();
            for (String namespace : rm.getNamespaces()) {
                Identifier file = Identifier.fromNamespaceAndPath(namespace, "lang/ja_jp.json");
                for (Resource res : rm.getResourceStack(file)) {
                    try (Reader r = res.openAsReader()) {
                        for (Map.Entry<String, JsonElement> e : JsonParser.parseReader(r).getAsJsonObject().entrySet()) {
                            if (e.getValue().isJsonPrimitive()) map.put(e.getKey(), e.getValue().getAsString());
                        }
                    } catch (Exception e) {
                        NihongoQuest.LOGGER.debug("Skipping {}: {}", file, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            NihongoQuest.LOGGER.warn("Could not load Minecraft's Japanese translation: {}", e.getMessage());
        }
        names = map;
    }
}
