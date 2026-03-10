package com.claudebattery;

import java.awt.TrayIcon;

/**
 * Thin wrapper around {@link TrayIcon#displayMessage} for desktop notifications.
 */
public final class NotificationManager {

    private static TrayIcon trayIcon;

    private NotificationManager() {}

    public static void setTrayIcon(TrayIcon icon) {
        trayIcon = icon;
    }

    public static void show(String title, String message) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.WARNING);
        }
    }
}
