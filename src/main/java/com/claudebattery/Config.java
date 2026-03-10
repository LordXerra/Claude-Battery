package com.claudebattery;

public class Config {
    // ── Mode ──────────────────────────────────────────────────────
    /** true = use claude.ai session cookie (no API credits needed); false = use API key */
    public boolean useWebSession = false;

    // ── Web session mode ──────────────────────────────────────────
    public String sessionKey = "";

    // ── API key mode ──────────────────────────────────────────────
    public String apiKey = "";
    public boolean autoDetectHourlyLimit = true;

    // ── Shared ────────────────────────────────────────────────────
    public long hourlyTokenLimit = 40_000;
    public long weeklyTokenLimit = 1_000_000;
    public int  pollIntervalSeconds = 300;
    public boolean notifyAt80 = true;
    public boolean notifyAt95 = true;
}
