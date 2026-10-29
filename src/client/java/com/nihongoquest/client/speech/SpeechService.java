package com.nihongoquest.client.speech;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nihongoquest.NihongoQuest;
import com.nihongoquest.config.ModConfig;
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

    private static final float SAMPLE_RATE = 16000f;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
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
                LibVosk.setLogLevel(LogLevel.WARNINGS); // also loads the native library
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

    public static void logMicrophones() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            List<String> names = new ArrayList<>();
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (AudioSystem.getMixer(mi).isLineSupported(info)) names.add(mi.getName());
            }
            NihongoQuest.LOGGER.info("Microphones found: {}", names.isEmpty() ? "(none)" : String.join(" | ", names));
        } catch (Throwable t) {
            NihongoQuest.LOGGER.warn("Could not list microphones: {}", t.getMessage());
        }
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
        stop();
        if (!isReady()) {
            onError.accept(unavailableReason());
            return;
        }
        Listener l = new Listener(model, grammarPhrases, matcher, onHeard, onMatch, onError);
        listener = l;
        l.start();
    }

    public static synchronized void stop() {
        Listener l = listener;
        listener = null;
        if (l != null) l.running = false;
    }

    private static TargetDataLine openMicrophone() throws Exception {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        String wanted = ModConfig.get().microphone == null ? "" : ModConfig.get().microphone.trim().toLowerCase(Locale.ROOT);
        if (!wanted.isEmpty()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(mi);
                if (mi.getName().toLowerCase(Locale.ROOT).contains(wanted) && mixer.isLineSupported(info)) {
                    Line line = mixer.getLine(info);
                    return (TargetDataLine) line;
                }
            }
            NihongoQuest.LOGGER.warn("Microphone matching \"{}\" not found, using the default one.", wanted);
        }
        return (TargetDataLine) AudioSystem.getLine(info);
    }

    private static final class Listener extends Thread {
        private final Model model;
        private final List<String> grammarPhrases;
        private final Predicate<String> matcher;
        private final Consumer<String> onHeard;
        private final Runnable onMatch;
        private final Consumer<String> onError;
        volatile boolean running = true;

        Listener(Model model, List<String> grammarPhrases, Predicate<String> matcher, Consumer<String> onHeard,
                 Runnable onMatch, Consumer<String> onError) {
            super("NihongoQuest-Microphone");
            setDaemon(true);
            this.model = model;
            this.grammarPhrases = grammarPhrases;
            this.matcher = matcher;
            this.onHeard = onHeard;
            this.onMatch = onMatch;
            this.onError = onError;
        }

        @Override
        public void run() {
            TargetDataLine mic = null;
            Recognizer free = null;
            Recognizer restricted = null;
            try {
                try {
                    mic = openMicrophone();
                    mic.open(FORMAT, 6400);
                    mic.start();
                } catch (Throwable t) {
                    onError.accept("Could not open the microphone (" + t.getMessage()
                            + "). Check it's plugged in and that Minecraft is allowed to use it.");
                    return;
                }

                free = new Recognizer(model, SAMPLE_RATE);
                try {
                    List<String> phrases = new ArrayList<>(grammarPhrases);
                    phrases.add("[unk]");
                    restricted = new Recognizer(model, SAMPLE_RATE, GSON.toJson(phrases));
                } catch (Throwable t) {
                    restricted = null; // some models don't support word lists; the free recognizer still works
                }

                StringBuilder freeDone = new StringBuilder();
                StringBuilder restrictedDone = new StringBuilder();
                byte[] buf = new byte[3200]; // 0.1 s of audio
                while (running) {
                    int n = mic.read(buf, 0, buf.length);
                    if (n <= 0) continue;

                    String heardFree = feed(free, buf, n, freeDone);
                    String heardRestricted = restricted != null ? feed(restricted, buf, n, restrictedDone) : "";
                    if (!running) break;

                    onHeard.accept(heardFree.isBlank() ? heardRestricted : heardFree);
                    if (matcher.test(heardFree) || matcher.test(heardRestricted)) {
                        running = false;
                        onMatch.run();
                        break;
                    }
                }
            } catch (Throwable t) {
                if (running) onError.accept("Speech recognition stopped: " + t.getMessage());
            } finally {
                if (mic != null) {
                    try { mic.stop(); mic.close(); } catch (Throwable ignored) {}
                }
                if (free != null) free.close();
                if (restricted != null) restricted.close();
            }
        }

        /** Feeds audio and returns everything heard so far in this quiz (final + partial). */
        private static String feed(Recognizer rec, byte[] buf, int n, StringBuilder done) {
            if (rec.acceptWaveForm(buf, n)) {
                String text = field(rec.getResult(), "text");
                if (!text.isBlank()) done.append(' ').append(text);
                return done.toString().trim();
            }
            String partial = field(rec.getPartialResult(), "partial");
            return (done + " " + partial).trim();
        }

        private static String field(String json, String key) {
            try {
                JsonElement el = JsonParser.parseString(json);
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    if (o.has(key)) return o.get(key).getAsString().replace("[unk]", "").trim();
                }
            } catch (Exception ignored) {}
            return "";
        }
    }
}
