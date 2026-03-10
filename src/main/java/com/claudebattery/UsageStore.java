package com.claudebattery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.time.*;
import java.util.*;

/**
 * Persists and manages local token-usage counters for hourly and weekly windows.
 *
 * <p>Hourly window: updated from API rate-limit headers each poll cycle.
 * Weekly window: accumulated manually by summing deltas between polls.
 */
public class UsageStore {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final String filePath;

    /* ── Persisted state ────────────────────────────────────────────── */
    static class StoredData {
        // Hourly (from rate-limit headers)
        long hourlyTokensUsed    = 0;
        long hourlyWindowStart   = 0; // epoch-ms
        long hourlyRateLimitMs   = 0; // epoch-ms when API says window resets

        // Weekly (accumulated deltas)
        long weeklyTokensUsed  = 0;
        long weeklyWindowStart = 0; // epoch-ms of last Monday midnight

        // Last known API values — used to calculate deltas
        long lastKnownRemaining = -1;
        long lastKnownLimit     = -1;

        List<DailyUsage> dailyHistory = new ArrayList<>();
    }

    static class DailyUsage {
        String date;        // "YYYY-MM-DD"
        long   tokensUsed;
    }

    private StoredData data;

    public UsageStore(String filePath) {
        this.filePath = filePath;
        data = load();
        initWindows();
    }

    private void initWindows() {
        long now = System.currentTimeMillis();
        if (data.hourlyWindowStart == 0) data.hourlyWindowStart = now;

        if (data.weeklyWindowStart == 0) {
            data.weeklyWindowStart = weeklyWindowStart();
        }
    }

    private long weeklyWindowStart() {
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        return monday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /* ── Called on each successful API probe ─────────────────────────── */
    public synchronized void updateFromApiHeaders(long remaining, long limit, long resetEpochMs) {
        if (limit <= 0) return;

        long now = System.currentTimeMillis();

        // ── Hourly update ──────────────────────────────────────────────
        // Always use latest API values for the current rate-limit window.
        data.hourlyTokensUsed  = limit - remaining;
        data.hourlyWindowStart = now;
        data.hourlyRateLimitMs = resetEpochMs > 0 ? resetEpochMs : now + 3_600_000L;

        // ── Weekly delta ───────────────────────────────────────────────
        // Roll over weekly window if a new week has started.
        long weekStart = weeklyWindowStart();
        if (weekStart > data.weeklyWindowStart) {
            data.weeklyTokensUsed  = 0;
            data.weeklyWindowStart = weekStart;
            data.lastKnownRemaining = -1;
        }

        // Accumulate tokens consumed since last probe.
        if (data.lastKnownRemaining >= 0 && data.lastKnownLimit == limit) {
            long delta = data.lastKnownRemaining - remaining;
            if (delta > 0) {
                data.weeklyTokensUsed += delta;
                recordDailyUsage(delta);
            } else if (delta < -1_000) {
                // Rate-limit window rolled over — count newly-consumed tokens.
                long consumedInNewWindow = limit - remaining;
                if (consumedInNewWindow > 0) {
                    data.weeklyTokensUsed += consumedInNewWindow;
                    recordDailyUsage(consumedInNewWindow);
                }
            }
        } else if (data.lastKnownRemaining < 0) {
            // First reading — seed weekly with current usage.
            long used = limit - remaining;
            if (used > 0) data.weeklyTokensUsed += used;
        }

        data.lastKnownRemaining = remaining;
        data.lastKnownLimit     = limit;

        save();
    }

    private void recordDailyUsage(long tokens) {
        String today = LocalDate.now().toString();
        DailyUsage day = data.dailyHistory.stream()
                .filter(d -> today.equals(d.date))
                .findFirst().orElseGet(() -> {
                    DailyUsage nd = new DailyUsage();
                    nd.date = today;
                    nd.tokensUsed = 0;
                    data.dailyHistory.add(nd);
                    return nd;
                });
        day.tokensUsed += tokens;

        // Keep only 30 days
        if (data.dailyHistory.size() > 30) {
            data.dailyHistory.sort(Comparator.comparing(d -> d.date));
            data.dailyHistory = new ArrayList<>(
                    data.dailyHistory.subList(data.dailyHistory.size() - 30, data.dailyHistory.size()));
        }
    }

    /* ── Getters ─────────────────────────────────────────────────────── */
    public synchronized long getHourlyTokensUsed()  { return data.hourlyTokensUsed; }
    public synchronized long getWeeklyTokensUsed()  { return data.weeklyTokensUsed; }

    public synchronized long getHourlyResetTime() {
        return data.hourlyRateLimitMs > 0
                ? data.hourlyRateLimitMs
                : data.hourlyWindowStart + 3_600_000L;
    }

    public synchronized long getWeeklyResetTime() {
        return data.weeklyWindowStart + 7 * 86_400_000L;
    }

    public synchronized List<DailyUsage> getDailyHistory() {
        return Collections.unmodifiableList(data.dailyHistory);
    }

    /* ── Manual resets ───────────────────────────────────────────────── */
    public synchronized void resetHourly() {
        data.hourlyTokensUsed    = 0;
        data.hourlyWindowStart   = System.currentTimeMillis();
        data.hourlyRateLimitMs   = 0;
        data.lastKnownRemaining  = -1;
        save();
    }

    public synchronized void resetWeekly() {
        data.weeklyTokensUsed   = 0;
        data.weeklyWindowStart  = System.currentTimeMillis();
        save();
    }

    /* ── Persistence ─────────────────────────────────────────────────── */
    private StoredData load() {
        File f = new File(filePath);
        if (!f.exists()) return new StoredData();
        try (Reader r = new FileReader(f)) {
            StoredData d = gson.fromJson(r, StoredData.class);
            return d != null ? d : new StoredData();
        } catch (IOException e) {
            return new StoredData();
        }
    }

    private void save() {
        try (Writer w = new FileWriter(filePath)) {
            gson.toJson(data, w);
        } catch (IOException e) {
            System.err.println("Cannot save usage data: " + e.getMessage());
        }
    }
}
