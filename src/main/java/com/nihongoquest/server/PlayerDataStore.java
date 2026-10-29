package com.nihongoquest.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nihongoquest.NihongoQuest;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Saves each player's pickup counters and scores in <world>/nihongoquest/<uuid>.json. */
public final class PlayerDataStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class PlayerData {
        /** item ID -> pickups (or items, in "items" count mode) so far */
        public Map<String, Integer> pickups = new HashMap<>();
        /** item ID -> times said correctly */
        public Map<String, Integer> correct = new HashMap<>();
        /** item ID -> times failed */
        public Map<String, Integer> failed = new HashMap<>();

        void fixNulls() {
            if (pickups == null) pickups = new HashMap<>();
            if (correct == null) correct = new HashMap<>();
            if (failed == null) failed = new HashMap<>();
        }
    }

    private final Path dir;

    public PlayerDataStore(Path dir) {
        this.dir = dir;
    }

    public PlayerData load(UUID uuid) {
        Path file = dir.resolve(uuid + ".json");
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                PlayerData d = GSON.fromJson(r, PlayerData.class);
                if (d != null) {
                    d.fixNulls();
                    return d;
                }
            } catch (Exception e) {
                NihongoQuest.LOGGER.warn("Could not read {}: {}", file, e.getMessage());
            }
        }
        return new PlayerData();
    }

    public void save(UUID uuid, PlayerData data) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(uuid + ".json");
            Path tmp = dir.resolve(uuid + ".json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            NihongoQuest.LOGGER.warn("Could not save Nihongo Quest data for {}: {}", uuid, e.getMessage());
        }
    }
}
