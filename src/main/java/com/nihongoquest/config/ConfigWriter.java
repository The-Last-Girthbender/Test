package com.nihongoquest.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Changes single values inside the commented config.json text without touching anything else,
 * so the explanations in the file survive saving from the in-game screen.
 */
public final class ConfigWriter {
    private ConfigWriter() {}

    /**
     * @param text      current file contents
     * @param key       setting name
     * @param jsonValue new value as JSON (e.g. {@code 30}, {@code true}, {@code "normal"}),
     *                  or null to comment the setting out (= "use the preset")
     */
    public static String set(String text, String key, String jsonValue) {
        Pattern p = Pattern.compile(
                "^([ \\t]*)(//[ \\t]*)?\"" + Pattern.quote(key) + "\"[ \\t]*:[ \\t]*"
                        + "(\"(?:[^\"\\\\\\n]|\\\\.)*\"|[^,\\s]+)[ \\t]*(,?)([^\\n]*)$",
                Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        int start = -1, end = -1;
        String indent = "  ", comma = ",", rest = "";
        // Prefer the active (uncommented) line; otherwise use the commented-out example.
        while (m.find()) {
            boolean commented = m.group(2) != null;
            if (start < 0 || !commented) {
                start = m.start();
                end = m.end();
                indent = m.group(1);
                comma = m.group(4);
                rest = m.group(5);
                if (!commented) break;
            }
        }

        if (start < 0) {
            if (jsonValue == null) return text;
            // Not in the file at all: add it right after the opening brace.
            Matcher brace = Pattern.compile("^[ \\t]*\\{[ \\t]*$", Pattern.MULTILINE).matcher(text);
            String line = "  \"" + key + "\": " + jsonValue + ",";
            if (brace.find()) {
                return text.substring(0, brace.end()) + "\n" + line + text.substring(brace.end());
            }
            return text;
        }

        String line = indent + (jsonValue == null ? "// " : "") + "\"" + key + "\": "
                + (jsonValue == null ? "null" : jsonValue) + comma + rest;
        if (jsonValue == null) {
            // Keep the original example value when commenting out, rather than writing null.
            Matcher again = p.matcher(text.substring(start, end));
            if (again.find()) {
                line = indent + "// \"" + key + "\": " + again.group(3) + comma + rest;
            }
        }
        return text.substring(0, start) + line + text.substring(end);
    }
}
