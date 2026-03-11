package com.jobagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.jobagent.config.FilterConfig;

/**
 * Central configuration — reads application.properties once at startup.
 *
 * LOADING ORDER (first found wins):
 *   1. ./application.properties  — file in the current working directory
 *                                  (used by GitHub Actions: written next to jar at runtime)
 *   2. classpath:application.properties — bundled inside the jar
 *                                  (used for local runs where file is in src/main/resources)
 *
 * SECRETS OVERRIDE (GitHub Actions / CircleCI):
 *   After the properties file is loaded, each SENSITIVE key is overridden
 *   by a matching environment variable if present.
 *
 *   Mapping (env var → property key):
 *     NAUKRI_USERNAME       → naukri.username
 *     NAUKRI_PASSWORD       → naukri.password
 *     GROQ_API_KEYS         → groq.api.keys
 *     TWILIO_ACCOUNT_SID    → twilio.account.sid
 *     TWILIO_AUTH_TOKEN     → twilio.auth.token
 *     WHATSAPP_TO           → whatsapp.to
 *     WHATSAPP_FROM         → whatsapp.from
 *
 *   This means:
 *   - Local dev: just edit application.properties normally — no env vars needed.
 *   - GitHub Actions / CircleCI: set secrets as env vars — they override the file.
 *   - The committed application.properties can have empty/placeholder values safely.
 */
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final Properties PROPS = new Properties();

    // Runtime-mutable keyword list (set by CVReaderAgent when getFromCv=true)
    private static List<String> runtimeKeywords = null;

    // Runtime-mutable filter selections (set by NaukriFilterAgent when filters.getFromCv=true)
    private static FilterConfig runtimeFilters = null;

    static {
        // ── Step 1: Load properties file ──────────────────────────────────────

        // Priority 1: ./application.properties in working directory
        Path externalFile = Paths.get("application.properties");
        if (Files.exists(externalFile)) {
            try (InputStream is = Files.newInputStream(externalFile)) {
                PROPS.load(is);
                log.info("[ CONFIG ] Loaded from working directory: {}", externalFile.toAbsolutePath());
            } catch (Exception e) {
                throw new ExceptionInInitializerError(
                        "Failed to load external application.properties: " + e.getMessage());
            }
        } else {
            // Priority 2: classpath (bundled inside the jar)
            try (InputStream is = AppConfig.class.getClassLoader()
                    .getResourceAsStream("application.properties")) {
                if (is == null) throw new IllegalStateException(
                        "application.properties not found — neither in working directory nor on classpath");
                PROPS.load(is);
                log.info("[ CONFIG ] Loaded from classpath (bundled jar).");
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        // ── Step 2: Fallback to environment variables for secrets ─────────────
        //
        // LOGIC:
        //   - If the property value is present AND not blank AND not "NA"
        //     → use it directly (local dev with real values in the file)
        //   - If the property value is missing, blank, or "NA"
        //     → read from the matching environment variable (GitHub Actions / CI)
        //
        // This means:
        //   Local dev  : set real values in application.properties → env vars ignored
        //   GitHub CI  : leave properties as NA → secrets injected from env vars
        //
        fallbackToEnv("naukri.username",    "NAUKRI_USERNAME");
        fallbackToEnv("naukri.password",    "NAUKRI_PASSWORD");
        fallbackToEnv("groq.api.keys",      "GROQ_API_KEYS");
        fallbackToEnv("twilio.account.sid", "TWILIO_ACCOUNT_SID");
        fallbackToEnv("twilio.auth.token",  "TWILIO_AUTH_TOKEN");
        fallbackToEnv("whatsapp.to",        "WHATSAPP_TO");
        fallbackToEnv("whatsapp.from",      "WHATSAPP_FROM");
    }

    /**
     * Reads the property value. If it is missing, blank, or exactly "NA",
     * falls back to the given environment variable.
     *
     * Logs the source used (key name only — value is NEVER logged).
     */
    private static void fallbackToEnv(String propKey, String envVar) {
        String propVal = PROPS.getProperty(propKey, "").trim();
        boolean isNA   = propVal.isEmpty() || propVal.equalsIgnoreCase("NA");

        if (isNA) {
            String envVal = System.getenv(envVar);
            if (envVal != null && !envVal.isBlank()) {
                PROPS.setProperty(propKey, envVal.trim());
                log.info("[ CONFIG ] '{}' is NA in properties — loaded from env var {}.", propKey, envVar);
            } else {
                log.warn("[ CONFIG ] '{}' is NA in properties AND env var {} is not set!", propKey, envVar);
            }
        } else {
            log.info("[ CONFIG ] '{}' loaded from application.properties.", propKey);
        }
    }

    // ── Naukri credentials ────────────────────────────────────
    public static String getNaukriUsername() { return require("naukri.username"); }
    public static String getNaukriPassword() { return require("naukri.password"); }

    // ── Groq ──────────────────────────────────────────────────
    public static List<String> getGroqApiKeys() { return splitCsv(require("groq.api.keys")); }
    public static String getGroqModel()         { return get("groq.model", "llama-3.3-70b-versatile"); }

    // ── CV ────────────────────────────────────────────────────
    public static String getCvFilePath() { return get("cv.file.path", "./cv.pdf"); }

    // ── Schedule ──────────────────────────────────────────────
    public static int getRunIntervalHours() {
        return Integer.parseInt(get("run.interval.hours", "3"));
    }

    // ── Job search ────────────────────────────────────────────
    public static List<String> getJobSearchKeywords() {
        if (runtimeKeywords != null) return Collections.unmodifiableList(runtimeKeywords);
        return splitCsv(get("job.search.keywords", "QA Engineer"));
    }
    public static void setJobSearchKeywords(List<String> kw) { runtimeKeywords = new ArrayList<>(kw); }
    public static void resetRuntimeKeywords()                { runtimeKeywords = null; }

    public static String getJobSearchLocation()        { return get("job.search.location", "India"); }
    public static int    getCandidateExperienceYears() {
        return Integer.parseInt(get("candidate.experience.years", "0"));
    }
    public static int    getJobSearchMaxPages()        { return Integer.parseInt(get("job.search.max.pages", "3")); }
    public static String getTrackerFile()              { return get("job.search.tracker.file", "naukri_seen_jobs.log"); }

    // ── Keywords from CV ──────────────────────────────────────
    public static boolean isGetKeywordsFromCv() {
        return Boolean.parseBoolean(get("job.search.keywords.getFromCv", "false"));
    }
    public static int getJobSearchKeywordsCount() {
        return Integer.parseInt(get("job.search.keywords.count", "10"));
    }

    // ── Filter source control ─────────────────────────────────
    public static boolean isFiltersEnabled() {
        return Boolean.parseBoolean(get("filters.apply", "true"));
    }
    public static boolean isGetFiltersFromCv() {
        return Boolean.parseBoolean(get("filters.getFromCv", "false"));
    }
    public static void setRuntimeFilters(FilterConfig fc) { runtimeFilters = fc; }
    public static void resetRuntimeFilters()              { runtimeFilters = null; }
    public static FilterConfig getFilters() {
        if (runtimeFilters != null) return runtimeFilters;
        return FilterConfig.fromProperties(PROPS);
    }

    // ── Filter timing ─────────────────────────────────────────
    public static int getFilterClickDelayMs() {
        return Integer.parseInt(get("filters.click.delay.ms", "1200"));
    }
    public static int getFilterClickRetries() {
        return Integer.parseInt(get("filters.click.retries", "3"));
    }

    // ── Match threshold ───────────────────────────────────────
    public static double getJobMatchThreshold() {
        return Integer.parseInt(get("job.match.threshold.percent", "65")) / 100.0;
    }

    // ── Apply behaviour ───────────────────────────────────────
    public static boolean isApplyForJob()         { return Boolean.parseBoolean(get("applyForJob", "false")); }
    public static String  getJobLinksOutputFile() { return get("job.links.output.file", "naukri_job_matches.txt"); }

    // ── Console output ────────────────────────────────────────
    public static boolean isLogRejected() { return Boolean.parseBoolean(get("job.log.rejected", "true")); }

    // ── WhatsApp via Twilio ───────────────────────────────────
    public static boolean isWhatsAppEnabled()   { return Boolean.parseBoolean(get("whatsapp.enabled",    "false")); }
    public static String  getTwilioAccountSid() { return get("twilio.account.sid", ""); }
    public static String  getTwilioAuthToken()  { return get("twilio.auth.token",  ""); }
    public static String  getWhatsAppTo()       { return get("whatsapp.to",        ""); }
    public static String  getWhatsAppFrom()     { return get("whatsapp.from",      "+14155238886"); }

    // ── Browser ───────────────────────────────────────────────
    public static boolean isChromeHeadless() { return Boolean.parseBoolean(get("chrome.headless", "false")); }

    // ── Parallel ──────────────────────────────────────────────
    public static boolean isParallelEnabled() {
        return Boolean.parseBoolean(get("parallel.enabled", "false"));
    }
    public static int getParallelBrowsers() {
        return Math.max(2, Integer.parseInt(get("parallel.browsers", "2")));
    }

    // ── Internal helpers ──────────────────────────────────────
    private static String require(String key) {
        String v = PROPS.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing config key: " + key);
        return v.trim();
    }
    static String get(String key, String def) {
        String v = PROPS.getProperty(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }
    public static List<String> splitCsv(String csv) {
        List<String> list = new ArrayList<>();
        if (csv == null || csv.isBlank()) return list;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) list.add(t);
        }
        return list;
    }
}
