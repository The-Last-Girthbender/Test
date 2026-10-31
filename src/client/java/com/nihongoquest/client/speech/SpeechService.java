package com.nihongoquest.client.speech;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nihongoquest.NihongoQuest;
import com.nihongoquest.config.ModConfig;
import com.nihongoquest.vocab.Kana;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Offline Japanese speech recognition with Vosk.
 *
 * The model loads once in the background. During a quiz a listener thread reads the
 * microphone and runs two recognizers on the same audio: a free one (understands any
 * Japanese) and one restricted to the expected word (much better at short words). The
 * quiz passes as soon as either one hears the word.
 */
public final class SpeechService {
    private SpeechService() {}

    public enum Status { IDLE, DOWNLOADING, LOADING, READY, MISSING, ERROR }

    private static final Gson GSON = new Gson();

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NihongoQuest-SpeechLoader");
        t.setDaemon(true);
        return t;
    });

    private static volatile Status status = Status.IDLE;
    private static volatile String detail = "";
    private static volatile Model model;
    private static volatile Listener listener;

    public static Status status() {
        return status;
    }

    public static boolean isReady() {
        return status == Status.READY && model != null;
    }

    /** Human-readable reason why quizzes can't listen right now. */
    public static String unavailableReason() {
        return switch (status) {
            case DOWNLOADING -> "The Japanese speech model is still downloading (" + detail + ").";
            case LOADING, IDLE -> "The Japanese speech model is still loading.";
            case MISSING -> "The Japanese speech model is missing. " + detail;
            case ERROR -> "Speech recognition failed to start: " + detail;
            case READY -> "";
        };
    }

    public static Path modelDir() {
        return NihongoQuest.configDir().resolve(ModConfig.get().speechModelFolder);
    }

    private static boolean looksLikeModel(Path dir) {
        return Files.isDirectory(dir.resolve("am")) || Files.isRegularFile(dir.resolve("conf").resolve("model.conf"));
    }

    /** Starts downloading/loading the model in the background if needed. Safe to call repeatedly. */
    public static synchronized void prepare() {
        if (status == Status.READY || status == Status.DOWNLOADING || status == Status.LOADING) return;
        status = Status.LOADING;
        LOADER.submit(() -> {
            try {
                // The speech library talks UTF-8, but JNA converts text with the system encoding
                // (e.g. Windows-1252), which garbles Japanese in both directions. Make JNA use
                // UTF-8 while the library registers its functions, then put the setting back.
                String previous = System.getProperty("jna.encoding");
                System.setProperty("jna.encoding", "UTF-8");
                try {
                    LibVosk.setLogLevel(LogLevel.WARNINGS); // also loads the native library
                } finally {
                    if (previous == null) System.clearProperty("jna.encoding");
                    else System.setProperty("jna.encoding", previous);
                }
                logTextEncoding();
            } catch (Throwable t) {
                fail(Status.ERROR, "native speech library could not load on this system (" + t.getMessage() + ")");
                return;
            }
            ModConfig cfg = ModConfig.get();
            Path dir = modelDir();
            if (!looksLikeModel(dir)) {
                if (!cfg.autoDownloadSpeechModel) {
                    fail(Status.MISSING, "Download it from " + cfg.speechModelUrl + " and unzip it into " + dir.getParent());
                    return;
                }
                try {
                    status = Status.DOWNLOADING;
                    detail = "starting";
                    NihongoQuest.LOGGER.info("Downloading Japanese speech model from {}", cfg.speechModelUrl);
                    ModelDownloader.downloadAndUnzip(cfg.speechModelUrl, dir.getParent(), p -> detail = p);
                } catch (Exception e) {
                    fail(Status.MISSING, "Automatic download failed (" + e.getMessage() + "). Download it from "
                            + cfg.speechModelUrl + " and unzip it into " + dir.getParent());
                    return;
                }
                if (!looksLikeModel(dir)) {
                    fail(Status.MISSING, "Downloaded, but no model found in " + dir
                            + ". Check \"speechModelFolder\" in config.json matches the unzipped folder name.");
                    return;
                }
            }
            try {
                status = Status.LOADING;
                model = new Model(dir.toAbsolutePath().toString());
                status = Status.READY;
                detail = "";
                NihongoQuest.LOGGER.info("Japanese speech model ready.");
            } catch (Throwable t) {
                fail(Status.ERROR, "model in " + dir + " could not be loaded (" + t.getMessage() + ")");
            }
        });
    }

    /** Logs which text encoding JNA uses for the speech library (should be UTF-8). */
    private static void logTextEncoding() {
        try {
            Object enc = Class.forName("com.sun.jna.Native")
                    .getMethod("getStringEncoding", Class.class).invoke(null, LibVosk.class);
            NihongoQuest.LOGGER.info("Speech library text encoding: {}", enc);
        } catch (Throwable t) {
            NihongoQuest.LOGGER.info("Speech library text encoding: unknown ({})", t.getMessage());
        }
    }

    private static void fail(Status s, String why) {
        status = s;
        detail = why;
        NihongoQuest.LOGGER.warn("Speech recognition unavailable: {}", why);
    }

    /** Lets a failed setup be retried (e.g. after editing config and rejoining). */
    public static synchronized void retryIfFailed() {
        if (status == Status.MISSING || status == Status.ERROR) {
            status = Status.IDLE;
            prepare();
        }
    }

    /** Names of all audio inputs that can record in the format we need. */
    public static List<String> microphoneNames() {
        List<String> names = new ArrayList<>();
        try {
            Line.Info info = new Line.Info(TargetDataLine.class);
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (AudioSystem.getMixer(mi).isLineSupported(info) && !names.contains(mi.getName())) {
                    names.add(mi.getName());
                }
            }
        } catch (Throwable t) {
            NihongoQuest.LOGGER.warn("Could not list microphones: {}", t.getMessage());
        }
        return names;
    }

    public static void logMicrophones() {
        List<String> names = microphoneNames();
        NihongoQuest.LOGGER.info("Microphones found: {}", names.isEmpty() ? "(none)" : String.join(" | ", names));
    }

    // ------------------------------------------------------------------ listening

    /**
     * Starts listening. Callbacks run on the listener thread; hop to the game thread yourself.
     *
     * @param grammarPhrases spellings to bias the restricted recognizer toward
     * @param matcher        returns true when the heard text counts as correct
     * @param onHeard        latest text heard (for display)
     * @param onMatch        called once when the word is recognised
     * @param onError        called if the microphone can't be opened
     */
    public static synchronized void listen(List<String> grammarPhrases, Predicate<String> matcher,
                                           Consumer<String> onHeard, Runnable onMatch, Consumer<String> onError) {
        listen(grammarPhrases, matcher, matcher, onHeard, onMatch, onError);
    }

    /**
     * @param grammarPhrases the target's spellings plus decoy words; the word-list recognizer must pick one
     * @param exactMatcher   judges the word-list recognizer's pick (should be an exact check)
     * @param matcher        judges what the open recognizer heard (may be fuzzy)
     * Only complete utterances (after a pause) are judged, never half-finished guesses.
     */
    public static synchronized void listen(List<String> grammarPhrases, Predicate<String> exactMatcher,
                                           Predicate<String> matcher, Consumer<String> onHeard,
                                           Runnable onMatch, Consumer<String> onError) {
        stop();
        if (!isReady()) {
            onError.accept(unavailableReason());
            return;
        }
        Listener l = new Listener(model, grammarPhrases, exactMatcher, matcher, onHeard, onMatch, onError);
        listener = l;
        l.start();
    }

    public static synchronized void stop() {
        Listener l = listener;
        listener = null;
        if (l != null) l.running = false;
    }

    /** Time's up: stop listening, but still judge whatever was being said at that moment. */
    public static synchronized void finish() {
        Listener l = listener;
        listener = null;
        if (l != null) {
            l.finishing = true;
            l.running = false;
        }
    }

    // ------------------------------------------------------------------ microphone

    /** What the listener is hearing right now, for the HUD and the mic test. */
    public static volatile float inputLevel;     // 0..1, loudness of the last 0.1 s
    public static volatile float peakLevel;      // loudest level since listening started
    public static volatile long listeningSince;  // System.currentTimeMillis() when the mic opened, 0 if not open
    public static volatile String openedMic = "";

    // While the computer reads a word aloud, audio is thrown away so the quiz can't "hear" it.
    private static volatile boolean voiceActive;
    private static volatile long mutedUntil;

    public static void muteForVoice(boolean speaking) {
        voiceActive = speaking;
        if (!speaking) mutedUntil = System.currentTimeMillis() + 500; // let the echo die down
    }

    private static boolean muted() {
        return voiceActive || System.currentTimeMillis() < mutedUntil;
    }

    /** A microphone that's open and recording, plus the format it agreed to. */
    private record OpenMic(TargetDataLine line, AudioFormat format, String name) {}

    /** Formats to try, best first. Vosk accepts any sample rate; stereo is mixed down to mono. */
    private static final float[][] CANDIDATES = {
            {16000, 1}, {48000, 1}, {44100, 1}, {32000, 1}, {22050, 1},
            {48000, 2}, {44100, 2}, {16000, 2}, {32000, 2}, {22050, 2}
    };

    private static OpenMic openMicrophone() throws Exception {
        String wanted = ModConfig.get().microphone == null ? "" : ModConfig.get().microphone.trim().toLowerCase(Locale.ROOT);
        Exception last = null;
        if (!wanted.isEmpty()) {
            boolean found = false;
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (!mi.getName().toLowerCase(Locale.ROOT).contains(wanted)) continue;
                Mixer mixer = AudioSystem.getMixer(mi);
                if (!mixer.isLineSupported(new Line.Info(TargetDataLine.class))) continue;
                found = true;
                for (float[] c : CANDIDATES) {
                    AudioFormat f = new AudioFormat(c[0], 16, (int) c[1], true, false);
                    DataLine.Info info = new DataLine.Info(TargetDataLine.class, f);
                    if (!mixer.isLineSupported(info)) continue;
                    try {
                        TargetDataLine line = (TargetDataLine) mixer.getLine(info);
                        line.open(f, bufferBytes(f) * 2);
                        return new OpenMic(line, f, mi.getName());
                    } catch (Exception e) {
                        last = e;
                    }
                }
            }
            NihongoQuest.LOGGER.warn(found
                    ? "Microphone \"{}\" was found but couldn't be opened, trying the default one."
                    : "Microphone matching \"{}\" not found, using the default one.", wanted);
        }
        for (float[] c : CANDIDATES) {
            AudioFormat f = new AudioFormat(c[0], 16, (int) c[1], true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, f);
            if (!AudioSystem.isLineSupported(info)) continue;
            try {
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(f, bufferBytes(f) * 2);
                return new OpenMic(line, f, "system default");
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("no microphone that can record was found");
    }

    /** Bytes in 0.1 s of audio for this format. */
    private static int bufferBytes(AudioFormat f) {
        return Math.max(f.getFrameSize(), (int) (f.getSampleRate() / 10) * f.getFrameSize());
    }

    /**
     * Turns raw 16-bit audio into mono 16-bit little-endian (what Vosk wants) and
     * returns the number of bytes written to {@code out}. Also updates the level meter.
     */
    private static int toMono(byte[] in, int n, int channels, byte[] out) {
        int frames = n / (2 * channels);
        double sumSq = 0;
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int ch = 0; ch < channels; ch++) {
                int idx = (i * channels + ch) * 2;
                sum += (short) ((in[idx] & 0xFF) | (in[idx + 1] << 8));
            }
            int sample = sum / channels;
            out[i * 2] = (byte) sample;
            out[i * 2 + 1] = (byte) (sample >> 8);
            double norm = sample / 32768.0;
            sumSq += norm * norm;
        }
        float rms = frames == 0 ? 0f : (float) Math.sqrt(sumSq / frames);
        float level = Math.min(1f, rms * 4f); // normal speech lands around 0.2-0.8
        inputLevel = level;
        if (level > peakLevel) peakLevel = level;
        return frames * 2;
    }

    /** Listens without a target word: for the "Test microphone" button. */
    public static void listenTest(Consumer<String> onHeard, Consumer<String> onError) {
        listen(List.of(), text -> false, onHeard, () -> {}, onError);
    }

    public static boolean isListening() {
        return listener != null;
    }

    private static final class Listener extends Thread {
        private final Model model;
        private final List<String> grammarPhrases;
        private final Predicate<String> exactMatcher;
        private final Predicate<String> matcher;
        private final Consumer<String> onHeard;
        private final Runnable onMatch;
        private final Consumer<String> onError;
        volatile boolean running = true;
        volatile boolean finishing = false;

        Listener(Model model, List<String> grammarPhrases, Predicate<String> exactMatcher, Predicate<String> matcher,
                 Consumer<String> onHeard, Runnable onMatch, Consumer<String> onError) {
            super("NihongoQuest-Microphone");
            setDaemon(true);
            this.model = model;
            this.grammarPhrases = grammarPhrases;
            this.exactMatcher = exactMatcher;
            this.matcher = matcher;
            this.onHeard = onHeard;
            this.onMatch = onMatch;
            this.onError = onError;
        }

        @Override
        public void run() {
            OpenMic mic = null;
            Recognizer free = null;
            Recognizer restricted = null;
            inputLevel = 0f;
            peakLevel = 0f;
            try {
                try {
                    mic = openMicrophone();
                    mic.line().start();
                } catch (Throwable t) {
                    onError.accept("Could not open the microphone (" + t.getMessage()
                            + "). Check it's plugged in and that Minecraft is allowed to use it.");
                    return;
                }
                AudioFormat f = mic.format();
                if (!mic.name().equals(openedMic)) {
                    NihongoQuest.LOGGER.info("Listening on microphone: {} ({} Hz, {} channel(s))",
                            mic.name(), (int) f.getSampleRate(), f.getChannels());
                }
                openedMic = mic.name();
                listeningSince = System.currentTimeMillis();

                free = new Recognizer(model, f.getSampleRate());
                if (!grammarPhrases.isEmpty()) {
                    try {
                        List<String> phrases = new ArrayList<>(grammarPhrases);
                        phrases.add("[unk]");
                        restricted = new Recognizer(model, f.getSampleRate(), GSON.toJson(phrases));
                    } catch (Throwable t) {
                        restricted = null; // some models don't support word lists; the open recognizer still works
                    }
                }

                String lastUtterance = "";
                byte[] raw = new byte[bufferBytes(f)];
                byte[] mono = new byte[raw.length];
                boolean wasMuted = false;
                while (running) {
                    int n = mic.line().read(raw, 0, raw.length);
                    if (n <= 0) continue;
                    int m = toMono(raw, n, f.getChannels(), mono);

                    if (muted()) {
                        wasMuted = true;
                        continue;
                    }
                    if (wasMuted) {
                        // Forget anything heard while the voice was playing.
                        wasMuted = false;
                        free.reset();
                        if (restricted != null) restricted.reset();
                        lastUtterance = "";
                        onHeard.accept("");
                    }

                    // Each recognizer says "done" when you pause after speaking; only then do we judge.
                    String freeFinal = free.acceptWaveForm(mono, m) ? field(free.getResult(), "text") : null;
                    String listFinal = restricted != null && restricted.acceptWaveForm(mono, m)
                            ? field(restricted.getResult(), "text") : null;
                    if (!running) break;

                    if (freeFinal != null && !freeFinal.isBlank()) lastUtterance = freeFinal;
                    String partial = freeFinal != null ? "" : field(free.getPartialResult(), "partial");
                    onHeard.accept(partial.isBlank() ? lastUtterance : partial);

                    if (judge(freeFinal, listFinal)) return;
                }

                // Time ran out mid-word: judge what was being said at that moment.
                if (finishing) {
                    String freeFinal = field(free.getFinalResult(), "text");
                    String listFinal = restricted != null ? field(restricted.getFinalResult(), "text") : null;
                    if (!freeFinal.isBlank()) onHeard.accept(freeFinal);
                    judge(freeFinal, listFinal);
                }
            } catch (Throwable t) {
                if (running) onError.accept("Speech recognition stopped: " + t.getMessage());
            } finally {
                listeningSince = 0;
                inputLevel = 0f;
                if (mic != null) {
                    try { mic.line().stop(); mic.line().close(); } catch (Throwable ignored) {}
                }
                if (free != null) free.close();
                if (restricted != null) restricted.close();
            }
        }

        /** True (and reports the match) if either finished utterance counts as the word. */
        private boolean judge(String freeFinal, String listFinal) {
            boolean ok = (listFinal != null && !listFinal.isBlank() && exactMatcher.test(listFinal))
                    || (freeFinal != null && !freeFinal.isBlank() && matcher.test(freeFinal));
            if (ok) {
                running = false;
                onMatch.run();
            }
            return ok;
        }

        private static String field(String json, String key) {
            try {
                JsonElement el = JsonParser.parseString(json);
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    if (o.has(key)) {
                        return Kana.repairMojibake(o.get(key).getAsString()).replace("[unk]", "").trim();
                    }
                }
            } catch (Exception ignored) {}
            return "";
        }
    }
}
