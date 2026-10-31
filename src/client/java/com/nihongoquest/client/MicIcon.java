package com.nihongoquest.client;

import com.nihongoquest.client.speech.SpeechService;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A pixel-art microphone with sound waves on both sides. The number of waves (0-3) follows
 * how loud the microphone is, so the player can see whether their input level is right:
 * <ul>
 *   <li>no waves while talking: mic too quiet / muted</li>
 *   <li>1-3 green waves: good</li>
 *   <li>red waves: too loud (the signal is clipping)</li>
 * </ul>
 */
final class MicIcon {
    private MicIcon() {}

    /** Size of the whole icon including waves, in GUI pixels (before scaling). */
    static final int WIDTH = 31;
    static final int HEIGHT = 20;

    private static final int MIC = 0xFFE8E8E8;
    private static final int GRILLE = 0xFF8C8C96;
    private static final int STAND = 0xFFB0B0B8;
    private static final int OFF = 0xFF66666E;
    private static final int GOOD = 0xFF55DD55;
    private static final int LOUD = 0xFFFFCC33;
    private static final int CLIP = 0xFFFF5555;

    private static float shown;     // smoothed level so the waves don't flicker
    private static long lastFrame;

    /** How many waves the current level earns (0-3). */
    static int waves(float level) {
        if (level < 0.04f) return 0;
        if (level < 0.15f) return 1;
        if (level < 0.35f) return 2;
        return 3;
    }

    /**
     * Draws the icon with its top-left corner at (x, y).
     *
     * @param active  listening right now (grey icon and no waves when false)
     * @param silent  the microphone has sent only silence (draws the mic red)
     */
    static void draw(GuiGraphicsExtractor g, int x, int y, boolean active, boolean silent) {
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.25f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float level = active ? SpeechService.inputLevel : 0f;
        // Rise instantly, fall over about a third of a second.
        shown = Math.max(level, shown * (float) Math.exp(-dt / 0.3f));

        boolean clipping = active && now - SpeechService.lastClipTime < 1200;
        int bodyColor = !active ? OFF : silent ? CLIP : MIC;
        int cx = x + WIDTH / 2;
        int top = y + 4;

        // ---- microphone head: a rounded capsule with grille lines ----
        g.fill(cx - 1, top, cx + 2, top + 1, bodyColor);
        g.fill(cx - 2, top + 1, cx + 3, top + 8, bodyColor);
        g.fill(cx - 1, top + 8, cx + 2, top + 9, bodyColor);
        if (active && !silent) {
            for (int row = 2; row <= 6; row += 2) g.fill(cx - 1, top + row, cx + 2, top + row + 1, GRILLE);
        }

        // ---- holder (U shape), stem and base ----
        int stand = active ? STAND : OFF;
        g.fill(cx - 4, top + 5, cx - 3, top + 9, stand);
        g.fill(cx + 4, top + 5, cx + 5, top + 9, stand);
        g.fill(cx - 3, top + 9, cx - 2, top + 10, stand);
        g.fill(cx + 3, top + 9, cx + 4, top + 10, stand);
        g.fill(cx - 2, top + 10, cx + 3, top + 11, stand);
        g.fill(cx, top + 11, cx + 1, top + 14, stand);
        g.fill(cx - 3, top + 14, cx + 4, top + 15, stand);

        if (!active) return;

        // ---- sound waves: ")))" on the right, "(((" on the left ----
        int count = waves(shown);
        int mid = top + 4;
        for (int k = 1; k <= count; k++) {
            int color = clipping ? CLIP : (k == 3 && shown > 0.8f) ? LOUD : GOOD;
            int half = 1 + 2 * k;                // waves get taller further out
            int right = cx + 5 + 3 * k;
            int left = cx - 5 - 3 * k;
            // straight middle part
            g.fill(right, mid - half + 2, right + 1, mid + half - 1, color);
            g.fill(left, mid - half + 2, left + 1, mid + half - 1, color);
            // two steps bending back toward the mic at each end, making the curve
            for (int dy : new int[]{-(half - 1), half - 1}) {
                g.fill(right - 1, mid + dy, right, mid + dy + 1, color);
                g.fill(left + 1, mid + dy, left + 2, mid + dy + 1, color);
            }
            for (int dy : new int[]{-half, half}) {
                g.fill(right - 2, mid + dy, right - 1, mid + dy + 1, color);
                g.fill(left + 2, mid + dy, left + 3, mid + dy + 1, color);
            }
        }
    }

    /** Draws the icon scaled up (e.g. 2x on the settings screen). */
    static void drawScaled(GuiGraphicsExtractor g, int x, int y, float scale, boolean active, boolean silent) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        draw(g, 0, 0, active, silent);
        g.pose().popMatrix();
    }

    /** A short note about the input level, or null if it looks fine. */
    static String levelNote(boolean active) {
        if (!active) return null;
        long now = System.currentTimeMillis();
        long since = SpeechService.listeningSince;
        if (since > 0 && now - since > 3000 && SpeechService.peakLevel < 0.01f) {
            return "No sound from your mic! Check its privacy settings.";
        }
        if (now - SpeechService.lastClipTime < 1200) {
            return "Too loud! Lower your mic volume or move back a little.";
        }
        if (since > 0 && now - since > 5000 && SpeechService.peakLevel < 0.10f) {
            return "Your mic is very quiet. Speak up or raise its volume.";
        }
        return null;
    }
}
