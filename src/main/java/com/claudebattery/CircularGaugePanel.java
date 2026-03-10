package com.claudebattery;

import javax.swing.*;
import java.awt.*;

/**
 * A circular arc gauge that fills from green → yellow → red as usage increases.
 */
public class CircularGaugePanel extends JPanel {

    private double percent;
    private String label = "";

    public CircularGaugePanel(double percent) {
        this.percent = percent;
        setPreferredSize(new Dimension(130, 130));
        setOpaque(false);
    }

    public void setPercent(double percent) {
        this.percent = percent;
        repaint();
    }

    public void setLabel(String label) {
        this.label = label;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);

        int w  = getWidth();
        int h  = getHeight();
        int sz = Math.min(w, h) - 14;
        int x  = (w - sz) / 2;
        int y  = (h - sz) / 2;
        int strokeW = Math.max(8, sz / 10);

        // ── Track (background arc) ─────────────────────────────────
        g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(220, 220, 220));
        g.drawArc(x, y, sz, sz, 225, -270);

        // ── Fill arc ───────────────────────────────────────────────
        int sweep = -(int) (270.0 * Math.min(percent, 100.0) / 100.0);
        if (sweep != 0) {
            g.setColor(BatteryIconRenderer.usageColor(percent));
            g.drawArc(x, y, sz, sz, 225, sweep);
        }

        // ── Centre text ────────────────────────────────────────────
        Color textColor = BatteryIconRenderer.usageColor(percent);
        g.setColor(textColor);

        String pctStr = String.format("%.0f%%", percent);
        g.setFont(new Font("SansSerif", Font.BOLD, sz / 4));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(pctStr,
                x + (sz - fm.stringWidth(pctStr)) / 2,
                y + sz / 2 + fm.getAscent() / 2 - (label.isEmpty() ? 0 : fm.getHeight() / 3));

        if (!label.isEmpty()) {
            g.setFont(new Font("SansSerif", Font.PLAIN, sz / 6));
            fm = g.getFontMetrics();
            g.setColor(Color.GRAY);
            g.drawString(label,
                    x + (sz - fm.stringWidth(label)) / 2,
                    y + sz / 2 + fm.getAscent() / 2 + fm.getHeight() / 2);
        }

        g.dispose();
    }
}
