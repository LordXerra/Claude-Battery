package com.claudebattery;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Renders a battery-shaped system-tray icon containing two gradient progress bars:
 *   top half  → hourly usage
 *   bottom half → weekly usage
 *
 * Colour gradient: green (0 %) → yellow (50 %) → red (100 %)
 */
public final class BatteryIconRenderer {

    private static final int RENDER_SIZE = 64;

    // Brand colours
    private static final Color GREEN  = new Color(34,  197,  94);
    private static final Color YELLOW = new Color(234, 179,   8);
    private static final Color RED    = new Color(239,  68,  68);

    private BatteryIconRenderer() {}

    /** Returns an icon scaled to {@code size × size} pixels. */
    public static Image renderForSize(double hourlyPct, double weeklyPct, int size) {
        BufferedImage full = render(hourlyPct, weeklyPct);
        if (size == RENDER_SIZE) return full;

        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(full, 0, 0, size, size, null);
        g.dispose();
        return scaled;
    }

    private static BufferedImage render(double hourlyPct, double weeklyPct) {
        int S = RENDER_SIZE;
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);

        // Transparent background
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, S, S);

        // ── Battery body ─────────────────────────────────────────────
        int bx = 2, by = 8;
        int bw = S - 10, bh = S - 16;
        int nubW = 5, nubH = 10;
        int nubX = bx + bw;
        int nubY = by + (bh - nubH) / 2;

        // Nub
        g.setColor(new Color(180, 180, 180));
        g.fillRoundRect(nubX, nubY, nubW, nubH, 3, 3);

        // Body outline
        g.setColor(new Color(160, 160, 160));
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawRoundRect(bx, by, bw, bh, 6, 6);

        // ── Inner progress area ──────────────────────────────────────
        int pad = 5;
        int ix = bx + pad;
        int iy = by + pad;
        int iw = bw - 2 * pad;
        int ih = bh - 2 * pad;

        int halfH = (ih - 1) / 2;

        // Top bar: hourly
        drawBar(g, ix, iy, iw, halfH, hourlyPct);

        // Separator
        g.setColor(new Color(80, 80, 80, 120));
        g.fillRect(ix, iy + halfH, iw, 1);

        // Bottom bar: weekly
        drawBar(g, ix, iy + halfH + 1, iw, halfH, weeklyPct);

        // ── Labels ───────────────────────────────────────────────────
        g.setFont(new Font("SansSerif", Font.BOLD, 9));

        // Hourly label "H"
        g.setColor(labelColor(hourlyPct));
        g.drawString("H", ix + 2, iy + halfH - 2);

        // Weekly label "W"
        g.setColor(labelColor(weeklyPct));
        g.drawString("W", ix + 2, iy + halfH + 1 + halfH - 2);

        g.dispose();
        return img;
    }

    private static void drawBar(Graphics2D g, int x, int y, int w, int h, double pct) {
        // Background
        g.setColor(new Color(30, 30, 30, 210));
        g.fillRoundRect(x, y, w, h, 4, 4);

        int fillW = Math.max(0, (int) (w * Math.min(pct, 100.0) / 100.0));
        if (fillW > 0) {
            Color c = usageColor(pct);
            GradientPaint gp = new GradientPaint(
                    x,          y,     c.brighter(),
                    x + fillW,  y + h, c.darker()
            );
            g.setPaint(gp);
            g.fillRoundRect(x, y, fillW, h, 4, 4);

            // Subtle inner highlight
            g.setColor(new Color(255, 255, 255, 40));
            g.fillRoundRect(x, y, fillW, h / 2, 4, 4);
        }

        // Border
        g.setPaint(new Color(60, 60, 60, 140));
        g.setStroke(new BasicStroke(0.7f));
        g.drawRoundRect(x, y, w, h, 4, 4);
    }

    /** Gradient: green → yellow → red based on usage %. */
    static Color usageColor(double pct) {
        if (pct <= 50) {
            return lerp(GREEN, YELLOW, (float) (pct / 50.0));
        } else {
            return lerp(YELLOW, RED, (float) ((pct - 50) / 50.0));
        }
    }

    private static Color lerp(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(
                clamp((int) (a.getRed()   + (b.getRed()   - a.getRed())   * t)),
                clamp((int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t)),
                clamp((int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * t))
        );
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static Color labelColor(double pct) {
        if (pct > 80) return new Color(255, 220, 220, 210);
        return new Color(255, 255, 255, 180);
    }
}
