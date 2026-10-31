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
    public Boolean showPronunciation;
    public Boolean speakAtQuizStart;

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

    // ---- pronunciation help ----
    public boolean speakOnFail = true;
    public int voiceSpeed = -2;

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

    /**
     * Writes the current values into config.json, changing only the value on each setting's
     * line so all comments are kept. Preset overrides that are null get commented out again.
     */
    public static synchronized void save() {
        ModConfig c = instance;
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                try (InputStream in = ModConfig.class.getResourceAsStream("/assets/nihongoquest/default_config.json")) {
                    if (in != null) Files.copy(in, path);
                }
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Gson out = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("difficulty", c.preset().name());
            values.put("timeLimitSeconds", c.timeLimitSeconds);
            values.put("quizEveryNPickups", c.quizEveryNPickups);
            values.put("showKanji", c.showKanji);
            values.put("showKana", c.showKana);
            values.put("showRomaji", c.showRomaji);
            values.put("showEnglish", c.showEnglish);
            values.put("matchStrictness", c.matchStrictness);
            values.put("showPronunciation", c.showPronunciation);
            values.put("speakAtQuizStart", c.speakAtQuizStart);
            values.put("quizOnFirstPickup", c.quizOnFirstPickup);
            values.put("countMode", c.countEveryItem() ? "items" : "pickups");
            values.put("ignoreCreativeMode", c.ignoreCreativeMode);
            values.put("onlyItemsWithBuiltInWords", c.onlyItemsWithBuiltInWords);
            values.put("includeColorWords", c.includeColorWords);
            values.put("autoDownloadSpeechModel", c.autoDownloadSpeechModel);
            values.put("microphone", c.microphone == null ? "" : c.microphone);
            values.put("penalizeWhenSpeechUnavailable", c.penalizeWhenSpeechUnavailable);
            values.put("playSounds", c.playSounds);
            values.put("hudYOffset", c.hudYOffset);
            values.put("showWhatWasHeard", c.showWhatWasHeard);
            values.put("speakOnFail", c.speakOnFail);
            values.put("voiceSpeed", c.voiceSpeed);
            for (var e : values.entrySet()) {
                text = ConfigWriter.set(text, e.getKey(), e.getValue() == null ? null : out.toJson(e.getValue()));
            }
            Files.writeString(path, text, StandardCharsets.UTF_8);
            NihongoQuest.LOGGER.info("Config saved.");
        } catch (Exception e) {
            NihongoQuest.LOGGER.error("Could not save {}: {}", path, e.getMessage());
        }
    }

    /** Switches preset and drops all per-setting overrides. */
    public void applyPreset(Preset p) {
        difficulty = p.name();
        clearOverrides();
    }

    public void clearOverrides() {
        timeLimitSeconds = null;
        quizEveryNPickups = null;
        showKanji = null;
        showKana = null;
        showRomaji = null;
        showEnglish = null;
        matchStrictness = null;
        showPronunciation = null;
        speakAtQuizStart = null;
    }

    public boolean hasOverrides() {
        return timeLimitSeconds != null || quizEveryNPickups != null || showKanji != null || showKana != null
                || showRomaji != null || showEnglish != null || matchStrictness != null
                || showPronunciation != null || speakAtQuizStart != null;
    }

    // ------------------------------------------------------------------ presets

    public record Preset(String name, int timeLimitSeconds, int quizEvery, boolean kanji, boolean kana,
                         boolean romaji, boolean english, double strictness,
                         boolean pronunciation, boolean speakAtStart) {}

    public static final Preset BEGINNER = new Preset("beginner", 45, 10, true, true, true, true, 0.60, true, true);
    public static final Preset NORMAL   = new Preset("normal",   30, 10, true, true, true, true, 0.75, true, false);
    public static final Preset ADVANCED = new Preset("advanced", 20,  5, true, true, false, true, 0.90, false, false);
    public static final Preset EXPERT   = new Preset("expert",   15,  3, false, false, false, true, 0.95, false, false);
    public static final List<Preset> PRESETS = List.of(BEGINNER, NORMAL, ADVANCED, EXPERT);

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
    public boolean showPronunciation() { return showPronunciation != null ? showPronunciation : preset().pronunciation(); }
    public boolean speakAtQuizStart() { return speakAtQuizStart != null ? speakAtQuizStart : preset().speakAtStart(); }

    /** Voice speed from -5 (slowest) to 5 (fastest); 0 is normal. */
    public int voiceSpeed() {
        return Math.max(-5, Math.min(5, voiceSpeed));
    }

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
