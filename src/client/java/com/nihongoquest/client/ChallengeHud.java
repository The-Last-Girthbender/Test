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

    private static final int BG = 0xD0101018;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GOLD = 0xFFFFD040;
    private static final int AQUA = 0xFF66E0FF;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;

    private record Line(String text, int color, float scale) {}

    static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        ClientChallenge c = ClientChallenge.current();
        if (c == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden()) return;
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
        lines.add(new Line(big, GOLD, 2f));
        if (kanjiShown && kanaShown && w.hasSeparateKanji()) lines.add(new Line(w.kana(), WHITE, 1f));
        if ((cfg.showRomaji() || reveal) && w.romaji() != null && !w.romaji().isBlank()) {
            lines.add(new Line(w.romaji(), AQUA, 1f));
        }
        boolean guideShown = (cfg.showPronunciation() || reveal) && c.guide != null;
        if (guideShown) {
            lines.add(new Line("sounds like: " + c.guide, 0xFF88EE88, 1f));
        }
        if (cfg.showEnglish() || reveal || (!kanjiShown && !kanaShown)) {
            String eng = c.itemName.getString() + (c.amount > 1 ? "  ×" + c.amount : "");
            lines.add(new Line(eng, GRAY, 1f));
        }

        String status;
        int statusColor;
        switch (c.state) {
            case PASSED -> { status = "正解！ Correct!"; statusColor = GREEN; }
            case FAILED -> {
                status = c.removed > 0 ? "時間切れ… lost " + c.removed + "×" : "時間切れ… Time's up";
                statusColor = RED;
            }
            case WAITING -> { status = "…"; statusColor = GRAY; }
            default -> {
                String heard = c.heard == null ? "" : c.heard.trim();
                String secs = "  " + (c.ticksLeft + 19) / 20 + "s";
                if (cfg.showWhatWasHeard && !heard.isEmpty()) {
                    if (heard.length() > 24) heard = "…" + heard.substring(heard.length() - 24);
                    status = "聞こえた: " + heard + secs;
                } else {
                    status = "Listening… 話してください" + secs;
                }
                statusColor = WHITE;
            }
        }

        String header = "日本語で言って！  Say it in Japanese!";
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

        // ---- measure ----
        int pad = 6;
        int contentW = font.width(header) + 20;
        int contentH = 12;
        for (Line l : lines) {
            contentW = Math.max(contentW, (int) (font.width(l.text) * l.scale));
            contentH += (int) (10 * l.scale) + 2;
        }
        int statusW = font.width(status) + (listening ? MicIcon.WIDTH + 4 : 0);
        contentW = Math.max(contentW, statusW);
        contentH += 8 + (listening ? MicIcon.HEIGHT + 2 : 12); // timer bar + status row (with mic icon)
        if (breath != null) {
            contentW = Math.max(contentW, font.width(breath));
            contentH += 11;
        }
        int accBarW = 100;
        if (showAccuracy) {
            contentW = Math.max(contentW, font.width(accLabel + " 100%") + accBarW + 8);
            contentH += 13;
        }
        if (micWarning != null) {
            contentW = Math.max(contentW, font.width(micWarning));
            contentH += 11;
        }
        if (help != null) {
            contentW = Math.max(contentW, font.width(help));
            contentH += 11;
        }
        int panelW = Math.min(g.guiWidth() - 8, contentW + pad * 2);
        int panelH = contentH + pad * 2;
        int x0 = (g.guiWidth() - panelW) / 2;
        int y0 = cfg.hudYOffset;
        int cx = g.guiWidth() / 2;

        int border = switch (c.state) {
            case PASSED -> GREEN;
            case FAILED -> RED;
            default -> GOLD;
        };
        g.fill(x0, y0, x0 + panelW, y0 + panelH, BG);
        g.outline(x0, y0, panelW, panelH, border);

        // ---- header with item icon ----
        int y = y0 + pad;
        g.item(c.icon, x0 + pad, y - 3);
        g.text(font, header, x0 + pad + 20, y + 1, GRAY, true);
        y += 16;

        // ---- word lines ----
        for (Line l : lines) {
            if (l.scale != 1f) {
                g.pose().pushMatrix();
                g.pose().translate(cx, y);
                g.pose().scale(l.scale, l.scale);
                g.centeredText(font, l.text, 0, 0, l.color);
                g.pose().popMatrix();
            } else {
                g.centeredText(font, l.text, cx, y, l.color);
            }
            y += (int) (10 * l.scale) + 2;
        }

        // ---- timer bar ----
        y += 2;
        int barX0 = x0 + pad;
        int barX1 = x0 + panelW - pad;
        float frac = c.state == State.LISTENING ? (float) c.ticksLeft / c.totalTicks : 0f;
        if (c.state == State.PASSED) frac = 1f;
        int barColor = c.state == State.PASSED ? GREEN
                : frac > 0.5f ? 0xFF55DD55 : frac > 0.2f ? 0xFFFFCC33 : RED;
        g.fill(barX0, y, barX1, y + 4, 0xFF333340);
        g.fill(barX0, y, barX0 + (int) ((barX1 - barX0) * frac), y + 4, barColor);
        y += 8;

        // ---- status row: microphone icon + what was heard ----
        if (listening) {
            int rowX = cx - statusW / 2;
            MicIcon.draw(g, rowX, y, true, silent);
            g.text(font, status, rowX + MicIcon.WIDTH + 4, y + (MicIcon.HEIGHT - 8) / 2, statusColor, true);
            y += MicIcon.HEIGHT + 2;
        } else {
            g.centeredText(font, Component.literal(status), cx, y, statusColor);
            y += 11;
        }
        if (showAccuracy) {
            y = drawAccuracy(g, font, c, accLabel, shownPct, accBarW, cx, y);
        }
        if (breath != null) {
            g.centeredText(font, breath, cx, y, breathColor);
            y += 11;
        }
        if (micWarning != null) {
            g.centeredText(font, micWarning, cx, y, RED);
            y += 11;
        }
        if (help != null) {
            g.centeredText(font, help, cx, y, 0xFF8888AA);
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
        g.fill(bx - 1, barY - 1, bx + barW + 1, barY + 7, fresh ? 0xFF8888AA : 0xFF555566);
        g.fill(bx, barY, bx + barW, barY + 6, 0xFF222230);
        g.fill(bx, barY, bx + Math.max(1, barW * Math.min(100, pct) / 100), barY + 6, color);
        int tick = bx + barW * Math.min(100, needed) / 100;
        g.fill(tick, barY - 2, tick + 1, barY + 8, WHITE);
        g.text(font, pctText, bx + barW + 4, y + 1, color, true);
        return y + 13;
    }
}
