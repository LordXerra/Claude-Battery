package com.claudebattery;

/**
 * Immutable snapshot of current usage state.
 */
public record UsageSnapshot(
        double hourlyPercent,
        double weeklyPercent,
        long hourlyUsed,
        long hourlyLimit,
        long weeklyUsed,
        long weeklyLimit,
        long hourlyResetMs,
        long weeklyResetMs,
        String lastUpdated,
        boolean apiKeyConfigured,
        String statusMessage   // "" = normal, otherwise a warning shown in the UI
) {
    public static UsageSnapshot empty() {
        long now = System.currentTimeMillis();
        return new UsageSnapshot(
                0, 0, 0, 0, 0, 0,
                now + 3_600_000L,
                now + 7 * 86_400_000L,
                "Never",
                false,
                ""
        );
    }
}
