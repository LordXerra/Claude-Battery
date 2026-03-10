package com.claudebattery;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;

/**
 * A lightweight, undecorated window that floats near the cursor
 * to display current usage details when the user hovers over the tray icon.
 */
public class HoverPopup extends JWindow {

    private final JLabel hourlyBar;
    private final JLabel weeklyBar;
    private final JLabel hourlyDetail;
    private final JLabel weeklyDetail;
    private final JLabel hourlyReset;
    private final JLabel weeklyReset;
    private final JLabel updated;

    public HoverPopup() {
        setAlwaysOnTop(true);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(new Color(30, 30, 30));
        root.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(80, 80, 80), 1, true),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));

        // Title
        JLabel title = mk("Claude Battery", Font.BOLD, 13, Color.WHITE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(title);
        root.add(vgap(6));
        root.add(separator());

        // Hourly
        root.add(vgap(6));
        root.add(sectionLabel("HOURLY"));
        hourlyBar    = barLabel();
        hourlyDetail = mk("", Font.PLAIN, 11, new Color(180, 180, 180));
        hourlyReset  = mk("", Font.ITALIC, 10, new Color(120, 120, 120));
        root.add(hourlyBar);
        root.add(hourlyDetail);
        root.add(hourlyReset);

        root.add(vgap(8));
        root.add(separator());

        // Weekly
        root.add(vgap(6));
        root.add(sectionLabel("WEEKLY"));
        weeklyBar    = barLabel();
        weeklyDetail = mk("", Font.PLAIN, 11, new Color(180, 180, 180));
        weeklyReset  = mk("", Font.ITALIC, 10, new Color(120, 120, 120));
        root.add(weeklyBar);
        root.add(weeklyDetail);
        root.add(weeklyReset);

        root.add(vgap(8));
        root.add(separator());
        root.add(vgap(4));
        updated = mk("", Font.ITALIC, 10, new Color(100, 100, 100));
        root.add(updated);

        setContentPane(root);
    }

    public void update(UsageSnapshot s) {
        hourlyBar.setText(buildBarText("Hourly", s.hourlyPercent()));
        hourlyBar.setForeground(BatteryIconRenderer.usageColor(s.hourlyPercent()));
        hourlyDetail.setText("  " + fmtDetail(s.hourlyUsed(), s.hourlyLimit()));
        hourlyReset.setText("  Resets in: " + timeLeft(s.hourlyResetMs()));

        weeklyBar.setText(buildBarText("Weekly", s.weeklyPercent()));
        weeklyBar.setForeground(BatteryIconRenderer.usageColor(s.weeklyPercent()));
        weeklyDetail.setText("  " + fmtDetail(s.weeklyUsed(), s.weeklyLimit()));
        weeklyReset.setText("  Resets in: " + timeLeft(s.weeklyResetMs()));

        if (s.statusMessage() != null && !s.statusMessage().isEmpty()) {
            updated.setText(s.statusMessage());
            updated.setForeground(new Color(220, 160, 50));
        } else {
            updated.setText("Updated: " + s.lastUpdated());
            updated.setForeground(new Color(100, 100, 100));
        }

        pack();
    }

    /** Show near the given screen point, staying within screen bounds. */
    public void showNear(Point screenPt) {
        pack();
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        // Place above cursor (macOS menu bar at top)
        int x = screenPt.x - getWidth() / 2;
        int y = screenPt.y + 12;  // below cursor by default

        // Flip above if too close to bottom
        if (y + getHeight() > scr.height - 40) {
            y = screenPt.y - getHeight() - 8;
        }
        x = Math.max(4, Math.min(x, scr.width - getWidth() - 4));
        setLocation(x, y);
        setVisible(true);
    }

    /* ── Widget helpers ───────────────────────────────────────────── */
    private static JLabel mk(String text, int style, int size, Color fg) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", style, size));
        l.setForeground(fg);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel sectionLabel(String text) {
        JLabel l = mk(text, Font.BOLD, 10, new Color(150, 150, 150));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JLabel barLabel() {
        JLabel l = mk("", Font.BOLD, 13, Color.WHITE);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static Component separator() {
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(70, 70, 70));
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        return sep;
    }

    private static Component vgap(int h) {
        return Box.createRigidArea(new Dimension(0, h));
    }

    private static String buildBarText(String label, double pct) {
        int filled = (int) (pct / 5.0);
        int empty  = 20 - filled;
        return String.format("%.1f%%  [%s%s]", pct,
                "█".repeat(filled), "░".repeat(empty));
    }

    private static String fmtDetail(long used, long limit) {
        if (limit <= 0) return "—";
        return fmt(used) + " / " + fmt(limit) + " tokens";
    }

    private static String fmt(long n) {
        if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String timeLeft(long resetMs) {
        long rem = resetMs - System.currentTimeMillis();
        if (rem <= 0) return "now";
        long s = rem / 1000, m = s / 60, h = m / 60, d = h / 24;
        if (d > 0)  return d + "d " + (h % 24) + "h";
        if (h > 0)  return h + "h " + (m % 60) + "m";
        if (m > 0)  return m + "m " + (s % 60) + "s";
        return s + "s";
    }
}
