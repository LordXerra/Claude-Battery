package com.claudebattery;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Core service — polls for usage data and keeps an up-to-date
 * {@link UsageSnapshot} that the UI observes.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Web-session mode</b> ({@code config.useWebSession = true}) — uses the
 *       claude.ai session cookie; no API credits required.</li>
 *   <li><b>API-key mode</b> — probes rate-limit headers via the Anthropic API;
 *       requires API credits.</li>
 * </ul>
 */
public class ClaudeUsageService {

    private final ConfigManager                 configManager;
    private final UsageStore                    usageStore;
    private final AnthropicClient               apiClient;
    private final ClaudeWebClient               webClient;
    private final ScheduledExecutorService      scheduler;
    private final List<Consumer<UsageSnapshot>> listeners = new CopyOnWriteArrayList<>();

    private volatile UsageSnapshot latestSnapshot;
    private volatile String        lastStatusMessage = "";

    // Web-session cache (avoids re-fetching on every display rebuild)
    private volatile long    webMessagesUsed     = 0;
    private volatile long    webMessagesLimit    = 0;
    private volatile long    webResetMs          = 0;
    private volatile double  webHourlyPct        = -1;  // -1 = no data yet
    private volatile double  webWeeklyPct        = -1;
    private volatile long    webHourlyResetMs    = 0;
    private volatile long    webWeeklyResetMs    = 0;

    // Notification dedup flags
    private boolean notified80Hourly = false, notified95Hourly = false;
    private boolean notified80Weekly = false, notified95Weekly = false;

    public ClaudeUsageService(ConfigManager configManager) {
        this.configManager = configManager;
        this.usageStore    = new UsageStore(configManager.getUsageFilePath());
        this.apiClient     = new AnthropicClient();
        this.webClient     = new ClaudeWebClient();
        this.scheduler     = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-battery-poller");
            t.setDaemon(true);
            return t;
        });
        this.latestSnapshot = buildSnapshot();
    }

    public void start() {
        scheduler.submit(this::poll);
        int interval = Math.max(30, configManager.getConfig().pollIntervalSeconds);
        scheduler.scheduleAtFixedRate(this::poll, interval, interval, TimeUnit.SECONDS);
    }

    public void forceRefresh() { scheduler.submit(this::poll); }

    /* ── Poll ─────────────────────────────────────────────────────── */

    private void poll() {
        Config config = configManager.getConfig();

        if (config.useWebSession) {
            pollWebSession(config);
        } else {
            pollApiKey(config);
        }

        UsageSnapshot snap = buildSnapshot();
        checkNotifications(snap);
        publish(snap);
    }

    private void pollWebSession(Config config) {
        if (config.sessionKey == null || config.sessionKey.isBlank()) {
            lastStatusMessage = "⚠ No session key — open Settings to configure";
            return;
        }
        try {
            ClaudeWebClient.WebUsageInfo info = webClient.getUsage(config.sessionKey);
            if (info.authFailed) {
                lastStatusMessage = "⚠ Session key expired — refresh it in Settings";
            } else if (!info.valid) {
                lastStatusMessage = "⚠ " + (info.error != null ? info.error : "Could not fetch usage");
            } else if (info.hourlyUtilization >= 0 || info.weeklyUtilization >= 0) {
                // New format: utilization percentages directly
                lastStatusMessage = "";
                webHourlyPct     = info.hourlyUtilization >= 0 ? info.hourlyUtilization : 0;
                webWeeklyPct     = info.weeklyUtilization >= 0 ? info.weeklyUtilization : 0;
                webHourlyResetMs = info.hourlyResetMs;
                webWeeklyResetMs = info.weeklyResetMs;
            } else if (info.messagesLimit > 0) {
                // Old format: absolute counts
                lastStatusMessage = "";
                webMessagesUsed  = info.messagesUsed;
                webMessagesLimit = info.messagesLimit;
                webResetMs       = info.resetEpochMs > 0
                        ? info.resetEpochMs
                        : System.currentTimeMillis() + 7 * 86_400_000L;
                webHourlyPct = Math.min(100.0, info.messagesUsed * 100.0 / info.messagesLimit);
                webWeeklyPct = webHourlyPct;
                webHourlyResetMs = webResetMs;
                webWeeklyResetMs = webResetMs;
                usageStore.updateFromApiHeaders(
                        info.messagesLimit - info.messagesUsed,
                        info.messagesLimit,
                        webResetMs);
            } else {
                lastStatusMessage = "Connected to claude.ai — usage data not yet available";
                if (info.rawDebug != null) {
                    System.out.println("[ClaudeWebClient] Raw response: " + info.rawDebug);
                }
            }
        } catch (Exception e) {
            lastStatusMessage = "⚠ Network error: " + e.getMessage();
        }
    }

    private void pollApiKey(Config config) {
        if (config.apiKey == null || config.apiKey.isBlank()) {
            lastStatusMessage = "⚠ No API key — open Settings to configure";
            return;
        }
        try {
            AnthropicClient.RateLimitInfo info = apiClient.probeRateLimits(config.apiKey);
            if (info.noCredits) {
                lastStatusMessage = "⚠ No API credits — switch to Web Session mode in Settings";
            } else if (info.valid && info.tokensLimit > 0) {
                lastStatusMessage = "";
                long resetMs = info.tokensReset != null ? info.tokensReset.toEpochMilli() : 0L;
                usageStore.updateFromApiHeaders(info.tokensRemaining, info.tokensLimit, resetMs);
                if (config.autoDetectHourlyLimit && config.hourlyTokenLimit != info.tokensLimit) {
                    config.hourlyTokenLimit = info.tokensLimit;
                    configManager.saveConfig(config);
                }
            } else if (!info.valid) {
                lastStatusMessage = "⚠ API error: " + info.errorMessage;
            }
        } catch (Exception e) {
            lastStatusMessage = "⚠ Network error: " + e.getMessage();
        }
    }

    /* ── Snapshot builder ─────────────────────────────────────────── */

    private UsageSnapshot buildSnapshot() {
        Config config = configManager.getConfig();
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());

        long hourlyUsed, hourlyLimit, weeklyUsed, weeklyLimit, hourlyReset, weeklyReset;

        if (config.useWebSession && webHourlyPct >= 0) {
            // Web session mode: store pct as used/100 so downstream pct calc works
            hourlyUsed  = Math.round(webHourlyPct);
            hourlyLimit = 100;
            weeklyUsed  = Math.round(webWeeklyPct >= 0 ? webWeeklyPct : 0);
            weeklyLimit = 100;
            hourlyReset = webHourlyResetMs;
            weeklyReset = webWeeklyResetMs;
        } else {
            hourlyUsed  = usageStore.getHourlyTokensUsed();
            hourlyLimit = config.hourlyTokenLimit;
            weeklyUsed  = usageStore.getWeeklyTokensUsed();
            weeklyLimit = config.weeklyTokenLimit;
            hourlyReset = usageStore.getHourlyResetTime();
            weeklyReset = usageStore.getWeeklyResetTime();
        }

        double hourlyPct = hourlyLimit > 0 ? Math.min(100.0, hourlyUsed * 100.0 / hourlyLimit) : 0;
        double weeklyPct = weeklyLimit > 0 ? Math.min(100.0, weeklyUsed * 100.0 / weeklyLimit) : 0;

        String status = lastStatusMessage;
        boolean configured = config.useWebSession
                ? (config.sessionKey != null && !config.sessionKey.isBlank())
                : (config.apiKey != null && !config.apiKey.isBlank());

        return new UsageSnapshot(
                hourlyPct, weeklyPct,
                hourlyUsed, hourlyLimit,
                weeklyUsed, weeklyLimit,
                hourlyReset, weeklyReset,
                ts, configured, status
        );
    }

    /* ── Notifications ────────────────────────────────────────────── */

    private void checkNotifications(UsageSnapshot s) {
        Config cfg = configManager.getConfig();
        if (s.hourlyPercent() < 75) notified80Hourly = false;
        if (s.hourlyPercent() < 90) notified95Hourly = false;
        if (s.weeklyPercent() < 75) notified80Weekly = false;
        if (s.weeklyPercent() < 90) notified95Weekly = false;

        if (cfg.notifyAt80) {
            if (!notified80Hourly && s.hourlyPercent() >= 80) { notified80Hourly = true;
                NotificationManager.show("Usage Warning", String.format("Claude usage at %.0f%%", s.hourlyPercent())); }
            if (!notified80Weekly && s.weeklyPercent() >= 80) { notified80Weekly = true;
                NotificationManager.show("Weekly Warning", String.format("Claude weekly at %.0f%%", s.weeklyPercent())); }
        }
        if (cfg.notifyAt95) {
            if (!notified95Hourly && s.hourlyPercent() >= 95) { notified95Hourly = true;
                NotificationManager.show("Usage Critical", String.format("Claude usage at %.0f%%!", s.hourlyPercent())); }
            if (!notified95Weekly && s.weeklyPercent() >= 95) { notified95Weekly = true;
                NotificationManager.show("Weekly Critical", String.format("Claude weekly at %.0f%%!", s.weeklyPercent())); }
        }
    }

    private void publish(UsageSnapshot snap) {
        latestSnapshot = snap;
        for (Consumer<UsageSnapshot> l : listeners) {
            try { l.accept(snap); } catch (Exception e) {
                System.err.println("[ClaudeUsageService] Listener error: " + e.getMessage());
            }
        }
    }

    public UsageSnapshot  getLatestSnapshot()                      { return latestSnapshot; }
    public void           addListener(Consumer<UsageSnapshot> l)   { listeners.add(l); }
    public void           removeListener(Consumer<UsageSnapshot> l) { listeners.remove(l); }
    public UsageStore     getUsageStore()                           { return usageStore; }
    public ConfigManager  getConfigManager()                        { return configManager; }
    public void           shutdown()                                { scheduler.shutdownNow(); }
    /** Call after saving a new session key so the org ID is re-fetched. */
    public void           resetWebCache()                           { webClient.resetCache(); }
}
