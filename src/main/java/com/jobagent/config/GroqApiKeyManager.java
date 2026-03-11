package com.jobagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Round-robin Groq API key pool.
 * Exhausted keys are removed; when empty throws RuntimeException.
 */
public class GroqApiKeyManager {

    private static final Logger log = LoggerFactory.getLogger(GroqApiKeyManager.class);

    private final Queue<String> keys = new LinkedList<>();

    public GroqApiKeyManager() { reset(); }

    public void reset() {
        keys.clear();
        List<String> configured = AppConfig.getGroqApiKeys();
        keys.addAll(configured);
        log.info("[ KEYS ] {} Groq API key(s) loaded.", configured.size());
    }

    /** Returns the current active key. */
    public String current() {
        if (keys.isEmpty()) throw new RuntimeException("All Groq API keys exhausted for this cycle.");
        return keys.peek();
    }

    /** Rotates to the next key (call when rate-limited). */
    public void rotate() {
        if (!keys.isEmpty()) {
            String old = keys.poll();
            log.warn("[ KEYS ] Key ending …{} rate-limited — rotating.", old.substring(Math.max(0, old.length()-6)));
        }
        if (keys.isEmpty()) throw new RuntimeException("All Groq API keys exhausted for this cycle.");
        log.info("[ KEYS ] Now using key ending …{}", current().substring(Math.max(0, current().length()-6)));
    }

    public boolean hasKeys() { return !keys.isEmpty(); }
}
