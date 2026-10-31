package com.nihongoquest.client;

import com.nihongoquest.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * "Move & resize panel": shows a sample quiz panel that can be dragged anywhere on screen
 * and resized with the mouse wheel or the buttons. Saved to config.json when closed.
 */
public class HudLayoutScreen extends Screen {
    private static final double STEP = 0.1;

    private final Screen parent;
    private final ClientChallenge sample = ClientChallenge.preview();
    private int[] panel = {0, 0, 0, 0};   // where the sample panel was drawn last frame
    private boolean dragging;
    private double grabX, grabY;          // where inside the panel the mouse grabbed it
    private Button smaller, bigger;

    public HudLayoutScreen(Screen parent) {
        super(Component.literal("Move & resize quiz panel"));
        this.parent = parent;
    }

    private ModConfig cfg() {
        return ModConfig.get();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 28;
        smaller = addRenderableWidget(Button.builder(Component.literal("Smaller"), b -> resize(-STEP))
                .bounds(cx - 154, y, 60, 20).build());
        bigger = addRenderableWidget(Button.builder(Component.literal("Bigger"), b -> resize(STEP))
                .bounds(cx - 90, y, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
            cfg().resetHudLayout();
            updateButtons();
        }).bounds(cx - 26, y, 52, 20).tooltip(Tooltip.create(Component.literal(
                "Normal size, top middle of the screen."))).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx + 30, y, 124, 20).build());
        updateButtons();
    }

    private void resize(double delta) {
        double s = Math.round((cfg().hudScale() + delta) * 10) / 10.0;
        cfg().hudScale = Math.max(ModConfig.MIN_HUD_SCALE, Math.min(ModConfig.MAX_HUD_SCALE, s));
        updateButtons();
    }

    private void updateButtons() {
        if (smaller == null) return;
        smaller.active = cfg().hudScale() > ModConfig.MIN_HUD_SCALE + 1e-6;
        bigger.active = cfg().hudScale() < ModConfig.MAX_HUD_SCALE - 1e-6;
    }

    // ------------------------------------------------------------------ mouse

    private boolean overPanel(double mx, double my) {
        return mx >= panel[0] && mx < panel[0] + panel[2] && my >= panel[1] && my < panel[1] + panel[3];
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true; // the buttons come first
        if (overPanel(event.x(), event.y())) {
            dragging = true;
            grabX = event.x() - panel[0];
            grabY = event.y() - panel[1];
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!dragging) return super.mouseDragged(event, dragX, dragY);
        moveTo(event.x() - grabX, event.y() - grabY);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            resize(scrollY > 0 ? STEP : -STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Turns the panel's new top-left corner into the saved 0-1 position. */
    private void moveTo(double left, double top) {
        int e = ChallengeHud.EDGE;
        double freeX = this.width - panel[2] - 2.0 * e;
        double freeY = this.height - panel[3] - 2.0 * e;
        cfg().hudPosX = freeX <= 0 ? 0.5 : clamp((left - e) / freeX);
        cfg().hudPosY = freeY <= 0 ? 0.0 : clamp((top - e) / freeY);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        panel = ChallengeHud.draw(g, sample);

        // Highlight the panel so it's clear it can be grabbed.
        boolean hover = dragging || overPanel(mouseX, mouseY);
        g.outline(panel[0] - 2, panel[1] - 2, panel[2] + 4, panel[3] + 4, hover ? 0xFFFFFFFF : 0x80FFFFFF);

        int cx = this.width / 2;
        String info = "Drag the panel to move it  ·  Scroll or use the buttons to resize";
        String size = "Size: " + Math.round(cfg().hudScale() * 100) + "%";
        // Keep the hint text away from the panel: show it at the bottom unless the panel is there.
        int hintY = panel[1] + panel[3] > this.height - 60 ? 6 : this.height - 50;
        g.centeredText(this.font, info, cx, hintY, 0xFFDDDDDD);
        g.centeredText(this.font, size, cx, hintY + 11, 0xFFFFD040);
    }

    @Override
    public void onClose() {
        ModConfig.save();
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
