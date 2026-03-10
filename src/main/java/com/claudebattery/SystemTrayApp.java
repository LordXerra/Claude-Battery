package com.claudebattery;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Manages the system-tray icon, hover popup, and right-click menu.
 */
public class SystemTrayApp {

    private final ConfigManager      configManager;
    private final ClaudeUsageService usageService;

    private SystemTray tray;
    private TrayIcon   trayIcon;
    private HoverPopup hoverPopup;
    private int        iconSize;

    public SystemTrayApp() {
        this.configManager = new ConfigManager();
        this.usageService  = new ClaudeUsageService(configManager);
    }

    public void start() {
        tray     = SystemTray.getSystemTray();
        iconSize = Math.max(tray.getTrayIconSize().width, tray.getTrayIconSize().height);

        // ── Initial icon ──────────────────────────────────────────────
        Image img     = BatteryIconRenderer.renderForSize(0, 0, iconSize);
        PopupMenu menu = buildMenu();
        trayIcon = new TrayIcon(img, "Claude Battery", menu);
        trayIcon.setImageAutoSize(true);
        NotificationManager.setTrayIcon(trayIcon);

        // ── Hover popup ───────────────────────────────────────────────
        hoverPopup = new HoverPopup();

        trayIcon.addMouseListener(new MouseAdapter() {
            // mouseEntered / mouseExited may not fire reliably on macOS but are
            // harmless when they do.
            @Override public void mouseEntered(MouseEvent e) {
                showPopup(e.getLocationOnScreen());
            }
            @Override public void mouseExited(MouseEvent e) {
                hidePopup();
            }
            // Double-click opens the dashboard.
            // Single-click is intentionally NOT handled here so it does not
            // put the HoverPopup on top of the native popup menu (which macOS
            // shows on every click of a TrayIcon that has a PopupMenu set).
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                    hidePopup();
                    SwingUtilities.invokeLater(SystemTrayApp.this::openDashboard);
                }
            }
        });

        // ── Snapshot listener → update icon + popup ───────────────────
        usageService.addListener(snap -> SwingUtilities.invokeLater(() -> {
            trayIcon.setImage(
                    BatteryIconRenderer.renderForSize(snap.hourlyPercent(), snap.weeklyPercent(), iconSize));
            trayIcon.setToolTip(shortTooltip(snap));  // fallback single-line tooltip
            if (hoverPopup.isVisible()) {
                hoverPopup.update(snap);
            }
        }));

        // ── Add to tray ───────────────────────────────────────────────
        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            System.err.println("Cannot add tray icon: " + e.getMessage());
            return;
        }

        // ── Open settings on first run ────────────────────────────────
        if (configManager.getConfig().apiKey.isBlank()) {
            SwingUtilities.invokeLater(this::openSettings);
        }

        usageService.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            usageService.shutdown();
            tray.remove(trayIcon);
        }));
    }

    /* ── Context menu ─────────────────────────────────────────────── */
    private PopupMenu buildMenu() {
        PopupMenu menu = new PopupMenu();

        MenuItem dashboard = new MenuItem("Open Dashboard");
        dashboard.addActionListener(e -> SwingUtilities.invokeLater(this::openDashboard));

        MenuItem refresh = new MenuItem("Refresh Now");
        refresh.addActionListener(e -> usageService.forceRefresh());

        MenuItem settings = new MenuItem("Settings…");
        settings.addActionListener(e -> SwingUtilities.invokeLater(this::openSettings));

        MenuItem resetHourly = new MenuItem("Reset Hourly Counter");
        resetHourly.addActionListener(e -> {
            if (confirm("Reset the hourly usage counter to zero?")) {
                usageService.getUsageStore().resetHourly();
                usageService.forceRefresh();
            }
        });

        MenuItem resetWeekly = new MenuItem("Reset Weekly Counter");
        resetWeekly.addActionListener(e -> {
            if (confirm("Reset the weekly usage counter to zero?")) {
                usageService.getUsageStore().resetWeekly();
                usageService.forceRefresh();
            }
        });

        MenuItem quit = new MenuItem("Quit Claude Battery");
        quit.addActionListener(e -> {
            usageService.shutdown();
            tray.remove(trayIcon);
            System.exit(0);
        });

        menu.add(dashboard);
        menu.add(refresh);
        menu.addSeparator();
        menu.add(settings);
        menu.add(resetHourly);
        menu.add(resetWeekly);
        menu.addSeparator();
        menu.add(quit);
        return menu;
    }

    /* ── Popup helpers ────────────────────────────────────────────── */
    private void showPopup(Point screenPt) {
        hoverPopup.update(usageService.getLatestSnapshot());
        hoverPopup.showNear(screenPt);
    }

    private void hidePopup() {
        hoverPopup.setVisible(false);
    }

    /* ── Dashboard ────────────────────────────────────────────────── */
    private void openDashboard() {
        DashboardWindow win = new DashboardWindow(usageService);
        win.setAlwaysOnTop(true);
        win.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowDeactivated(java.awt.event.WindowEvent e) {
                win.setAlwaysOnTop(true);
            }
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                win.setAlwaysOnTop(false);
            }
        });
        win.setVisible(true);
        win.toFront();
        win.requestFocus();
    }

    /* ── Settings ─────────────────────────────────────────────────── */
    private void openSettings() {
        SettingsDialog dlg = new SettingsDialog(null, configManager, usageService);
        // Keep always-on-top until closed — re-assert on deactivation so it can't slip behind
        dlg.setAlwaysOnTop(true);
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowDeactivated(java.awt.event.WindowEvent e) {
                dlg.setAlwaysOnTop(true);
            }
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                dlg.setAlwaysOnTop(false);
            }
        });
        dlg.setVisible(true);
        dlg.toFront();
        dlg.requestFocus();
    }

    /** Shows a YES/NO dialog using a null-parent JDialog (works without a Dock icon). */
    private static boolean confirm(String message) {
        JDialog d = new JDialog((Frame) null, "Confirm", true);
        bringToFront(d);
        int result = JOptionPane.showConfirmDialog(
                null, message, "Confirm", JOptionPane.YES_NO_OPTION);
        return result == JOptionPane.YES_OPTION;
    }

    /**
     * On macOS with apple.awt.UIElement=true, Swing windows don't grab focus
     * automatically. setAlwaysOnTop(true) forces them to the front.
     * We set it false once the window is shown so it doesn't permanently float.
     */
    private static void bringToFront(Window w) {
        w.setAlwaysOnTop(true);
        w.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                w.setAlwaysOnTop(false);
                w.toFront();
                w.requestFocus();
            }
        });
    }

    /* ── Short tooltip (single-line, macOS-safe fallback) ─────────── */
    private static String shortTooltip(UsageSnapshot s) {
        return String.format("Claude  H:%.0f%%  W:%.0f%%",
                s.hourlyPercent(), s.weeklyPercent());
    }
}
