package com.claudebattery;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/**
 * Entry point.
 *
 * macOS: set {@code apple.awt.UIElement=true} BEFORE the AWT toolkit is
 * initialised so the app never appears in the Dock.
 */
public class Main {

    public static void main(String[] args) {
        // Single-instance lock — silently exit if already running
        if (!acquireLock()) return;

        // Must be set before any AWT class is loaded
        System.setProperty("apple.awt.UIElement",        "true");
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("awt.useSystemAAFontSettings","on");
        System.setProperty("swing.aatext",               "true");

        if (!SystemTray.isSupported()) {
            JOptionPane.showMessageDialog(
                    null,
                    "System tray is not supported on this platform.\n"
                            + "Claude Battery requires system-tray support to run.",
                    "Claude Battery – Unsupported",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Fall back to cross-platform L&F — not fatal
            }
            new SystemTrayApp().start();
        });
    }

    /** Returns true if this process acquired the lock (i.e. no other instance running). */
    private static boolean acquireLock() {
        try {
            Path lockFile = Path.of(System.getProperty("user.home"), ".claude-battery", "app.lock");
            Files.createDirectories(lockFile.getParent());
            FileChannel ch = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = ch.tryLock();
            if (lock == null) return false;   // another instance holds it
            // Keep channel open for lifetime of process — lock released on exit
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { lock.release(); ch.close(); } catch (Exception ignored) {}
            }));
            return true;
        } catch (Exception e) {
            return true; // if locking fails, allow startup anyway
        }
    }
}
