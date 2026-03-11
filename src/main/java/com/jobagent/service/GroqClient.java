package com.jobagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobagent.config.AppConfig;
import com.jobagent.config.GroqApiKeyManager;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * HTTP client for the Groq LLM API (OpenAI-compatible endpoint).
 *
 * Why Groq instead of OpenAI: Groq offers a generous free tier with
 * very high token-per-minute limits on llama-3.3-70b, which makes it
 * practical to score 100+ jobs per pipeline run without cost.
 *
 * Key behaviours:
 *  - All requests go to the same endpoint with the Bearer token swapped
 *    per request (rotation is transparent to callers).
 *  - On HTTP 429 (rate-limited): rotates to the next API key and retries
 *    immediately (up to MAX_RETRIES times).
 *  - On other HTTP errors: throws RuntimeException so the pipeline can
 *    decide whether to skip the job or abort the run.
 *  - temperature=0.2 keeps responses deterministic (important for scoring).
 *
 * Singleton accessor (getInstance) is used by NaukriFilterAgent, which
 * cannot receive the instance through its constructor chain.
 */
public class GroqClient {

    private static final Logger     log      = LoggerFactory.getLogger(GroqClient.class);
    private static final String     GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final MediaType  JSON_MT  = MediaType.get("application/json; charset=utf-8");
    private static final int        MAX_RETRIES = 3;

    // Singleton reference — set by the first constructor call from Main
    private static GroqClient instance;

    private final GroqApiKeyManager keys;
    private final OkHttpClient      http;
    private final ObjectMapper      mapper = new ObjectMapper();

    public GroqClient(GroqApiKeyManager keys) {
        this.keys = keys;
        // Generous timeouts because Groq can take 20-40 s for a long CV + JD prompt
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60,  TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        instance = this; // register as singleton for NaukriFilterAgent
    }

    /**
     * Returns the shared instance created by Main.
     * Throws if called before Main has constructed GroqClient.
     */
    public static GroqClient getInstance() {
        if (instance == null)
            throw new IllegalStateException("GroqClient not yet initialised — call new GroqClient(...) first.");
        return instance;
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Single-turn chat with a default system prompt.
     * Convenience overload used by NaukriFilterAgent.
     */
    public String chat(String userPrompt) {
        return chat("You are a helpful assistant.", userPrompt);
    }

    /**
     * Single-turn chat with an explicit system prompt.
     * Used by CVReaderAgent (keyword/role inference) and JobScoringAgent (scoring).
     *
     * @param systemPrompt sets the model's persona / instructions
     * @param userPrompt   the actual question or task
     * @return assistant reply text, trimmed
     */
    public String chat(String systemPrompt, String userPrompt) {
        // Build the JSON request body once; reused across retries
        ObjectNode body = mapper.createObjectNode();
        body.put("model",       AppConfig.getGroqModel());
        body.put("max_tokens",  1024);
        body.put("temperature", 0.2);  // low = deterministic, important for scoring

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Request req = new Request.Builder()
                        .url(GROQ_URL)
                        .addHeader("Authorization", "Bearer " + keys.current())
                        .addHeader("Content-Type",  "application/json")
                        .post(RequestBody.create(mapper.writeValueAsString(body), JSON_MT))
                        .build();

                try (Response resp = http.newCall(req).execute()) {
                    String raw = resp.body() != null ? resp.body().string() : "";

                    // 429 = rate-limited on this key — rotate and retry immediately
                    if (resp.code() == 429 || raw.contains("rate_limit")) {
                        log.warn("[ GROQ ] Rate-limited (attempt {}). Rotating key...", attempt);
                        keys.rotate();
                        Thread.sleep(2000);
                        continue;
                    }

                    if (!resp.isSuccessful()) {
                        log.error("[ GROQ ] HTTP {} — {}", resp.code(), raw);
                        throw new RuntimeException("Groq API error HTTP " + resp.code());
                    }

                    // Parse the OpenAI-compatible response: choices[0].message.content
                    JsonNode root = mapper.readTree(raw);
                    return root.path("choices").get(0)
                            .path("message").path("content").asText("").trim();
                }

            } catch (RuntimeException e) {
                throw e; // propagate without wrapping — already has context
            } catch (Exception e) {
                if (attempt == MAX_RETRIES)
                    throw new RuntimeException("Groq call failed after " + MAX_RETRIES + " retries: " + e.getMessage(), e);
                log.warn("[ GROQ ] Attempt {} failed: {}. Retrying in {}s...", attempt, e.getMessage(), attempt * 3);
                try { Thread.sleep(3000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw new RuntimeException("Groq call failed after " + MAX_RETRIES + " attempts.");
    }
}
