package com.claudebattery;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Minimal Anthropic API client.
 *
 * Uses the {@code count_tokens} endpoint to probe current rate-limit headers
 * without consuming message credits.
 */
public class AnthropicClient {

    private static final String BASE_URL     = "https://api.anthropic.com";
    private static final String API_VERSION  = "2023-06-01";
    private static final String COUNT_TOKENS = BASE_URL + "/v1/messages/count_tokens";
    private static final String BETA_HEADER  = "token-counting-2024-11-01";

    // Minimal payload — cheapest possible count_tokens call
    private static final String PROBE_BODY;
    static {
        Gson g = new Gson();
        PROBE_BODY = g.toJson(Map.of(
                "model",    "claude-haiku-4-5-20251001",
                "messages", new Object[]{Map.of("role", "user", "content", "hi")},
                "max_tokens", 1
        ));
    }

    public static class RateLimitInfo {
        public long    tokensLimit     = -1;
        public long    tokensRemaining = -1;
        public Instant tokensReset;
        public boolean valid           = false;
        public boolean noCredits       = false; // key OK but account has no API balance
        public String  errorMessage;
    }

    private final HttpClient http;

    public AnthropicClient() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Probes the Anthropic API and returns rate-limit information.
     * A 200 or 422 response indicates the API key is valid.
     */
    public RateLimitInfo probeRateLimits(String apiKey) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(COUNT_TOKENS))
                .header("x-api-key",          apiKey)
                .header("anthropic-version",  API_VERSION)
                .header("anthropic-beta",     BETA_HEADER)
                .header("content-type",       "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(PROBE_BODY))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        RateLimitInfo info = new RateLimitInfo();
        int status = response.statusCode();
        info.valid = (status == 200 || status == 422);

        if (!info.valid) {
            String body = response.body();
            info.errorMessage = "HTTP " + status + ": " + body;
            // 400 "credit balance too low" → key is authentic, account just needs top-up
            if (status == 400 && body != null && body.contains("credit balance")) {
                info.noCredits = true;
            }
            return info;
        }

        HttpHeaders h = response.headers();
        info.tokensLimit     = parseLong(h, "anthropic-ratelimit-tokens-limit");
        info.tokensRemaining = parseLong(h, "anthropic-ratelimit-tokens-remaining");

        h.firstValue("anthropic-ratelimit-tokens-reset").ifPresent(v -> {
            try { info.tokensReset = Instant.parse(v); } catch (Exception ignored) {}
        });

        return info;
    }

    /** Returns true if the API key is accepted by the Anthropic API. */
    public boolean validateApiKey(String apiKey) {
        try {
            return probeRateLimits(apiKey).valid;
        } catch (Exception e) {
            return false;
        }
    }

    private long parseLong(HttpHeaders headers, String name) {
        return headers.firstValue(name)
                .map(v -> { try { return Long.parseLong(v); } catch (NumberFormatException e) { return -1L; } })
                .orElse(-1L);
    }
}
