package com.nihongoquest.vocab;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/** Kana helpers: normalisation for answer matching, and kana -> romaji. Plain Java, no Minecraft. */
public final class Kana {
    private Kana() {}

    /** Katakana -> hiragana (ァ..ヶ). Other characters unchanged. */
    public static String toHiragana(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'ァ' && c <= 'ヶ') c = (char) (c - 0x60);
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Normalise text for comparison: width/compat forms unified (NFKC), katakana folded
     * to hiragana, spaces and punctuation removed, Latin lowercased.
     */
    public static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        n = toHiragana(n);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            if (cp == 'ー' || cp == '々') { sb.appendCodePoint(cp); continue; }
            int type = Character.getType(cp);
            boolean punct = type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION;
            if (punct) continue;
            sb.appendCodePoint(Character.toLowerCase(cp));
        }
        return sb.toString();
    }

    /** True if the string is only hiragana/katakana (plus ー and spaces). */
    public static boolean isAllKana(String s) {
        if (s == null || s.isBlank()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean kana = (c >= 'ぁ' && c <= 'ゖ') || (c >= 'ァ' && c <= 'ヺ')
                    || c == 'ー' || c == ' ' || c == '・' || c == '　';
            if (!kana) return false;
        }
        return true;
    }

    // --------------------------------------------------------------- romaji

    private static final Map<Character, String> BASE = new HashMap<>();
    private static final Map<String, String> DIGRAPH = new HashMap<>();

    private static void b(String kana, String... romaji) {
        for (int i = 0; i < kana.length(); i++) BASE.put(kana.charAt(i), romaji[i]);
    }

    static {
        b("あいうえお", "a", "i", "u", "e", "o");
        b("かきくけこ", "ka", "ki", "ku", "ke", "ko");
        b("がぎぐげご", "ga", "gi", "gu", "ge", "go");
        b("さしすせそ", "sa", "shi", "su", "se", "so");
        b("ざじずぜぞ", "za", "ji", "zu", "ze", "zo");
        b("たちつてと", "ta", "chi", "tsu", "te", "to");
        b("だぢづでど", "da", "ji", "zu", "de", "do");
        b("なにぬねの", "na", "ni", "nu", "ne", "no");
        b("はひふへほ", "ha", "hi", "fu", "he", "ho");
        b("ばびぶべぼ", "ba", "bi", "bu", "be", "bo");
        b("ぱぴぷぺぽ", "pa", "pi", "pu", "pe", "po");
        b("まみむめも", "ma", "mi", "mu", "me", "mo");
        b("やゆよ", "ya", "yu", "yo");
        b("らりるれろ", "ra", "ri", "ru", "re", "ro");
        b("わゐゑをん", "wa", "i", "e", "o", "n");
        b("ゔ", "vu");
        b("ぁぃぅぇぉ", "a", "i", "u", "e", "o");
        b("ゃゅょゎ", "ya", "yu", "yo", "wa");

        String[][] yoon = {
                {"き", "ky"}, {"ぎ", "gy"}, {"し", "sh"}, {"じ", "j"}, {"ち", "ch"}, {"ぢ", "j"},
                {"に", "ny"}, {"ひ", "hy"}, {"び", "by"}, {"ぴ", "py"}, {"み", "my"}, {"り", "ry"}
        };
        String[] small = {"ゃ", "ゅ", "ょ"};
        String[] vowels = {"a", "u", "o"};
        for (String[] y : yoon) {
            for (int i = 0; i < 3; i++) DIGRAPH.put(y[0] + small[i], y[1] + vowels[i]);
        }
    }

    /** Hepburn-style romaji. Long vowels are written doubled (ちゅう -> chuu, ケーキ -> keeki). */
    public static String toRomaji(String text) {
        String s = toHiragana(Normalizer.normalize(text, Normalizer.Form.NFKC));
        StringBuilder out = new StringBuilder();
        boolean geminate = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'っ') { geminate = true; continue; }
            if (c == ' ' || c == '　' || c == '・') { out.append(' '); continue; }
            if (c == 'ー') {
                char last = lastVowel(out);
                if (last != 0) out.append(last);
                continue;
            }
            String unit = null;
            if (i + 1 < s.length()) {
                String pair = s.substring(i, i + 2);
                unit = DIGRAPH.get(pair);
                if (unit != null) {
                    i++;
                } else {
                    char next = s.charAt(i + 1);
                    // Small vowels after a normal kana: ふぁ -> fa, てぃ -> ti, しぇ -> she, うぃ -> wi
                    if ("ぁぃぅぇぉ".indexOf(next) >= 0 && BASE.containsKey(c) && "ぁぃぅぇぉゃゅょ".indexOf(c) < 0) {
                        String base = BASE.get(c);
                        String stem = c == 'う' ? "w" : base.substring(0, base.length() - 1);
                        unit = stem + BASE.get(next);
                        i++;
                    }
                }
            }
            if (unit == null) unit = BASE.get(c);
            if (unit == null) { out.append(c); geminate = false; continue; }
            if (geminate) {
                out.append(unit.startsWith("ch") ? 't' : unit.charAt(0));
                geminate = false;
            }
            // ん before a vowel or y gets an apostrophe: きんえん -> kin'en
            if (c == 'ん' && i + 1 < s.length()) {
                String nextRomaji = BASE.get(s.charAt(i + 1));
                if (nextRomaji != null && "aiueoy".indexOf(nextRomaji.charAt(0)) >= 0) {
                    out.append("n'");
                    continue;
                }
            }
            out.append(unit);
        }
        return out.toString().trim();
    }

    private static char lastVowel(StringBuilder sb) {
        for (int i = sb.length() - 1; i >= 0; i--) {
            char c = sb.charAt(i);
            if ("aiueo".indexOf(c) >= 0) return c;
            if (c == ' ') break;
        }
        return 0;
    }

    // --------------------------------------------------------------- matching

    /** Similarity in [0,1] based on Levenshtein distance. */
    public static double similarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int max = Math.max(a.length(), b.length());
        return 1.0 - (double) levenshtein(a, b) / max;
    }

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /**
     * Does the heard text count as saying {@code target}? Both must already be normalised.
     * Passes if the word appears anywhere in what was heard, or if some stretch of the heard
     * text is at least {@code strictness} similar to it.
     */
    public static boolean matches(String heard, String target, double strictness) {
        if (heard.isEmpty() || target.isEmpty()) return false;
        if (heard.contains(target)) return true;
        if (strictness >= 1.0) return false;
        int len = target.length();
        if (heard.length() <= len + 1) return similarity(heard, target) >= strictness;
        // Slide a window a little wider/narrower than the target across what was heard.
        for (int w = Math.max(1, len - 1); w <= len + 1; w++) {
            for (int start = 0; start + w <= heard.length(); start++) {
                if (similarity(heard.substring(start, start + w), target) >= strictness) return true;
            }
        }
        return false;
    }
}
