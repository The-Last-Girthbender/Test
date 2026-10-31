package com.nihongoquest.client.speech;

import com.nihongoquest.NihongoQuest;
import com.nihongoquest.config.ModConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads Japanese words aloud with the operating system's own Japanese voice
 * (Windows: System.Speech via PowerShell, macOS: "say", Linux: espeak-ng).
 * Nothing is downloaded. While speaking, the microphone is muted for the quiz.
 */
public final class VoiceService {
    private VoiceService() {}

    public enum Status { UNKNOWN, CHECKING, AVAILABLE, NO_JAPANESE_VOICE, UNSUPPORTED }

    private static volatile Status status = Status.UNKNOWN;
    private static volatile Process current;
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    public static Status status() {
        return status;
    }

    /** Why the voice can't be used, for chat/screen messages. */
    public static String unavailableReason() {
        return switch (status) {
            case NO_JAPANESE_VOICE -> OS.contains("win")
                    ? "No Japanese voice installed. Windows Settings > Time & language > Language & region > "
                    + "Add a language > Japanese (tick Text-to-speech), then restart Minecraft."
                    : OS.contains("mac")
                    ? "No Japanese voice installed. System Settings > Accessibility > Spoken Content > "
                    + "System voice > Manage Voices > Japanese (Kyoko)."
                    : "No Japanese voice found. Install espeak-ng.";
            case UNSUPPORTED -> "Reading words aloud isn't supported on this system.";
            case CHECKING, UNKNOWN -> "Still checking for a Japanese voice…";
            case AVAILABLE -> "";
        };
    }

    /** Checks once in the background whether a Japanese voice exists. */
    public static synchronized void check() {
        if (status != Status.UNKNOWN) return;
        status = Status.CHECKING;
        Thread t = new Thread(() -> {
            try {
                status = probe();
            } catch (Throwable e) {
                status = Status.UNSUPPORTED;
            }
            NihongoQuest.LOGGER.info("Japanese voice: {}", status);
        }, "NihongoQuest-VoiceCheck");
        t.setDaemon(true);
        t.start();
    }

    private static Status probe() throws Exception {
        if (OS.contains("win")) {
            String script = "Add-Type -AssemblyName System.Speech;"
                    + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                    + "$v=$s.GetInstalledVoices()|Where-Object{$_.Enabled -and $_.VoiceInfo.Culture.Name -like 'ja*'}|Select-Object -First 1;"
                    + "if($v){Write-Output $v.VoiceInfo.Name; exit 0}else{exit 3}";
            ProcessResult r = run(powershell(script), 20);
            if (r.exit == 0) {
                NihongoQuest.LOGGER.info("Using Windows voice: {}", r.output.trim());
                return Status.AVAILABLE;
            }
            return r.exit == 3 ? Status.NO_JAPANESE_VOICE : Status.UNSUPPORTED;
        }
        if (OS.contains("mac")) {
            ProcessResult r = run(List.of("say", "-v", "?"), 10);
            return r.exit == 0 && r.output.contains("ja_JP") ? Status.AVAILABLE : Status.NO_JAPANESE_VOICE;
        }
        ProcessResult r = run(List.of("espeak-ng", "--version"), 10);
        return r.exit == 0 ? Status.AVAILABLE : Status.NO_JAPANESE_VOICE;
    }

    /** Reads the text aloud (stops anything already playing). Returns false if no voice is available. */
    public static synchronized boolean speak(String japanese) {
        if (status != Status.AVAILABLE || japanese == null || japanese.isBlank()) {
            if (status == Status.UNKNOWN) check();
            return false;
        }
        stop();
        int speed = ModConfig.get().voiceSpeed();
        List<String> cmd;
        if (OS.contains("win")) {
            String b64 = Base64.getEncoder().encodeToString(japanese.getBytes(StandardCharsets.UTF_8));
            String script = "Add-Type -AssemblyName System.Speech;"
                    + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                    + "$v=$s.GetInstalledVoices()|Where-Object{$_.Enabled -and $_.VoiceInfo.Culture.Name -like 'ja*'}|Select-Object -First 1;"
                    + "if(-not $v){exit 3};$s.SelectVoice($v.VoiceInfo.Name);$s.Rate=" + speed + ";"
                    + "$s.Speak([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + b64 + "')))";
            cmd = powershell(script);
        } else if (OS.contains("mac")) {
            cmd = List.of("say", "-v", "Kyoko", "-r", String.valueOf(175 + speed * 20), japanese);
        } else {
            cmd = List.of("espeak-ng", "-v", "ja", "-s", String.valueOf(160 + speed * 15), japanese);
        }

        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            current = p;
            SpeechService.muteForVoice(true);
            Thread waiter = new Thread(() -> {
                try {
                    p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                    p.waitFor(30, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                } finally {
                    p.destroy();
                    synchronized (VoiceService.class) {
                        if (current == p) current = null;
                    }
                    SpeechService.muteForVoice(false);
                }
            }, "NihongoQuest-Voice");
            waiter.setDaemon(true);
            waiter.start();
            return true;
        } catch (Exception e) {
            NihongoQuest.LOGGER.warn("Could not read the word aloud: {}", e.getMessage());
            SpeechService.muteForVoice(false);
            return false;
        }
    }

    public static synchronized void stop() {
        Process p = current;
        current = null;
        if (p != null) p.destroy();
    }

    public static boolean isSpeaking() {
        return current != null;
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> powershell(String script) {
        // -EncodedCommand avoids every quoting/encoding problem with Japanese text.
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
    }

    private record ProcessResult(int exit, String output) {}

    private static ProcessResult run(List<String> cmd, int timeoutSeconds) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new ProcessResult(-1, out.toString());
        }
        return new ProcessResult(p.exitValue(), out.toString());
    }
}
