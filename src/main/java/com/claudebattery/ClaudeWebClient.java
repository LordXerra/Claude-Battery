package com.claudebattery;

import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeParseException;

/**
 * Fetches usage data from the claude.ai web interface using the user's
 * browser session cookie — no API credits required.
 *
 * <p>To get the session key:
 * <ol>
 *   <li>Open claude.ai in Chrome / Safari / Firefox</li>
 *   <li>Open DevTools → Application (Chrome) or Storage (Firefox) → Cookies → claude.ai</li>
 *   <li>Copy the value of the cookie named {@code sessionKey}</li>
 * </ol>
 */
public class ClaudeWebClient {

    private static final String BASE = "https://claude.ai";

    public static class WebUsageInfo {
        public long    messagesUsed  = 0;
        public long    messagesLimit = 0;
        public long    resetEpochMs  = 0;
        public boolean valid         = false;
        public boolean authFailed    = false;
        public String  error;
        public String  rawDebug;
        // Utilization-based fields (newer API format)
        public double hourlyUtilization = -1;  // percentage 0-100, -1 = not available
        public double weeklyUtilization = -1;
        public long   hourlyResetMs     = 0;
        public long   weeklyResetMs     = 0;
    }

    private final HttpClient http;
    private final Gson       gson = new Gson();
    private volatile String  cachedOrgId = null;  // skip bootstrap once we know the org ID

    public ClaudeWebClient() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /* ── Public API ──────────────────────────────────────────────── */

    public WebUsageInfo getUsage(String sessionKey) throws IOException, InterruptedException {
        WebUsageInfo result = new WebUsageInfo();

        // Use cached org ID if available — skips bootstrap entirely (avoids Cloudflare)
        String orgId = cachedOrgId;

        if (orgId == null) {
            // Bootstrap → get org UUID
            HttpResponse<String> bootResp = fetch("/api/bootstrap", sessionKey);
            if (bootResp == null) {
                result.error = "Could not connect to claude.ai";
                return result;
            }
            if (bootResp.statusCode() == 401 || bootResp.statusCode() == 403) {
                result.authFailed = true;
                result.error      = "Session key rejected (expired or invalid)";
                return result;
            }
            if (bootResp.statusCode() != 200) {
                result.error = "Bootstrap returned HTTP " + bootResp.statusCode();
                return result;
            }
            JsonObject boot;
            try {
                boot = gson.fromJson(bootResp.body(), JsonObject.class);
            } catch (JsonSyntaxException e) {
                result.error = "Unexpected response from claude.ai";
                return result;
            }
            orgId = extractOrgId(boot);
            if (orgId == null) {
                result.valid    = true;
                result.error    = "Could not read organisation ID from bootstrap response";
                result.rawDebug = bootResp.body();
                return result;
            }
            cachedOrgId = orgId;  // cache for future polls
        }

        // Fetch usage data directly (no bootstrap needed)
        tryFetchUsage(orgId, sessionKey, result);

        // If usage fetch failed with a cached org ID, clear cache and signal retry next poll
        if (result.hourlyUtilization < 0 && result.messagesLimit == 0 && result.rawDebug == null) {
            cachedOrgId = null;
            result.error = "Could not fetch usage — will retry";
            return result;
        }

        result.valid = true;
        return result;
    }

    /** Clears the cached org ID — call when the session key changes. */
    public void resetCache() { cachedOrgId = null; }

    /** Quick check: returns true if the session key gets a 200 from bootstrap. */
    public boolean validateSessionKey(String sessionKey) {
        try {
            HttpResponse<String> r = fetch("/api/bootstrap", sessionKey);
            return r != null && r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /* ── Internal helpers ────────────────────────────────────────── */

    private void tryFetchUsage(String orgId, String sessionKey, WebUsageInfo out)
            throws IOException, InterruptedException {

        // Try several plausible usage endpoints in order
        String[] paths = {
            "/api/organizations/" + orgId + "/active_plan",
            "/api/organizations/" + orgId + "/usage",
            "/api/organizations/" + orgId + "/subscription",
        };

        for (String path : paths) {
            HttpResponse<String> r = fetch(path, sessionKey);
            if (r != null && r.statusCode() == 200) {
                out.rawDebug = r.body();
                if (parseUsage(r.body(), out)) return;
            }
        }

        // Fallback: try to pull usage from the bootstrap org object
        // (some API versions embed it there)
    }

    /** Returns true if useful usage numbers were extracted. */
    private boolean parseUsage(String json, WebUsageInfo out) {
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);

            // Newer format: { "five_hour": { "utilization": 56.0, "resets_at": "..." }, "seven_day": {...} }
            if (obj.has("five_hour") || obj.has("seven_day")) {
                if (obj.has("five_hour") && obj.get("five_hour").isJsonObject()) {
                    JsonObject fh = obj.getAsJsonObject("five_hour");
                    if (fh.has("utilization")) out.hourlyUtilization = fh.get("utilization").getAsDouble();
                    if (fh.has("resets_at")) {
                        try { out.hourlyResetMs = Instant.parse(fh.get("resets_at").getAsString()).toEpochMilli(); } catch (Exception ignored) {}
                    }
                }
                if (obj.has("seven_day") && obj.get("seven_day").isJsonObject()) {
                    JsonObject sd = obj.getAsJsonObject("seven_day");
                    if (sd.has("utilization")) out.weeklyUtilization = sd.get("utilization").getAsDouble();
                    if (sd.has("resets_at")) {
                        try { out.weeklyResetMs = Instant.parse(sd.get("resets_at").getAsString()).toEpochMilli(); } catch (Exception ignored) {}
                    }
                }
                return out.hourlyUtilization >= 0 || out.weeklyUtilization >= 0;
            }

            return parseRecursive(obj, out);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Walks common JSON shapes returned by the claude.ai internal API.
     * Different versions of the API use different field names.
     */
    private boolean parseRecursive(JsonObject obj, WebUsageInfo out) {
        if (obj == null) return false;

        // ── "limits" block ─────────────────────────────────────────
        if (obj.has("limits")) {
            JsonObject lim = obj.getAsJsonObject("limits");
            trySetUsed(lim,  out, "messages_sent",        "message_count",
                               "messages_used",           "messagesSentInWindow");
            trySetLimit(lim, out, "messages_limit",       "max_messages",
                                  "window_size",          "windowSize");
            trySetReset(lim, out, "window_reset_at",      "reset_at", "next_reset");
        }

        // ── "usage" block ──────────────────────────────────────────
        if (obj.has("usage")) {
            JsonObject u = obj.getAsJsonObject("usage");
            trySetUsed(u,  out, "messages_sent",  "message_count",  "messages_used");
            trySetLimit(u, out, "messages_limit", "max_messages",   "message_cap");
            trySetReset(u, out, "reset_at",       "next_reset",     "window_reset_at");
        }

        // ── flat fields ────────────────────────────────────────────
        trySetUsed(obj,  out, "messages_sent",  "message_count");
        trySetLimit(obj, out, "messages_limit", "message_cap");
        trySetReset(obj, out, "reset_at",       "usage_reset_at");

        // ── plan → usage nesting ───────────────────────────────────
        for (String key : new String[]{"plan", "subscription", "rate_limit"}) {
            if (obj.has(key) && obj.get(key).isJsonObject()) {
                parseRecursive(obj.getAsJsonObject(key), out);
            }
        }

        return out.messagesLimit > 0;
    }

    private void trySetUsed(JsonObject obj, WebUsageInfo out, String... names) {
        if (out.messagesUsed > 0) return;
        for (String n : names) {
            if (obj.has(n) && obj.get(n).isJsonPrimitive()) {
                try { out.messagesUsed = obj.get(n).getAsLong(); return; } catch (Exception ignored) {}
            }
        }
    }

    private void trySetLimit(JsonObject obj, WebUsageInfo out, String... names) {
        if (out.messagesLimit > 0) return;
        for (String n : names) {
            if (obj.has(n) && obj.get(n).isJsonPrimitive()) {
                try { out.messagesLimit = obj.get(n).getAsLong(); return; } catch (Exception ignored) {}
            }
        }
    }

    private void trySetReset(JsonObject obj, WebUsageInfo out, String... names) {
        if (out.resetEpochMs > 0) return;
        for (String n : names) {
            if (obj.has(n) && obj.get(n).isJsonPrimitive()) {
                String v = obj.get(n).getAsString();
                try {
                    out.resetEpochMs = Instant.parse(v).toEpochMilli();
                    return;
                } catch (DateTimeParseException ignored) {}
                try {
                    out.resetEpochMs = Long.parseLong(v) * 1000L;
                    return;
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private String extractOrgId(JsonObject boot) {
        // Shape 1a: { "account": { "memberships": [ { "organization": { "uuid": "..." } } ] } }
        try {
            JsonArray m = boot.getAsJsonObject("account").getAsJsonArray("memberships");
            if (m.size() > 0) return m.get(0).getAsJsonObject().getAsJsonObject("organization").get("uuid").getAsString();
        } catch (Exception ignored) {}

        // Shape 1b: { "account": { "memberships": [ { "organization_id": "..." } ] } }
        try {
            JsonArray m = boot.getAsJsonObject("account").getAsJsonArray("memberships");
            if (m.size() > 0) return m.get(0).getAsJsonObject().get("organization_id").getAsString();
        } catch (Exception ignored) {}

        // Shape 2: { "organizations": [ { "uuid": "..." } ] }
        try {
            JsonArray orgs = boot.getAsJsonArray("organizations");
            if (orgs.size() > 0) return orgs.get(0).getAsJsonObject().get("uuid").getAsString();
        } catch (Exception ignored) {}

        // Shape 3: { "organization": { "uuid": "..." } }
        try {
            return boot.getAsJsonObject("organization").get("uuid").getAsString();
        } catch (Exception ignored) {}

        // Shape 4: { "default_organization_uuid": "..." }
        try {
            return boot.get("default_organization_uuid").getAsString();
        } catch (Exception ignored) {}

        return null;
    }

    private HttpResponse<String> fetch(String path, String sessionKey)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .header("Cookie",            "sessionKey=" + sessionKey)
                .header("User-Agent",        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                                            + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                            + "Chrome/120.0.0.0 Safari/537.36")
                .header("Accept",            "application/json, text/plain, */*")
                .header("Accept-Language",   "en-US,en;q=0.9")
                .header("Referer",           "https://claude.ai/")
                .header("Origin",            "https://claude.ai")
                .header("sec-ch-ua",         "\"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                .header("sec-ch-ua-mobile",  "?0")
                .header("sec-ch-ua-platform","\"macOS\"")
                .header("sec-fetch-dest",    "empty")
                .header("sec-fetch-mode",    "cors")
                .header("sec-fetch-site",    "same-origin")
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
