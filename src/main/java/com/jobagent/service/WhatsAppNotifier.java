package com.jobagent.service;

import com.jobagent.config.AppConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

public class WhatsAppNotifier {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppNotifier.class);

    private static final String    API_URL = "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";
    private static final MediaType FORM    = MediaType.get("application/x-www-form-urlencoded");

    // Twilio caps a single message at 1600 chars — we chunk anything longer
    private static final int MAX_MSG_LEN = 1500;

    private static WhatsAppNotifier instance;

    private final boolean      enabled;
    private final String       sid;
    private final String       token;
    private final String       to;
    private final String       from;
    private final OkHttpClient http;

    public WhatsAppNotifier() {
        this.enabled = AppConfig.isWhatsAppEnabled();
        this.sid     = AppConfig.getTwilioAccountSid();
        this.token   = AppConfig.getTwilioAuthToken();
        this.to      = AppConfig.getWhatsAppTo();
        this.from    = AppConfig.getWhatsAppFrom();
        this.http    = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20,  TimeUnit.SECONDS)
                .build();
        instance = this;
        log.info("[ WA ] WhatsApp notifications: {}  →  {}",
                enabled ? "ENABLED" : "DISABLED", to);
    }

    public static WhatsAppNotifier getInstance() {
        if (instance == null) instance = new WhatsAppNotifier();
        return instance;
    }

    // ── Per-job apply notification ─────────────────────────────────────────────

    public void sendApplySuccess(String title, String company,
                                 String location, int matchScore, String jobUrl) {
        if (!enabled) return;

        String message = String.format(
                "✅ *Applied on Naukri!*\n\n" +
                        "📋 *%s*\n" +
                        "🏢 %s\n" +
                        "📍 %s\n" +
                        "🎯 CV Match: %d%%\n\n" +
                        "🔗 %s",
                nvl(title), nvl(company), nvl(location), matchScore, nvl(jobUrl));

        sendToTwilio(message);
    }

    // ── End-of-run summary notification ───────────────────────────────────────

    /**
     * Called once at the end of the pipeline run.
     * Sends a summary message and then sends each matched job as a separate message.
     *
     * @param totalApplied   jobs auto-applied via Easy Apply
     * @param totalWritten   jobs written to file (need manual action)
     * @param totalRejected  jobs below CV match threshold
     * @param totalSkipped   duplicate jobs skipped
     * @param durationSecs   total run time
     * @param jobDetails     list of matched job detail strings to send individually
     */
    public void sendRunSummary(int totalApplied, int totalWritten,
                               int totalRejected, int totalSkipped,
                               long durationSecs, java.util.List<String> jobDetails) {
        if (!enabled) {
            log.debug("[ WA ] Notifications disabled — skipping run summary.");
            return;
        }

        // ── 1. Summary message ────────────────────────────────────────────────
        String summary = String.format(
                "🤖 *Naukri Job Agent — Run Complete*\n\n" +
                        "✅ Easy Applied  : %d\n" +
                        "📋 Need Action   : %d\n" +
                        "❌ Low Match     : %d\n" +
                        "⏭  Duplicates    : %d\n" +
                        "⏱  Duration      : %ds\n\n" +
                        "%s",
                totalApplied, totalWritten, totalRejected, totalSkipped, durationSecs,
                totalWritten > 0
                        ? "📲 Sending " + totalWritten + " job(s) that need your action below..."
                        : "No jobs need manual action this run.");

        sendToTwilio(summary);

        // ── 2. Send each matched job that needs manual action ─────────────────
        if (jobDetails == null || jobDetails.isEmpty()) return;

        int i = 1;
        for (String detail : jobDetails) {
            // Chunk if over Twilio's limit
            if (detail.length() > MAX_MSG_LEN) {
                int parts = (int) Math.ceil(detail.length() / (double) MAX_MSG_LEN);
                for (int p = 0; p < parts; p++) {
                    int start = p * MAX_MSG_LEN;
                    int end   = Math.min(start + MAX_MSG_LEN, detail.length());
                    String chunk = (parts > 1 ? "[" + (p+1) + "/" + parts + "] " : "") + detail.substring(start, end);
                    sendToTwilio(chunk);
                    sleepMs(500);
                }
            } else {
                sendToTwilio(detail);
            }
            log.info("[ WA ] Sent job {}/{} to WhatsApp.", i++, jobDetails.size());
            sleepMs(500); // avoid Twilio rate limit
        }
    }

    // ── Core Twilio HTTP call ──────────────────────────────────────────────────

    private void sendToTwilio(String message) {
        if (sid.isBlank() || token.isBlank() || to.isBlank() || from.isBlank()) {
            log.warn("[ WA ] Missing Twilio config — check secrets TWILIO_SID, TWILIO_TOKEN, WA_TO.");
            return;
        }

        try {
            String auth = Base64.getEncoder()
                    .encodeToString((sid + ":" + token).getBytes());

            String body = "To="   + enc("whatsapp:" + to)   +
                    "&From=" + enc("whatsapp:" + from) +
                    "&Body=" + enc(message);

            Request req = new Request.Builder()
                    .url(String.format(API_URL, sid))
                    .addHeader("Authorization", "Basic " + auth)
                    .post(RequestBody.create(body, FORM))
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "";

                if (resp.code() == 201) {
                    log.info("[ WA ] ✅ WhatsApp sent to {}", to);

                } else if (respBody.contains("63016") || respBody.contains("not opted in")) {
                    log.warn("[ WA ] ❌ {} is NOT opted in to Twilio sandbox!", to);
                    log.warn("[ WA ]    Send your join code to +14155238886 on WhatsApp.");
                    log.warn("[ WA ]    Get join code: https://console.twilio.com → Messaging → Try it out");

                } else if (resp.code() == 401) {
                    log.warn("[ WA ] ❌ Auth failed — check TWILIO_SID and TWILIO_TOKEN secrets.");

                } else {
                    log.warn("[ WA ] ❌ Twilio HTTP {}  Body: {}", resp.code(), respBody);
                }
            }

        } catch (Exception e) {
            log.warn("[ WA ] Could not send WhatsApp message: {}", e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String nvl(String s) { return (s != null && !s.isBlank()) ? s : "N/A"; }

    private String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
