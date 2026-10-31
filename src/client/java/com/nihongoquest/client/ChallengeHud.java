package com.nihongoquest.client;

import com.nihongoquest.client.ClientChallenge.State;
import com.nihongoquest.client.speech.SpeechService;
import com.nihongoquest.client.speech.VoiceService;
import com.nihongoquest.config.ModConfig;
import com.nihongoquest.vocab.Vocabulary.Word;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The quiz panel drawn at the top of the screen. */
final class ChallengeHud {
    private ChallengeHud() {}

    private static final int BODY = 0xF015151E;   // panel background (ink)
    private static final int TRACK = 0xFF2A2A3A;  // empty part of bars
    private static final int HEADER_H = 18;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFF9A9AAE;
    private static final int GOLD = 0xFFFFD040;
    private static final int AQUA = 0xFF66E0FF;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;

    private record Line(String text, int color, float scale, boolean pill) {
        Line(String text, int color, float scale) { this(text, color, scale, false); }
    }

    /** Space kept between the panel and the screen edges. */
    static final int EDGE = 4;

    static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        ClientChallenge c = ClientChallenge.current();
        if (c == null) return;
        if (Minecraft.getInstance().gui.hud.isHidden()) return;
        draw(g, c);
    }

    /**
     * Draws the quiz panel at the configured size and position.
     * @return where it ended up on screen: {x, y, width, height} (used by the layout editor)
     */
    static int[] draw(GuiGraphicsExtractor g, ClientChallenge c) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        ModConfig cfg = ModConfig.get();
        Word w = c.word;
        boolean reveal = c.state == State.PASSED || c.state == State.FAILED;

        // ---- which lines to show ----
        List<Line> lines = new ArrayList<>();
        boolean kanjiShown = (cfg.showKanji() || reveal) && w.kanji() != null;
        boolean kanaShown = (cfg.showKana() || reveal) && w.kana() != null;
        String big;
        if (kanjiShown) big = w.kanji();
        else if (kanaShown) big = w.kana();
        else big = "？？？";
        lines.add(new Line(big, 0xFFFFD45A, 2f));
        if (kanjiShown && kanaShown && w.hasSeparateKanji()) lines.add(new Line(w.kana(), WHITE, 1f));
        if ((cfg.showRomaji() || reveal) && w.romaji() != null && !w.romaji().isBlank()) {
            lines.add(new Line(w.romaji(), 0xFF7FD8FF, 1f));
        }
        boolean guideShown = (cfg.showPronunciation() || reveal) && c.guide != null;
        if (guideShown) {
            lines.add(new Line("sounds like  " + c.guide, 0xFFA8F5B5, 1f, true));
        }
        if (cfg.showEnglish() || reveal || (!kanjiShown && !kanaShown)) {
            String eng = c.itemName.getString() + (c.amount > 1 ? "  ×" + c.amount : "");
            lines.add(new Line(eng, 0xFF9A9AAE, 1f));
        }

        boolean listening = c.state == State.LISTENING;

        // How to say it: in one breath, only stopping where the guide shows a "."
        String breath = null;
        int breathColor = 0xFF9999BB;
        if (listening && c.splitWarning) {
            breath = "You paused mid-word! Say it in one breath.";
            breathColor = 0xFFFFAA44;
        } else if (listening && guideShown) {
            breath = c.guide.indexOf('.') >= 0
                    ? "Say it in one breath — only the \".\" is a tiny pause"
                    : "Say it in one breath — don't stop at the dashes";
        }

        // Small help line: the tricky-sound tip and the "hear it" key.
        String help = null;
        if (c.state == State.LISTENING || reveal) {
            StringBuilder h = new StringBuilder();
            if (cfg.showPronunciation() && c.tip != null) h.append(c.tip);
            String key = NihongoQuestClient.hearKeyName();
            if (key != null && VoiceService.status() != VoiceService.Status.NO_JAPANESE_VOICE
                    && VoiceService.status() != VoiceService.Status.UNSUPPORTED) {
                if (!h.isEmpty()) h.append("   ");
                h.append("[").append(key).append("] hear it");
            }
            if (!h.isEmpty()) help = h.toString();
        }

        // Accuracy of the latest attempt (or the best one, once the quiz is over).
        int shownPct = c.state == State.FAILED ? c.bestPct : c.lastPct;
        boolean showAccuracy = shownPct >= 0 && c.state != State.WAITING;
        String accLabel = c.state == State.FAILED ? "Best try" : c.state == State.PASSED ? "Accuracy" : "Last try";

        // Microphone level problems: silent, too loud, or very quiet.
        String micWarning = MicIcon.levelNote(listening);
        long since = SpeechService.listeningSince;
        boolean silent = listening && since > 0 && System.currentTimeMillis() - since > 3000
                && SpeechService.peakLevel < 0.01f;

        // ---- header title (the state) and the seconds left ----
        String title = switch (c.state) {
            case PASSED -> "正解！ Correct!";
            case FAILED -> c.removed > 0 ? "時間切れ… lost " + c.removed + "×" : "時間切れ… Time's up";
            case WAITING -> "…";
            default -> "日本語で言って！";
        };
        String secs = listening ? (c.ticksLeft + 19) / 20 + "s" : null;

        // ---- status row text (listening only): what the microphone heard ----
        String heardLabel = null, heardValue = null;
        if (listening) {
            String heard = c.heard == null ? "" : c.heard.trim();
            if (cfg.showWhatWasHeard && !heard.isEmpty()) {
                if (heard.length() > 24) heard = "…" + heard.substring(heard.length() - 24);
                heardLabel = "聞こえた  ";
                heardValue = heard;
            } else {
                heardLabel = "";
                heardValue = "話してください…";
            }
        }

        // ---- footer lines: how to say it, mic problems, tips ----
        List<String[]> footer = new ArrayList<>(); // {text, color}
        if (breath != null) footer.add(new String[]{breath, Integer.toString(breathColor)});
        if (micWarning != null) footer.add(new String[]{micWarning, Integer.toString(RED)});
        if (help != null) footer.add(new String[]{help, Integer.toString(0xFF7C7C98)});

        // ---- measure (at normal size; scaled afterwards) ----
        int pad = 8;
        int contentW = 22 + font.width(title) + (secs != null ? 12 + font.width(secs) : 0);
        int contentH = HEADER_H + 7;
        for (Line l : lines) {
            int lineW = (int) (font.width(l.text) * l.scale) + (l.pill ? 12 : 0);
            contentW = Math.max(contentW, lineW);
            contentH += l.pill ? 14 : (int) (10 * l.scale) + 2;
        }
        contentH += 4 + 6; // timer bar + gap
        int statusW = 0;
        if (listening) {
            statusW = MicIcon.WIDTH + 4 + font.width(heardLabel) + font.width(heardValue);
            contentW = Math.max(contentW, statusW);
            contentH += MicIcon.HEIGHT + 2;
        }
        int accBarW = 100;
        if (showAccuracy) {
            contentW = Math.max(contentW, font.width(accLabel + " 100%") + accBarW + 8);
            contentH += 14;
        }
        contentH += 2;
        int footerH = footer.isEmpty() ? 0 : footer.size() * 10 + 6;
        for (String[] f : footer) contentW = Math.max(contentW, font.width(f[0]));
        contentH += footerH;

        // ---- size and position (the panel is laid out at normal size, then scaled) ----
        float scale = (float) cfg.hudScale();
        int panelW = Math.max(160, Math.min((int) ((g.guiWidth() - 2 * EDGE) / scale), contentW + pad * 2));
        int panelH = contentH;
        int screenW = Math.round(panelW * scale);
        int screenH = Math.round(panelH * scale);
        int freeX = Math.max(0, g.guiWidth() - screenW - 2 * EDGE);
        int freeY = Math.max(0, g.guiHeight() - screenH - 2 * EDGE);
        int screenX = EDGE + (int) Math.round(freeX * cfg.hudPosX());
        int screenY = EDGE + (int) Math.round(freeY * cfg.hudPosY(freeY));

        // Slide in gently when a quiz appears.
        float t = Math.min(1f, (System.currentTimeMillis() - c.shownAt) / 220f);
        float slide = (1f - t) * (1f - t) * -8f;

        g.pose().pushMatrix();
        g.pose().translate(screenX, screenY + slide * scale);
        g.pose().scale(scale, scale);
        int x0 = 0;
        int y0 = 0;
        int x1 = panelW;
        int y1 = panelH;
        int cx = panelW / 2;

        // ---- colors for the current state ----
        int bandTop, bandBottom, edge;
        switch (c.state) {
            case PASSED -> { bandTop = 0xFF3FBF5F; bandBottom = 0xFF23803A; edge = 0xFF55DD77; }
            case FAILED -> { bandTop = 0xFFC8363A; bandBottom = 0xFF7E1F22; edge = 0xFFE04A4A; }
            default -> { bandTop = 0xFFD9483B; bandBottom = 0xFF9E2C24; edge = 0xFFE0604F; }
        }
        float frac = c.state == State.LISTENING ? (float) c.ticksLeft / c.totalTicks : 0f;
        if (c.state == State.PASSED) frac = 1f;
        // Last 20% of the time: the border pulses to show time is running out.
        if (listening && frac < 0.2f && (System.currentTimeMillis() / 300) % 2 == 0) edge = 0xFFFF8070;

        // ---- shadow, border, body ----
        roundRect(g, x0 + 2, y0 + 2, x1 + 2, y1 + 2, 0x55000000);
        roundRect(g, x0, y0, x1, y1, edge);
        roundRect(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, BODY);

        // ---- header band: item icon, title, seconds left ----
        g.fillGradient(x0 + 1, y0 + 1, x1 - 1, y0 + HEADER_H, bandTop, bandBottom);
        g.fill(x0 + 1, y0 + HEADER_H, x1 - 1, y0 + HEADER_H + 1, 0x40000000);
        if (c.icon != null) g.item(c.icon, x0 + 4, y0 + 2);
        g.text(font, title, x0 + 22, y0 + 6, WHITE, true);
        if (secs != null) g.text(font, secs, x1 - pad - font.width(secs), y0 + 6, WHITE, true);

        // ---- the word ----
        int y = y0 + HEADER_H + 7;
        for (Line l : lines) {
            if (l.pill) {
                int pw = font.width(l.text) + 12;
                roundRect(g, cx - pw / 2, y - 2, cx - pw / 2 + pw, y + 10, 0x3355DD77);
                g.centeredText(font, l.text, cx, y, l.color);
                y += 14;
            } else if (l.scale != 1f) {
                g.pose().pushMatrix();
                g.pose().translate(cx, y);
                g.pose().scale(l.scale, l.scale);
                g.centeredText(font, l.text, 0, 0, l.color);
                g.pose().popMatrix();
                y += (int) (10 * l.scale) + 2;
            } else {
                g.centeredText(font, l.text, cx, y, l.color);
                y += 12;
            }
        }

        // ---- timer bar ----
        int barColor = c.state == State.PASSED ? GREEN
                : frac > 0.5f ? 0xFF55DD55 : frac > 0.2f ? 0xFFFFCC33 : RED;
        bar(g, x0 + pad, y, x1 - pad, 4, frac, barColor);
        y += 10;

        // ---- status row: microphone icon + what was heard ----
        if (listening) {
            int rowX = cx - statusW / 2;
            MicIcon.draw(g, rowX, y, true, silent);
            int ty = y + (MicIcon.HEIGHT - 8) / 2;
            g.text(font, heardLabel, rowX + MicIcon.WIDTH + 4, ty, GRAY, true);
            g.text(font, heardValue, rowX + MicIcon.WIDTH + 4 + font.width(heardLabel), ty, WHITE, true);
            y += MicIcon.HEIGHT + 2;
        }
        if (showAccuracy) {
            y = drawAccuracy(g, font, c, accLabel, shownPct, accBarW, cx, y);
        }

        // ---- footer strip ----
        if (!footer.isEmpty()) {
            int fy0 = y1 - 1 - footerH;
            g.fill(x0 + 1, fy0, x1 - 1, y1 - 1, 0x40000000);
            g.fill(x0 + 1, fy0, x1 - 1, fy0 + 1, 0x20FFFFFF);
            int fy = fy0 + 4;
            for (String[] f : footer) {
                g.centeredText(font, f[0], cx, fy, Integer.parseInt(f[1]));
                fy += 10;
            }
        }
        g.pose().popMatrix();
        return new int[]{screenX, screenY, screenW, screenH};
    }

    /** A rectangle with its four corner pixels cut off, so it looks rounded. */
    static void roundRect(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0 + 1, y0, x1 - 1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    /** A rounded progress bar with a small highlight along its top edge. */
    static void bar(GuiGraphicsExtractor g, int x0, int y, int x1, int h, float frac, int color) {
        roundRect(g, x0, y, x1, y + h, TRACK);
        int w = (int) ((x1 - x0) * Math.max(0f, Math.min(1f, frac)));
        if (w > 1) {
            roundRect(g, x0, y, x0 + w, y + h, color);
            g.fill(x0 + 1, y, x0 + w - 1, y + 1, 0x50FFFFFF);
        }
    }

    /**
     * "Last try [######|---] 72%": a bar filled to the score, with a white tick at the
     * Match Needed level. Green = enough to pass, yellow = close, red = far off.
     */
    private static int drawAccuracy(GuiGraphicsExtractor g, Font font, ClientChallenge c, String label,
                                    int pct, int barW, int cx, int y) {
        int needed = c.neededPct;
        int color = pct >= needed ? GREEN : pct >= needed - 20 ? 0xFFFFCC33 : RED;
        String pctText = pct + "%";
        String labelText = label + " ";
        int totalW = font.width(labelText) + barW + 4 + font.width("100%");
        int x = cx - totalW / 2;

        g.text(font, labelText, x, y + 1, GRAY, true);
        int bx = x + font.width(labelText);
        int barY = y + 2;
        // A just-finished attempt flashes brighter for a moment.
        boolean fresh = System.currentTimeMillis() - c.lastPctTime < 600 && c.state == State.LISTENING;
        if (fresh) roundRect(g, bx - 1, barY - 1, bx + barW + 1, barY + 7, 0x60FFFFFF);
        bar(g, bx, barY, bx + barW, 6, Math.min(100, pct) / 100f, color);
        int tick = bx + barW * Math.min(100, needed) / 100;
        g.fill(tick, barY - 2, tick + 1, barY + 8, WHITE);
        g.text(font, pctText, bx + barW + 4, y + 1, color, true);
        return y + 14;
    }
}
