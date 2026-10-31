package com.nihongoquest.vocab;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * English "sounds like" respellings for kana, e.g. りんご -> "reen-goh", はっぱ -> "hah.pah",
 * ケーキ -> "kehh-kee". Plain Java, no Minecraft.
 *
 * "-" joins sounds that flow straight on (no stopping); "." marks the one real pause in
 * Japanese words, the small っ (a short silent beat before the next sound).
 *
 * Vowels are always pure in Japanese, so they're respelled the same way every time:
 * a = "ah", i = "ee", u = "oo", e = "eh", o = "oh". A doubled letter at the end
 * ("ahh", "ooo") means "hold it twice as long".
 */
public final class Pronunciation {
    private Pronunciation() {}

    private static final String SMALL_Y = "ゃゅょ";
    private static final String SMALL_V = "ぁぃぅぇぉ";

    /** Respelling for the kana, or null if the text isn't all kana. */
    public static String guide(String kana) {
        if (kana == null || kana.isBlank()) return null;
        String s = Kana.toHiragana(Normalizer.normalize(kana, Normalizer.Form.NFKC));

        List<StringBuilder> syllables = new ArrayList<>();
        List<Boolean> pauseBefore = new ArrayList<>();
        StringBuilder cur = null;
        char curVowel = 0;          // last vowel letter of the current syllable (a/i/u/e/o)
        boolean geminate = false;   // っ seen: next consonant also closes the current syllable

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '　' || c == '・') continue;

            if (c == 'っ') { geminate = true; continue; }

            if (c == 'ー') {
                if (cur != null && curVowel != 0) lengthen(cur, curVowel);
                continue;
            }

            if (c == 'ん') {
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                String n = "ばびぶべぼぱぴぷぺぽまみむめも".indexOf(next) >= 0 ? "m" : "n";
                if (cur == null) { cur = new StringBuilder(n); syllables.add(cur); pauseBefore.add(false); }
                else cur.append(n);
                curVowel = 0;
                continue;
            }

            // One mora: a kana plus any small ゃゅょ / ぁぃぅぇぉ after it.
            String unit = String.valueOf(c);
            if (i + 1 < s.length() && (SMALL_Y.indexOf(s.charAt(i + 1)) >= 0 || SMALL_V.indexOf(s.charAt(i + 1)) >= 0)
                    && SMALL_Y.indexOf(c) < 0 && SMALL_V.indexOf(c) < 0) {
                unit = s.substring(i, i + 2);
                i++;
            }
            String romaji = Kana.toRomaji(unit);
            if (romaji.isEmpty() || !romaji.chars().allMatch(ch -> ch >= 'a' && ch <= 'z')) return null;

            char vowel = romaji.charAt(romaji.length() - 1);
            String consonant = "aiueo".indexOf(vowel) >= 0 ? romaji.substring(0, romaji.length() - 1) : romaji;
            if ("aiueo".indexOf(vowel) < 0) vowel = 0;

            // A bare vowel right after the same sound makes it long: おう, ああ, いい, うう, ええ.
            if (consonant.isEmpty() && cur != null && curVowel != 0 && !geminate) {
                boolean same = vowel == curVowel || (curVowel == 'o' && vowel == 'u');
                if (same) {
                    lengthen(cur, curVowel);
                    continue;
                }
                if (curVowel == 'e' && vowel == 'i') { // えい sounds like "ay"
                    replaceEnd(cur, "eh", "ay");
                    curVowel = 'i';
                    continue;
                }
            }

            boolean pause = geminate && cur != null;
            geminate = false;

            cur = new StringBuilder(consonant).append(vowel == 0 ? "" : vowelSound(vowel));
            syllables.add(cur);
            pauseBefore.add(pause);
            curVowel = vowel;
        }

        if (syllables.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < syllables.size(); k++) {
            if (k > 0) out.append(pauseBefore.get(k) ? '.' : '-');
            out.append(syllables.get(k));
        }
        return out.toString();
    }

    /** One short tip about the trickiest sound in the word, or null. */
    public static String tip(String kana, String romaji) {
        if (kana == null) return null;
        String s = Kana.toHiragana(kana);
        String r = romaji == null ? Kana.toRomaji(kana) : romaji;
        if (r.contains("tsu")) return "tsu = the \"ts\" in \"cats\" + oo";
        if (r.contains("fu")) return "fu = blow softly, lips barely touching";
        if (r.contains("r"))
            return "r = a light tap, like the \"dd\" in \"ladder\"";
        if (s.indexOf('ー') >= 0 || r.matches(".*(aa|ii|uu|ee|oo|ou).*"))
            return "Doubled vowels are held twice as long";
        if (r.contains("g")) return "g is always hard, like \"go\"";
        return null;
    }

    private static String vowelSound(char v) {
        return switch (v) {
            case 'a' -> "ah";
            case 'i' -> "ee";
            case 'u' -> "oo";
            case 'e' -> "eh";
            case 'o' -> "oh";
            default -> "";
        };
    }

    private static void lengthen(StringBuilder sb, char vowel) {
        String sound = vowelSound(vowel);
        if (sound.isEmpty()) return;
        if (vowel == 'i' && sb.toString().endsWith("ay")) return; // already "ay"
        sb.append(sound.charAt(sound.length() - 1));
    }

    private static void replaceEnd(StringBuilder sb, String from, String to) {
        int idx = sb.lastIndexOf(from);
        if (idx >= 0 && idx == sb.length() - from.length()) sb.replace(idx, sb.length(), to);
    }
}
