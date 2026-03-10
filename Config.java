package com.claudebattery;

public class Config {
    public String apiKey = "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";
    public long hourlyTokenLimit = 40_000;
    public long weeklyTokenLimit = 1_000_000;
    public int pollIntervalSeconds = 300;
    public boolean notifyAt80 = true;
    public boolean notifyAt95 = true;
    public boolean autoDetectHourlyLimit = true;
}
