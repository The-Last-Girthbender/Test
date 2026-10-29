package com.nihongoquest.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.nihongoquest.NihongoQuest;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings from config/nihongoquest/config.json.
 *
 * The file is JSON with // comments (parsed leniently). It is only ever written once
 * (copied from the mod's commented template), so the player's comments and layout
 * are never overwritten.
 *
 * Preset-controlled settings are nullable: null means "use the difficulty preset".
 */
public final class ModConfig {
    private static final Gson GSON = new Gson();
    private static volatile ModConfig instance = new ModConfig();

    // ---- difficulty preset + optional overrides ----
    public String difficulty = "normal";
    public Integer timeLimitSeconds;
    public Integer quizEveryNPickups;
    public Boolean showKanji;
    public Boolean showKana;
    public Boolean showRomaji;
    public Boolean showEnglish;
    public Double matchStrictness;

    // ---- when quizzes happen ----
    public boolean quizOnFirstPickup = false;
    public String countMode = "pickups";
    public boolean ignoreCreativeMode = true;
    public double recountCooldownSeconds = 5;
    public boolean onlyItemsWithBuiltInWords = false;
    public List<String> excludedItems = new ArrayList<>();
    public boolean includeColorWords = true;

    // ---- speech ----
    public boolean autoDownloadSpeechModel = true;
    public String speechModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip";
    public String speechModelFolder = "vosk-model-small-ja-0.22";
    public String microphone = "";
    public boolean penalizeWhenSpeechUnavailable = false;

    // ---- display ----
    public boolean playSounds = true;
    public int hudYOffset = 6;
    public boolean showWhatWasHeard = true;

    public static ModConfig get() {
        return instance;
    }

    public static Path file() {
        return NihongoQuest.configDir().resolve("config.json");
    }

    public static synchronized void load() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                try (InputStream in = ModConfig.class.getResourceAsStream("/assets/nihongoquest/default_config.json")) {
                    if (in != null) Files.copy(in, path);
                }
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement json = JsonParser.parseReader(reader); // lenient: allows // comments
                ModConfig loaded = GSON.fromJson(json, ModConfig.class);
                if (loaded != null) {
                    if (loaded.excludedItems == null) loaded.excludedItems = new ArrayList<>();
                    instance = loaded;
                }
            }
            NihongoQuest.LOGGER.info("Config loaded (difficulty: {})", instance.preset().name);
        } catch (Exception e) {
            NihongoQuest.LOGGER.error("Could not read {} — using defaults. Fix or delete the file. Error: {}", path, e.getMessage());
            instance = new ModConfig();
        }
    }

    // ------------------------------------------------------------------ presets

    public record Preset(String name, int timeLimitSeconds, int quizEvery, boolean kanji, boolean kana,
                         boolean romaji, boolean english, double strictness) {}

    public static final Preset BEGINNER = new Preset("beginner", 45, 10, true, true, true, true, 0.60);
    public static final Preset NORMAL   = new Preset("normal",   30, 10, true, true, true, true, 0.75);
    public static final Preset ADVANCED = new Preset("advanced", 20,  5, true, true, false, true, 0.90);
    public static final Preset EXPERT   = new Preset("expert",   15,  3, false, false, false, true, 0.95);

    public Preset preset() {
        String d = difficulty == null ? "normal" : difficulty.trim().toLowerCase(Locale.ROOT);
        return switch (d) {
            case "beginner", "easy" -> BEGINNER;
            case "advanced", "hard" -> ADVANCED;
            case "expert" -> EXPERT;
            default -> NORMAL;
        };
    }

    public int timeLimitSeconds() {
        int v = timeLimitSeconds != null ? timeLimitSeconds : preset().timeLimitSeconds();
        return Math.max(5, Math.min(600, v));
    }

    public int quizEveryNPickups() {
        int v = quizEveryNPickups != null ? quizEveryNPickups : preset().quizEvery();
        return Math.max(1, Math.min(100000, v));
    }

    public boolean showKanji()   { return showKanji   != null ? showKanji   : preset().kanji(); }
    public boolean showKana()    { return showKana    != null ? showKana    : preset().kana(); }
    public boolean showRomaji()  { return showRomaji  != null ? showRomaji  : preset().romaji(); }
    public boolean showEnglish() { return showEnglish != null ? showEnglish : preset().english(); }

    public double matchStrictness() {
        double v = matchStrictness != null ? matchStrictness : preset().strictness();
        return Math.max(0.0, Math.min(1.0, v));
    }

    public boolean countEveryItem() {
        return countMode != null && countMode.trim().equalsIgnoreCase("items");
    }

    public int recountCooldownTicks() {
        return (int) Math.max(0, Math.round(recountCooldownSeconds * 20));
    }

    public boolean isExcluded(String itemId) {
        for (String s : excludedItems) {
            if (s == null) continue;
            String e = s.trim();
            if (e.equals(itemId) || ("minecraft:" + e).equals(itemId)) return true;
        }
        return false;
    }
}
