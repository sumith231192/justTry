package com.jobagent;

import com.jobagent.agent.*;
import com.jobagent.config.AppConfig;
import com.jobagent.config.GroqApiKeyManager;
import com.jobagent.service.GroqClient;
import com.jobagent.service.JobTrackerService;
import com.jobagent.service.OutputWriterService;
import com.jobagent.service.WhatsAppNotifier;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║          Naukri Job Agent  v2.0  —  Orchestrator                   ║
 * ╠══════════════════════════════════════════════════════════════════════╣
 * ║  parallel.enabled=false  →  Sequential: 1 browser, all keywords,   ║
 * ║                              all pages, one by one.                 ║
 * ║                                                                     ║
 * ║  parallel.enabled=true   →  Page-parallel: for EACH keyword,       ║
 * ║    browsers open together and split that keyword's pages between    ║
 * ║    them. When all finish, all move to the next keyword together.    ║
 * ║                                                                     ║
 * ║  Browser count auto-capped:                                         ║
 * ║    effectiveBrowsers = min(parallel.browsers, maxPages)             ║
 * ║    e.g. parallel.browsers=4, maxPages=2  →  only 2 browsers open   ║
 * ║                                                                     ║
 * ║  Page distribution (round-robin interleave):                        ║
 * ║    pages=10, browsers=3:                                            ║
 * ║      Worker-1 → [1, 4, 7, 10]                                      ║
 * ║      Worker-2 → [2, 5, 8]                                          ║
 * ║      Worker-3 → [3, 6, 9]                                          ║
 * ║                                                                     ║
 * ║  Duplicate prevention:                                              ║
 * ║    Each jobId is atomically marked the MOMENT its card is seen      ║
 * ║    on the SRP (before scoring). Written to disk immediately.        ║
 * ║    Any parallel worker or restart encountering the same jobId       ║
 * ║    skips it instantly.                                              ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        printBanner();
        boolean once = hasArg(args, "--once");
        if (once) {
            log.info("Mode: ONE-SHOT");
            runPipeline();
        } else {
            int hours = AppConfig.getRunIntervalHours();
            log.info("Mode: DAEMON — every {} hour(s).", hours);
            runDaemon(hours);
        }
    }

    // ── Daemon ────────────────────────────────────────────────────────────────

    private static void runDaemon(int intervalHours) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try { runPipeline(); }
            catch (Exception e) { log.error("Pipeline error: {}", e.getMessage(), e); }
        }, 0, intervalHours, TimeUnit.HOURS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown."); scheduler.shutdown();
        }));
        try { Thread.currentThread().join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    static void runPipeline() {
        long t0 = System.currentTimeMillis();
        log.info("━━━━━━━━━━━━━━━━  Pipeline START  ━━━━━━━━━━━━━━━━");

        // ── Shared services ───────────────────────────────────────────────
        GroqApiKeyManager   keys         = new GroqApiKeyManager();
        keys.reset();
        AppConfig.resetRuntimeKeywords();
        AppConfig.resetRuntimeFilters();
        GroqClient          groq         = new GroqClient(keys);
        JobTrackerService   tracker      = new JobTrackerService();
        OutputWriterService writer       = new OutputWriterService();
        new WhatsAppNotifier();   // registers singleton — MUST be before JobScoringAgent

        // Merged keyword stats — all workers write into this shared map
        // ConcurrentHashMap: parallel workers may computeIfAbsent for the same key
        java.util.concurrent.ConcurrentHashMap<String, com.jobagent.model.KeywordStats>
                mergedStats = new java.util.concurrent.ConcurrentHashMap<>();

        // ── Read CV ───────────────────────────────────────────────────────
        CVReaderAgent cvReader = new CVReaderAgent(groq);
        String cvText;
        try {
            log.info("[ CV ] Reading CV from: {}", AppConfig.getCvFilePath());
            cvText = cvReader.readCv();
            log.info("[ CV ] {} chars loaded.", cvText.length());
        } catch (Exception e) {
            log.error("[ CV ] Cannot read CV — aborting: {}", e.getMessage()); return;
        }

        // ── Infer roles ───────────────────────────────────────────────────
        try {
            log.info("[ CV ] Inferring roles...");
            log.info("[ CV ] {}", cvReader.inferRolesFromCv(cvText));
        } catch (Exception e) { log.warn("[ CV ] Role inference failed: {}", e.getMessage()); }

        // ── Keywords ──────────────────────────────────────────────────────
        if (AppConfig.isGetKeywordsFromCv()) {
            try {
                int kwCount = AppConfig.getJobSearchKeywordsCount();
                log.info("[ KW ] Generating {} keywords from CV...", kwCount);
                List<String> kw = cvReader.generateKeywordsFromCv(kwCount);
                AppConfig.setJobSearchKeywords(kw);
                for (int i = 0; i < kw.size(); i++) log.info("[ KW ]   {}. {}", i+1, kw.get(i));
            } catch (Exception e) {
                log.warn("[ KW ] Generation failed — using static list: {}", e.getMessage());
            }
        } else {
            List<String> kw = AppConfig.getJobSearchKeywords();
            log.info("[ KW ] Using {} static keyword(s).", kw.size());
            for (int i = 0; i < kw.size(); i++) log.info("[ KW ]   {}. {}", i+1, kw.get(i));
        }

        List<String> allKeywords = AppConfig.getJobSearchKeywords();
        int          maxPages    = AppConfig.getJobSearchMaxPages();
        boolean      parallel    = AppConfig.isParallelEnabled();

        // ── SEQUENTIAL MODE ───────────────────────────────────────────────
        if (!parallel) {
            // Build full page list [1, 2, ..., maxPages] for the single worker
            List<Integer> allPages = new ArrayList<>();
            for (int p = 1; p <= maxPages; p++) allPages.add(p);

            log.info("[ MODE ] Sequential — 1 browser, {} keyword(s), pages {} each.",
                    allKeywords.size(), allPages);
            runWorker(1, allKeywords, allPages, cvText,
                    groq, tracker, writer, mergedStats);

            // ── PARALLEL MODE ─────────────────────────────────────────────────
        } else {
            // Cap browsers to maxPages — pointless to open more browsers than pages
            int configuredBrowsers = AppConfig.getParallelBrowsers();
            int browsers = Math.min(configuredBrowsers, maxPages);
            if (browsers < configuredBrowsers) {
                log.info("[ MODE ] parallel.browsers={} capped to {} (= job.search.max.pages).",
                        configuredBrowsers, browsers);
            }

            // Distribute pages round-robin:
            //   maxPages=10, browsers=3 →
            //     Worker-1: [1, 4, 7, 10]
            //     Worker-2: [2, 5, 8]
            //     Worker-3: [3, 6, 9]
            // Every worker runs these same page numbers for EVERY keyword.
            List<List<Integer>> pageSlices =
                    NaukriSearchAgent.distributePages(maxPages, browsers);

            log.info("[ MODE ] Parallel — {} browser(s), {} keyword(s), {} max page(s).",
                    browsers, allKeywords.size(), maxPages);
            for (int i = 0; i < pageSlices.size(); i++)
                log.info("[ MODE ]   Worker-{} → pages {} for every keyword", i+1, pageSlices.get(i));

            // Launch N workers — each logs in ONCE, then loops through ALL keywords
            // doing its assigned pages for each keyword before moving to the next.
            // No keyword is skipped. All browsers cover all keywords.
            ExecutorService pool = Executors.newFixedThreadPool(browsers, r -> {
                Thread t = new Thread(r); t.setDaemon(false); return t;
            });

            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < browsers; w++) {
                final int           workerId = w + 1;
                final List<Integer> myPages  = pageSlices.get(w);
                final List<String>  allKw    = allKeywords;
                final String        finalCv  = cvText;

                futures.add(pool.submit(() ->
                        runWorker(workerId, allKw, myPages, finalCv,
                                groq, tracker, writer, mergedStats)
                ));
            }

            pool.shutdown();
            for (Future<?> f : futures) {
                try { f.get(); }
                catch (ExecutionException e) {
                    log.error("[ W ] Worker crashed: {}", e.getCause().getMessage(), e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // ── Summary ───────────────────────────────────────────────────────
        long elapsed = (System.currentTimeMillis() - t0) / 1000;
        String mode  = parallel
                ? "Parallel (" + Math.min(AppConfig.getParallelBrowsers(), maxPages) + " browsers)"
                : "Sequential (1 browser)";

        // Grand totals — summed across all keywords
        int totalApplied   = 0, totalExternal  = 0,
                totalMatchOnly = 0, totalRejected  = 0, totalSkipped = 0;
        for (com.jobagent.model.KeywordStats s : mergedStats.values()) {
            totalApplied   += s.getApplied();
            totalExternal  += s.getExternal();
            totalMatchOnly += s.getLinkOnly();
            totalRejected  += s.getRejected();
            totalSkipped   += s.getSkipped();
        }
        int totalWritten = totalExternal + totalMatchOnly;

        // Log per-keyword table
        log.info("━━━━━━━━━━━━━━━━  Pipeline END  ━━━━━━━━━━━━━━━━━");
        log.info("  Mode : {}", mode);
        log.info("  ┌─────────────────────────────────────────────────────────────────────────┐");
        log.info("  │ {:50s} {:>7} {:>7} {:>7} {:>7} {:>7} │",
                "Keyword", "Applied", "Txt", "Skipped", "Reject", "Total");
        log.info("  ├─────────────────────────────────────────────────────────────────────────┤");
        for (String kw : allKeywords) {
            com.jobagent.model.KeywordStats s = mergedStats.get(kw);
            if (s == null) continue;
            log.info("  │ {:50s} {:>7} {:>7} {:>7} {:>7} {:>7} │",
                    truncKw(kw, 50),
                    s.getApplied(),
                    s.getTotalWrittenToFile(),
                    s.getSkipped(),
                    s.getRejected(),
                    s.getTotalProcessed());
        }
        log.info("  ├─────────────────────────────────────────────────────────────────────────┤");
        log.info("  │ {:50s} {:>7} {:>7} {:>7} {:>7} {:>7} │",
                "TOTAL",
                totalApplied, totalWritten, totalSkipped, totalRejected,
                totalApplied + totalWritten + totalRejected);
        log.info("  └─────────────────────────────────────────────────────────────────────────┘");
        log.info("  Applied (Easy Apply done)   : {}", totalApplied);
        log.info("  Written to .txt (you act)   : {}  → {}", totalWritten, AppConfig.getJobLinksOutputFile());
        log.info("  Skipped (duplicates)        : {}", totalSkipped);
        log.info("  Rejected (below threshold)  : {}", totalRejected);
        log.info("  Duration                    : {}s", elapsed);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        writer.writeSummary(allKeywords, mergedStats,
                totalApplied, totalWritten, totalSkipped, totalRejected, elapsed, mode);

        // ── WhatsApp end-of-run summary ───────────────────────────────────────
        // Read the output file and send each job entry as a separate WhatsApp message.
        // This runs after writeSummary so the file is fully written.
        try {
            java.util.List<String> jobMessages = parseJobsFromOutputFile(
                    AppConfig.getJobLinksOutputFile());
            WhatsAppNotifier.getInstance().sendRunSummary(
                    totalApplied, totalWritten, totalRejected, totalSkipped,
                    elapsed, jobMessages);
        } catch (Exception e) {
            log.warn("[ WA ] Could not send WhatsApp summary: {}", e.getMessage());
        }
    }

    /**
     * Parses the naukri_job_matches.txt file and returns each job block
     * as a formatted WhatsApp message string.
     *
     * Job blocks are separated by the ─────── divider lines.
     * Each block is reformatted into a compact WhatsApp-friendly message.
     */
    private static java.util.List<String> parseJobsFromOutputFile(String filePath) {
        java.util.List<String> messages = new java.util.ArrayList<>();
        java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            log.warn("[ WA ] Output file not found: {}", filePath);
            return messages;
        }

        try {
            String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
            // Split on the ──── divider lines that separate job blocks
            String[] blocks = content.split("─{10,}");

            for (String block : blocks) {
                block = block.trim();
                if (block.isEmpty()) continue;
                // Skip header blocks and summary blocks
                if (block.contains("Naukri Job Agent") ||
                        block.contains("RUN SUMMARY") ||
                        block.contains("HOW TO ACTION") ||
                        block.contains("Easy Apply done") ||
                        block.contains("Only jobs where")) continue;
                // Must contain a Title line to be a valid job block
                if (!block.contains("Title")) continue;

                // Build a compact WhatsApp message from the block
                StringBuilder msg = new StringBuilder();
                for (String line : block.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    // Pick the most useful fields only
                    if (line.startsWith("ACTION")     ||
                            line.startsWith("Title")      ||
                            line.startsWith("Company")    ||
                            line.startsWith("Location")   ||
                            line.startsWith("Experience") ||
                            line.startsWith("Salary")     ||
                            line.startsWith("CV Match")   ||
                            line.startsWith("Naukri URL") ||
                            line.startsWith("⬈ APPLY HERE") ||
                            line.startsWith("Note")) {
                        msg.append(line).append("\n");
                    }
                }

                String result = msg.toString().trim();
                if (!result.isEmpty()) {
                    messages.add("📌 *Job Match*\n" + result);
                }
            }
        } catch (Exception e) {
            log.warn("[ WA ] Could not parse output file: {}", e.getMessage());
        }

        log.info("[ WA ] Parsed {} job(s) from output file to send via WhatsApp.", messages.size());
        return messages;
    }

    // ── Worker (one Chrome session) ───────────────────────────────────────────

    /**
     * One worker = one Chrome session, open for the entire run.
     *
     * Sequential mode  (parallel.enabled=false):
     *   allKeywords = full list,  pageNumbers = all pages [1..maxPages]
     *   searchAllKeywords() loops keywords × pages sequentially.
     *
     * Parallel mode  (parallel.enabled=true):
     *   allKeywords = full list,  pageNumbers = this worker's page slice
     *   searchAllKeywordsWithPages() loops ALL keywords; for each keyword
     *   it processes only the pages in pageNumbers.
     *
     * Login happens ONCE at the start. The browser stays alive for all keywords.
     * The session is quit in the finally block after all keywords are done.
     */
    private static void runWorker(
            int workerId,
            List<String> allKeywords,
            List<Integer> pageNumbers,
            String cvText,
            GroqClient groq,
            JobTrackerService tracker,
            OutputWriterService writer,
            java.util.concurrent.ConcurrentHashMap<String, com.jobagent.model.KeywordStats> mergedStats) {

        String tag = String.format("[ W%d ]", workerId);
        log.info("{} Starting — {} keyword(s), pages {} each", tag, allKeywords.size(), pageNumbers);

        NaukriLoginAgent loginAgent = new NaukriLoginAgent();
        SessionKeepAlive keepAlive  = null;
        JobScoringAgent  scorer     = new JobScoringAgent(groq, writer);
        scorer.setCvText(cvText);

        try {
            loginAgent.loginAndStart();
            log.info("{} ✓ Logged in.", tag);

            keepAlive = new SessionKeepAlive(loginAgent.getDriver());
            keepAlive.start();

            NaukriSearchAgent search = new NaukriSearchAgent(cvText, tracker);
            search.setDriver(loginAgent.getDriver(), loginAgent.getWait());
            search.linkFilterAgent();
            scorer.setDriver(loginAgent.getDriver(), loginAgent.getWait());
            // Wire scorer directly so NaukriSearchAgent calls setActiveStats() per keyword
            search.setScorer(scorer);

            search.searchAllKeywordsWithPages(allKeywords, pageNumbers);

            // Copy this worker's per-keyword stats into the pipeline-level map.
            // NaukriSearchAgent.allStats uses computeIfAbsent with the keyword as key,
            // so parallel workers that share the same keyword already received and updated
            // the SAME KeywordStats object — no double-counting, just register it.
            mergedStats.putAll(search.getAllStats());

        } catch (Exception e) {
            log.error("{} Fatal error: {}", tag, e.getMessage(), e);
            ScreenshotHelper.captureOnError(loginAgent.getDriver(),
                    "worker" + workerId + "_error", e);
        } finally {
            if (keepAlive != null) keepAlive.stop();
            loginAgent.quitSession();
            log.info("{} Done.", tag);
        }
    }

    private static String truncKw(String kw, int max) {
        return kw.length() <= max ? kw : kw.substring(0, max - 1) + "…";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static boolean hasArg(String[] args, String flag) {
        for (String a : args) if (flag.equalsIgnoreCase(a)) return true;
        return false;
    }

    private static void printBanner() {
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║     Naukri Job Agent  v2.0  — AI-powered             ║");
        log.info("║  CV → Keywords → Page-Parallel → Score → Apply      ║");
        log.info("╚══════════════════════════════════════════════════════╝");
    }
}
