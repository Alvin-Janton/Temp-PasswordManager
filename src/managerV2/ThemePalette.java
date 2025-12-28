package managerV2;

import javax.swing.*;
import java.awt.*;

/**
 * ThemePalette
 * -------------
 * Centralized theme accessor that adapts colors/fonts to the current Look & Feel (FlatLaf).
 * Provides a small, immutable set of UI colors (base, accent, hover, selected, etc.)
 * derived from the current Swing {@link UIManager} values.
 *
 * Usage:
 *   ThemePalette p = ThemePalette.current();
 *   panel.setBackground(p.baseBg);
 *
 * Notes:
 * - Keeps colors theme-aware (auto-updates when the user switches dark/light modes).
 * - “Accent” is typically the focus color (e.g., blue highlight in FlatLaf).
 */
public final class ThemePalette {

    public final Color baseBg;        // background of main panels
    public final Color accent;        // primary accent (focus or selection blue)
    public final Color hoverBg;       // background color for hover states
    public final Color selectedBg;    // background color for selected states
    public final Color divider;       // thin dividing lines (slightly darker)
    public final Font  baseLabelFont; // default label font from UI
    public final Color buttonBase;    // base button background (slightly lighter)
    public final Color buttonOutline; // subtle outline around buttons

    private ThemePalette(Color baseBg, Color accent, Color hoverBg, Color selectedBg,
                         Color divider, Font baseLabelFont, Color buttonBase, Color buttonOutline) {
        this.baseBg = baseBg;
        this.accent = accent;
        this.hoverBg = hoverBg;
        this.selectedBg = selectedBg;
        this.divider = divider;
        this.baseLabelFont = baseLabelFont;
        this.buttonBase = buttonBase;
        this.buttonOutline = buttonOutline;
    }

    /**
     * Builds a palette reflecting the active Swing Look & Feel colors.
     * - Pulls base background and focus (accent) colors from UIManager.
     * - Derives hover/selected shades by blending base+accent.
     * - Calculates lighter button color using HSB brightness math.
     */
    public static ThemePalette current() {
        Color base = UIManager.getColor("Panel.background");
        if (base == null) base = UIManager.getColor("control"); // fallback = default gray background
        if (base == null) base = Color.GRAY; // final fallback

        Color focus = UIManager.getColor("Component.focusColor");
        if (focus == null) focus = new Color(0x3D7FFF); // a medium blue (hex RGB 3D7FFF)

        // Slightly mix base and accent for hover/selection colors
        Color hover = blend(base, focus, 0.15f);     // 15% of accent color mixed in
        Color selected = blend(base, focus, 0.25f);  // 25% accent → stronger contrast
        Color divider = base.darker();               // divider is just a darker base tone

        
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null); // Convert RGB to HSB (Hue, Saturation, Brightness) for easy brightness tweaking.
        float minDelta = 0.10f; // +10% brightness to make buttons pop slightly
        float targetB  = Math.min(0.98f, hsb[2] + minDelta); // cap brightness so it doesn't go pure white
        Color buttonBase = Color.getHSBColor(hsb[0], hsb[1], targetB); // same hue/saturation, slightly brighter

        Color buttonOutline = blend(base, Color.BLACK, 0.10f); // Outline: 10% blend between base color and black → subtle edge on light UIs.

        Font labelFont = UIManager.getFont("Label.font");
        if (labelFont == null) labelFont = new JLabel().getFont(); // fallback if LAF doesn't provide one

        return new ThemePalette(base, focus, hover, selected, divider, labelFont, buttonBase, buttonOutline);
    }

    /** Blend two colors (a + b) using ratio 0–1. Higher ratio → closer to b. */
    private static Color blend(Color a, Color b, float ratio) {
        float r = 1f - ratio;
        return new Color(
            Math.round(a.getRed()   * r + b.getRed()   * ratio),
            Math.round(a.getGreen() * r + b.getGreen() * ratio),
            Math.round(a.getBlue()  * r + b.getBlue()  * ratio)
        );
    }
    
    /** Unused helper for future UI effects — could lighten/darken colors dynamically. */
    private static Color lightenOrDarken(Color c, float factor) { // currently unused
        // positive factor → lighten, negative factor → darken
        int r = Math.min(255, (int)(c.getRed()   + (255 - c.getRed())   * factor));
        int g = Math.min(255, (int)(c.getGreen() + (255 - c.getGreen()) * factor));
        int b = Math.min(255, (int)(c.getBlue()  + (255 - c.getBlue())  * factor));
        return new Color(r, g, b);
    }
}
