package com.nihongoquest.vocab;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Item ID -> Japanese word lookup.
 *
 * Built-in list (inside the jar) is loaded first, then config/nihongoquest/vocabulary.json
 * is layered on top so players can fix or add words. Deliberately free of Minecraft classes.
 */
public final class Vocabulary {
    private Vocabulary() {}

    /** A word the player has to say. {@code accepted} holds every spelling that counts. */
    public record Word(String kanji, String kana, String romaji, List<String> accepted, boolean curated) {
        /** Main written form: kanji if there is one, otherwise kana. */
        public String primary() {
            return kanji != null && !kanji.isBlank() ? kanji : kana;
        }

        public boolean hasSeparateKanji() {
            return kanji != null && !kanji.isBlank() && kana != null && !kanji.equals(kana);
        }
    }

    static final class Entry {
        String kanji;
        String kana;
        String romaji;
        List<String> accept;
    }

    private static final Gson GSON = new Gson();
    private static volatile Data data = new Data();

    private static final class Data {
        final Map<String, Entry> exact = new HashMap<>();
        final Map<String, Entry> suffix = new HashMap<>(); // "*_log"   -> key "_log"
        final Map<String, Entry> prefix = new HashMap<>(); // "music_*" -> key "music_"
        final Map<String, Entry> colors = new HashMap<>();
        List<String> colorKeysLongestFirst = List.of();
    }

    // ------------------------------------------------------------------ loading

    /** Loads using the mod's config folder. */
    public static void load() {
        load(com.nihongoquest.NihongoQuest.configDir().resolve("vocabulary.json"),
                msg -> com.nihongoquest.NihongoQuest.LOGGER.warn(msg));
    }

    public static synchronized void load(Path userFile, Consumer<String> warn) {
        Data d = new Data();
        try (InputStream in = Vocabulary.class.getResourceAsStream("/assets/nihongoquest/default_vocabulary.json")) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    merge(d, JsonParser.parseReader(r), warn);
                }
            }
        } catch (Exception e) {
            warn.accept("Built-in vocabulary failed to load: " + e.getMessage());
        }

        try {
            if (Files.notExists(userFile)) {
                Files.createDirectories(userFile.getParent());
                try (InputStream in = Vocabulary.class.getResourceAsStream("/assets/nihongoquest/default_vocabulary.json")) {
                    if (in != null) Files.copy(in, userFile);
                }
            } else {
                try (Reader r = Files.newBufferedReader(userFile, StandardCharsets.UTF_8)) {
                    merge(d, JsonParser.parseReader(r), warn);
                }
            }
        } catch (Exception e) {
            warn.accept("Could not read " + userFile + " (built-in words are still used): " + e.getMessage());
        }

        List<String> keys = new ArrayList<>(d.colors.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        d.colorKeysLongestFirst = Collections.unmodifiableList(keys);
        data = d;
    }

    private static void merge(Data d, JsonElement root, Consumer<String> warn) {
        if (root == null || !root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("colors") && obj.get("colors").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("colors").entrySet()) {
                Entry entry = parse(e.getKey(), e.getValue(), warn);
                if (entry != null) d.colors.put(e.getKey().trim(), entry);
            }
        }
        if (obj.has("items") && obj.get("items").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("items").entrySet()) {
                Entry entry = parse(e.getKey(), e.getValue(), warn);
                if (entry == null) continue;
                String key = stripMinecraft(e.getKey().trim());
                if (key.startsWith("*")) d.suffix.put(key.substring(1), entry);
                else if (key.endsWith("*")) d.prefix.put(key.substring(0, key.length() - 1), entry);
                else d.exact.put(key, entry);
            }
        }
    }

    private static Entry parse(String key, JsonElement json, Consumer<String> warn) {
        try {
            Entry e = GSON.fromJson(json, Entry.class);
            if (e == null || e.kana == null || e.kana.isBlank()) {
                warn.accept("Vocabulary entry '" + key + "' has no \"kana\" — skipped.");
                return null;
            }
            if (e.romaji == null || e.romaji.isBlank()) e.romaji = Kana.toRomaji(e.kana);
            return e;
        } catch (Exception ex) {
            warn.accept("Vocabulary entry '" + key + "' is malformed — skipped.");
            return null;
        }
    }

    private static String stripMinecraft(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    // ------------------------------------------------------------------ lookup

    /**
     * @param itemId       e.g. "minecraft:red_wool"
     * @param colorWords   prefix colored items with their color word
     * @return the word, or null if there's no hand-written entry
     */
    public static Word lookup(String itemId, boolean colorWords) {
        Data d = data;
        String key = stripMinecraft(itemId);
        Entry exact = d.exact.get(key);
        if (exact != null) return toWord(exact);

        String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;

        if (colorWords) {
            for (String color : d.colorKeysLongestFirst) {
                if (path.startsWith(color + "_")) {
                    String rest = path.substring(color.length() + 1);
                    Entry noun = d.exact.get(rest);
                    if (noun == null) noun = wildcard(d, "_" + rest);
                    if (noun != null) return compose(d.colors.get(color), noun);
                }
            }
        }

        Entry wild = wildcard(d, path);
        return wild != null ? toWord(wild) : null;
    }

    private static Entry wildcard(Data d, String path) {
        Entry best = null;
        int bestLen = -1;
        for (Map.Entry<String, Entry> e : d.suffix.entrySet()) {
            if (path.endsWith(e.getKey()) && e.getKey().length() > bestLen) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        for (Map.Entry<String, Entry> e : d.prefix.entrySet()) {
            if (path.startsWith(e.getKey()) && e.getKey().length() > bestLen) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        return best;
    }

    private static Word toWord(Entry e) {
        Set<String> accepted = new LinkedHashSet<>();
        addForms(accepted, e);
        return new Word(blankToNull(e.kanji), e.kana, e.romaji, List.copyOf(accepted), true);
    }

    private static Word compose(Entry color, Entry noun) {
        Set<String> colorForms = new LinkedHashSet<>();
        addForms(colorForms, color);
        Set<String> nounForms = new LinkedHashSet<>();
        addForms(nounForms, noun);
        Set<String> accepted = new LinkedHashSet<>();
        for (String c : colorForms) for (String n : nounForms) {
            accepted.add(c + n);
            accepted.add(c + " " + n); // two words, as the speech recognizer writes them
        }

        String colorWritten = color.kanji != null && !color.kanji.isBlank() ? color.kanji : color.kana;
        String nounWritten = noun.kanji != null && !noun.kanji.isBlank() ? noun.kanji : noun.kana;
        String kanji = colorWritten + nounWritten;
        String kana = color.kana + noun.kana;
        return new Word(kanji.equals(kana) ? null : kanji, kana, color.romaji + " " + noun.romaji,
                List.copyOf(accepted), true);
    }

    private static void addForms(Set<String> out, Entry e) {
        if (e.kanji != null && !e.kanji.isBlank()) out.add(e.kanji);
        out.add(e.kana);
        if (e.accept != null) for (String a : e.accept) if (a != null && !a.isBlank()) out.add(a);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Builds a word from Minecraft's own Japanese item name (used when there's no entry). */
    public static Word fromGameTranslation(String japaneseName) {
        String name = japaneseName.trim();
        boolean kanaOnly = Kana.isAllKana(name);
        String romaji = kanaOnly ? Kana.toRomaji(name) : null;
        return new Word(kanaOnly ? null : name, kanaOnly ? name : null, romaji, List.of(name), false);
    }

    /**
     * Decoy words for the word-list recognizer: other vocabulary words that don't sound like
     * the target, so "anything vaguely similar" can't be forced into the right answer.
     */
    public static List<String> decoys(Word target, int count, long seed) {
        Data d = data;
        String targetRomaji = target.romaji() == null ? "" : target.romaji().replaceAll("[^a-z]", "");
        Set<String> targetForms = new java.util.HashSet<>();
        for (String a : target.accepted()) targetForms.add(Kana.normalize(a));
        List<Entry> pool = new ArrayList<>(d.exact.values());
        pool.addAll(d.suffix.values());
        pool.addAll(d.prefix.values());
        Collections.shuffle(pool, new java.util.Random(seed));
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : pool) {
            if (out.size() >= count) break;
            String r = e.romaji == null ? "" : e.romaji.replaceAll("[^a-z]", "");
            if (r.isEmpty() || targetForms.contains(Kana.normalize(e.kana))) continue;
            if (!targetRomaji.isEmpty() && (Kana.similarity(r, targetRomaji) >= 0.5
                    || r.contains(targetRomaji) || targetRomaji.contains(r))) continue;
            out.add(e.kana);
            if (e.kanji != null && !e.kanji.isBlank()) out.add(e.kanji);
        }
        return new ArrayList<>(out);
    }

    public static boolean has(String itemId, boolean colorWords) {
        return lookup(itemId, colorWords) != null;
    }
}
