package com.claudebattery;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

/**
 * Full dashboard window showing circular gauges, token counts, and reset times.
 */
public class DashboardWindow extends JFrame {

    private final ClaudeUsageService         usageService;
    private final Consumer<UsageSnapshot>    listener;

    // Hourly widgets
    private final CircularGaugePanel hourlyGauge  = new CircularGaugePanel(0);
    private final JLabel hourlyPctLabel           = bigLabel("0%");
    private final JLabel hourlyDetailLabel        = smallLabel("— / — tokens");
    private final JLabel hourlyResetLabel         = resetLabel("Resets in: —");

    // Weekly widgets
    private final CircularGaugePanel weeklyGauge  = new CircularGaugePanel(0);
    private final JLabel weeklyPctLabel           = bigLabel("0%");
    private final JLabel weeklyDetailLabel        = smallLabel("— / — tokens");
    private final JLabel weeklyResetLabel         = resetLabel("Resets in: —");

    private final JLabel updatedLabel = new JLabel("Last updated: —");

    public DashboardWindow(ClaudeUsageService usageService) {
        super("Claude Battery – Dashboard");
        this.usageService = usageService;

        buildUI();
        updateDisplay(usageService.getLatestSnapshot());

        listener = snap -> SwingUtilities.invokeLater(() -> updateDisplay(snap));
        usageService.addListener(listener);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                usageService.removeListener(listener);
            }
        });

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setMinimumSize(new Dimension(520, 420));
        setLocationRelativeTo(null);
    }

    /* ── UI construction ──────────────────────────────────────────── */
    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));

        // Title
        JPanel titlePanel = new JPanel();
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Claude API Usage Monitor", JLabel.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel subtitle = new JLabel("Developed by Tony Brice  ·  v1.0", JLabel.CENTER);
        subtitle.setFont(new Font("SansSerif", Font.ITALIC, 11));
        subtitle.setForeground(Color.GRAY);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        titlePanel.add(title);
        titlePanel.add(Box.createVerticalStrut(2));
        titlePanel.add(subtitle);
        root.add(titlePanel, BorderLayout.NORTH);

        // Gauge panels
        JPanel gauges = new JPanel(new GridLayout(1, 2, 20, 0));
        gauges.add(makeGaugeCard("Hourly",  hourlyGauge,  hourlyPctLabel,  hourlyDetailLabel,  hourlyResetLabel));
        gauges.add(makeGaugeCard("Weekly",  weeklyGauge,  weeklyPctLabel,  weeklyDetailLabel,  weeklyResetLabel));
        root.add(gauges, BorderLayout.CENTER);

        // Bottom bar
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        updatedLabel.setFont(updatedLabel.getFont().deriveFont(Font.ITALIC, 11f));
        updatedLabel.setForeground(Color.GRAY);
        bottom.add(updatedLabel, BorderLayout.WEST);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton refresh  = new JButton("Refresh");
        JButton settings = new JButton("Settings…");
        refresh.addActionListener(e -> usageService.forceRefresh());
        settings.addActionListener(e -> {
            SettingsDialog dlg = new SettingsDialog(this,
                    usageService.getConfigManager(), usageService);
            dlg.setVisible(true);
        });
        btnRow.add(refresh);
        btnRow.add(settings);
        bottom.add(btnRow, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel makeGaugeCard(String title,
                                 CircularGaugePanel gauge,
                                 JLabel pctLabel,
                                 JLabel detailLabel,
                                 JLabel resetLabel) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title,
                TitledBorder.CENTER, TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 13)));

        centre(gauge);
        centre(pctLabel);
        centre(detailLabel);
        centre(resetLabel);

        card.add(Box.createVerticalStrut(6));
        card.add(gauge);
        card.add(Box.createVerticalStrut(4));
        card.add(pctLabel);
        card.add(detailLabel);
        card.add(Box.createVerticalStrut(4));
        card.add(resetLabel);
        card.add(Box.createVerticalGlue());
        return card;
    }

    /* ── Update display ───────────────────────────────────────────── */
    private void updateDisplay(UsageSnapshot s) {
        // Hourly
        hourlyGauge.setPercent(s.hourlyPercent());
        hourlyPctLabel.setText(String.format("%.1f%%", s.hourlyPercent()));
        hourlyPctLabel.setForeground(BatteryIconRenderer.usageColor(s.hourlyPercent()));
        hourlyDetailLabel.setText(tokenDetail(s.hourlyUsed(), s.hourlyLimit()));
        hourlyResetLabel.setText("Resets in: " + timeLeft(s.hourlyResetMs()));

        // Weekly
        weeklyGauge.setPercent(s.weeklyPercent());
        weeklyPctLabel.setText(String.format("%.1f%%", s.weeklyPercent()));
        weeklyPctLabel.setForeground(BatteryIconRenderer.usageColor(s.weeklyPercent()));
        weeklyDetailLabel.setText(tokenDetail(s.weeklyUsed(), s.weeklyLimit()));
        weeklyResetLabel.setText("Resets in: " + timeLeft(s.weeklyResetMs()));

        String status = s.statusMessage();
        if (status != null && !status.isEmpty()) {
            updatedLabel.setText(status);
            updatedLabel.setForeground(new Color(180, 100, 0));
        } else {
            updatedLabel.setText("Last updated: " + s.lastUpdated());
            updatedLabel.setForeground(Color.GRAY);
        }
    }

    /* ── Helpers ──────────────────────────────────────────────────── */
    private static String tokenDetail(long used, long limit) {
        if (limit <= 0) return "Configure limit in Settings";
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

    private static void centre(JComponent c) {
        c.setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    private static JLabel bigLabel(String text) {
        JLabel l = new JLabel(text, JLabel.CENTER);
        l.setFont(new Font("SansSerif", Font.BOLD, 26));
        return l;
    }

    private static JLabel smallLabel(String text) {
        JLabel l = new JLabel(text, JLabel.CENTER);
        l.setFont(new Font("SansSerif", Font.PLAIN, 12));
        return l;
    }

    private static JLabel resetLabel(String text) {
        JLabel l = new JLabel(text, JLabel.CENTER);
        l.setFont(new Font("SansSerif", Font.ITALIC, 11));
        l.setForeground(Color.GRAY);
        return l;
    }
}
