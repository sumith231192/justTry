package com.jobagent.agent;

import com.jobagent.config.AppConfig;
import com.jobagent.model.JobListing;
import com.jobagent.model.KeywordStats;
import com.jobagent.service.GroqClient;
import com.jobagent.service.OutputWriterService;
import com.jobagent.service.WhatsAppNotifier;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JobScoringAgent — called per-job by NaukriSearchAgent.
 *
 * CONTRACT with NaukriSearchAgent:
 *   - When process(job) is called, the browser is already on the job detail page.
 *   - job.description is already populated by NaukriSearchAgent.openJobDetailPage().
 *   - After process() returns, NaukriSearchAgent navigates back to SRP.
 *
 * Responsibilities:
 *   1. Score CV vs JD via Groq (0–100).
 *   2. If below threshold → log + skip.
 *   3. If at/above threshold → attempt apply (or write link if applyForJob=false).
 *   4. Detect apply outcome (Easy Apply success / external redirect / unclear).
 *   5. Update both global counters AND the active KeywordStats object.
 */
public class JobScoringAgent extends BrowserAgent {

    private final GroqClient          groq;
    private final OutputWriterService writer;
    private final WhatsAppNotifier    whatsapp;
    private       String              cvText;

    // Global run-level counters (aggregated across all keywords)
    private final AtomicInteger applied   = new AtomicInteger(0);
    private final AtomicInteger external  = new AtomicInteger(0);
    private final AtomicInteger rejected  = new AtomicInteger(0);
    private final AtomicInteger matched   = new AtomicInteger(0);
    private final AtomicInteger matchOnly = new AtomicInteger(0);

    // Per-keyword stats — set by NaukriSearchAgent before each keyword's jobs are processed
    private volatile KeywordStats activeStats = null;

    /**
     * Parallel constructor — accepts a shared OutputWriterService so all workers
     * write matched jobs to the same output file without duplication.
     */
    public JobScoringAgent(GroqClient groq, OutputWriterService sharedWriter) {
        this.groq     = groq;
        this.writer   = sharedWriter;
        this.whatsapp = WhatsAppNotifier.getInstance();
    }

    /**
     * Single-browser constructor (legacy / parallel.browsers=1).
     * Creates its own OutputWriterService.
     */
    public JobScoringAgent(GroqClient groq) {
        this(groq, new OutputWriterService());
    }

    public void setCvText(String cvText) { this.cvText = cvText; }
    public OutputWriterService getWriter() { return writer; }

    /**
     * Called by NaukriSearchAgent at the start of each keyword.
     * All subsequent process() calls update this stats object until the next setActiveStats().
     */
    public void setActiveStats(KeywordStats stats) { this.activeStats = stats; }

    // ── Entry point (called per-job by NaukriSearchAgent) ────────────────────

    /**
     * Called AFTER NaukriSearchAgent has:
     *   1. Navigated to the job detail page.
     *   2. Extracted the full JD into job.description.
     *
     * This method MUST NOT call driver.get() — we are already on the right page.
     */
    public void process(JobListing job) {

        // Safety net: if description is still missing try a quick re-extract
        if (job.getDescription() == null || job.getDescription().isBlank()) {
            log.debug("[ SCORE ] Description empty for {} — attempting page re-extract.", job.getTitle());
            String jdText = extractJdFromCurrentPage();
            if (!jdText.isBlank()) {
                job.setDescription(jdText);
                log.debug("[ SCORE ] Re-extract succeeded: {} chars.", jdText.length());
            } else {
                log.warn("[ SCORE ] Still no JD for {} — scoring from title/meta only.", job.getTitle());
            }
        }

        // 1. Score
        int score     = scoreJobVsCv(job);
        int threshold = (int)(AppConfig.getJobMatchThreshold() * 100);
        int kwMatched = countKeywordMatches(job);
        double kwPct  = kwPercent(kwMatched);

        job.setMatchScore(score);
        job.setKeywordsMatched(kwMatched);
        job.setKeywordsMatchPct(kwPct);

        log.info("  [{}%] {} @ {}  (kw: {}/{})  threshold: {}%",
                score, job.getTitle(), job.getCompany(),
                kwMatched, AppConfig.getJobSearchKeywords().size(), threshold);

        // 2. Below threshold → skip (NOT written to file)
        if (score < threshold) {
            rejected.incrementAndGet();
            if (activeStats != null) activeStats.incRejected();
            if (AppConfig.isLogRejected()) {
                log.info("  ❌ Below threshold ({} < {}) — skipping.", score, threshold);
            }
            return;
        }

        matched.incrementAndGet();
        log.info("  ✅ MATCH! score={}, kw={}/{}", score, kwMatched,
                AppConfig.getJobSearchKeywords().size());

        // 3. applyForJob=false → save link only, no auto-apply
        if (!AppConfig.isApplyForJob()) {
            matchOnly.incrementAndGet();
            if (activeStats != null) activeStats.incLinkOnly();
            log.info("  📋 NOT APPLIED — applyForJob=false | \"{}\" @ {}", job.getTitle(), job.getCompany());
            writer.writeExternal(job,
                    "applyForJob=false in application.properties — visit Naukri URL to apply manually");
            return;
        }

        // 4. Attempt apply on the currently open job detail page
        log.info("  🚀 APPLYING — \"{}\" @ {}", job.getTitle(), job.getCompany());
        applyOnCurrentPage(job);
    }

    // ── Quick JD re-extractor (fallback) ─────────────────────────────────────

    private String extractJdFromCurrentPage() {
        String[] selectors = {
                ".dang-inner-html", ".jd-desc", ".job-desc",
                "#job_description", ".jobDescriptionText",
                "section.job-desc", "[class*='jd-desc']",
                "[class*='jobDesc']", ".description-section", ".description"
        };
        for (String sel : selectors) {
            try {
                String text = driver.findElement(By.cssSelector(sel)).getText().trim();
                if (text.length() > 80)
                    return text.substring(0, Math.min(3000, text.length()));
            } catch (Exception ignored) {}
        }
        return "";
    }

    // ── CV ↔ JD Groq scorer ───────────────────────────────────────────────────

    private int scoreJobVsCv(JobListing job) {
        // Title is intentionally excluded from scoring.
        // Job titles vary wildly between organisations for the same actual role
        // (e.g. "QA Lead" vs "SDET" vs "Test Automation Architect").
        // Matching must be driven entirely by the JD body, required skills,
        // responsibilities, and experience level — not the label on the tin.

        String jd = (job.getDescription() != null && !job.getDescription().isBlank())
                ? job.getDescription().substring(0, Math.min(3000, job.getDescription().length()))
                : ""; // pure fallback — no title injected

        // If JD is completely empty after all extraction attempts, we cannot score
        // meaningfully. Return 0 so the job is skipped rather than guessed at.
        if (jd.isBlank()) {
            log.warn("[ SCORE ] No JD content available for \"{}\" — scoring 0.", job.getTitle());
            return 0;
        }

        String cv = cvText != null ? cvText.substring(0, Math.min(4000, cvText.length())) : "";

        String systemPrompt =
                "You are a strict technical recruitment evaluator.\n" +
                        "Your ONLY job is to compare a candidate's CV against a job description " +
                        "and return a match score.\n" +
                        "Rules:\n" +
                        "  - Base the score ENTIRELY on the JOB DESCRIPTION content: required skills, " +
                        "tools, responsibilities, domain knowledge, and years of experience.\n" +
                        "  - DO NOT factor in the job title — titles vary across organisations " +
                        "for identical roles.\n" +
                        "  - Focus on: technical skills match, domain overlap, seniority level, " +
                        "tools/frameworks mentioned in the JD vs present in the CV.\n" +
                        "  - A high score (80-100) means the CV strongly covers the JD's technical " +
                        "requirements and responsibilities.\n" +
                        "  - A mid score (50-79) means partial overlap — some key skills match.\n" +
                        "  - A low score (0-49) means the CV does not meet the JD requirements.\n" +
                        "Return ONLY a single integer 0-100. No text, no explanation, no punctuation.";

        String userPrompt =
                "JOB DESCRIPTION (skills, responsibilities, requirements):\n" +
                        jd +
                        "\n\n---\n\n" +
                        "CANDIDATE CV:\n" +
                        cv +
                        "\n\n---\n\n" +
                        "Score (0-100):";

        try {
            String raw = groq.chat(systemPrompt, userPrompt);
            int score  = Integer.parseInt(raw.replaceAll("[^0-9]", "").trim());
            return Math.min(100, Math.max(0, score));
        } catch (Exception e) {
            log.warn("[ SCORE ] Score parse failed for \"{}\" — defaulting to 50", job.getTitle());
            return 50;
        }
    }

    private int countKeywordMatches(JobListing job) {
        // Match against JD content only — not the title — for the same reason
        // as the scoring prompt: titles are unreliable labels, the JD body is ground truth.
        String hay = nvl(job.getDescription()).toLowerCase();
        int c = 0;
        for (String kw : AppConfig.getJobSearchKeywords())
            if (hay.contains(kw.toLowerCase())) c++;
        return c;
    }

    private double kwPercent(int matched) {
        int total = AppConfig.getJobSearchKeywords().size();
        if (total == 0) return 0;
        return Math.round((matched * 100.0 / total) * 10) / 10.0;
    }

    // ── Apply on the currently open job detail page ───────────────────────────

    private void applyOnCurrentPage(JobListing job) {
        String      naukriWindow  = driver.getWindowHandle();
        Set<String> windowsBefore = driver.getWindowHandles();

        WebElement applyBtn = findApplyButton();
        if (applyBtn == null) {
            log.warn("[ APPLY ] No Apply button found for: {}", job.getTitle());
            writer.writeExternal(job,
                    "Apply button not found on job detail page — visit Naukri URL to apply manually");
            return;
        }

        log.info("[ APPLY ] Clicking Apply for: {}", job.getTitle());
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center'});", applyBtn);
            sleep(600);
            applyBtn.click();
            sleep(3500);
        } catch (Exception e) {
            log.warn("[ APPLY ] Click failed: {}", e.getMessage());
            writer.writeExternal(job, "Apply button click failed: " + e.getMessage());
            return;
        }

        // ── Case A: new tab opened → external site ───────────────────────
        Set<String> windowsAfter = driver.getWindowHandles();
        if (windowsAfter.size() > windowsBefore.size()) {
            for (String handle : windowsAfter) {
                if (!windowsBefore.contains(handle)) {
                    driver.switchTo().window(handle);
                    String extUrl = currentUrl();
                    log.info("  ⬈  NOT APPLIED (auto) — external site opened | \"{}\" @ {} | URL: {}",
                            job.getTitle(), job.getCompany(), extUrl);
                    job.setExternalUrl(extUrl);
                    writer.writeExternal(job,
                            "Apply opened external company site — apply manually at ⬈ APPLY HERE");
                    external.incrementAndGet();
                    if (activeStats != null) activeStats.incExternal();
                    driver.close();
                    driver.switchTo().window(naukriWindow);
                    return;
                }
            }
        }

        // ── Case B: same tab redirected off naukri.com ───────────────────
        String newUrl = currentUrl();
        if (newUrl != null && !newUrl.contains("naukri.com")) {
            log.info("  ⬈  NOT APPLIED (auto) — redirected off-site | \"{}\" @ {} | URL: {}",
                    job.getTitle(), job.getCompany(), newUrl);
            job.setExternalUrl(newUrl);
            writer.writeExternal(job,
                    "Apply redirected to external company site — apply manually at ⬈ APPLY HERE");
            external.incrementAndGet();
            if (activeStats != null) activeStats.incExternal();
            driver.navigate().back();
            sleep(1500);
            return;
        }

        // ── Case C: still on Naukri — handle confirmation modal ──────────
        handleConfirmationModal();

        // ── Case D: detect ACP (After-Apply Confirmation Page) ───────────
        sleep(1500);
        if (isApplySuccess()) {
            applied.incrementAndGet();
            if (activeStats != null) activeStats.incApplied();
            log.info("  ✅ APPLIED — Easy Apply success | \"{}\" @ {}", job.getTitle(), job.getCompany());

            // ── Send WhatsApp notification ────────────────────────────────────
            whatsapp.sendApplySuccess(
                    job.getTitle(),
                    job.getCompany(),
                    job.getLocation(),
                    job.getMatchScore(),
                    job.getApplyUrl()
            );

        } else {
            // Unclear outcome — write to file for manual check (treat as external/needs-action)
            external.incrementAndGet();
            if (activeStats != null) activeStats.incExternal();
            log.info("  ⚠  NOT APPLIED (unclear) — could not confirm submission | \"{}\" @ {}",
                    job.getTitle(), job.getCompany());
            writer.writeExternal(job,
                    "Apply outcome could not be confirmed — visit Naukri URL to check/apply manually");
        }
    }

    private boolean isApplySuccess() {
        String url = currentUrl();
        if (url != null && (
                url.contains("/myapply/saveApply") ||
                        url.contains("/myapply/showAcp")   ||
                        url.contains("multiApplyResp"))) {
            return true;
        }
        if (isPresent(By.cssSelector(".acp-container")))       return true;
        if (isPresent(By.cssSelector(".applied-job-content"))) return true;
        if (isPresent(By.cssSelector(".start-prep-txt")))      return true;
        try {
            String body = driver.findElement(By.tagName("body")).getText().toLowerCase();
            return body.contains("applied to")
                    || body.contains("application submitted")
                    || body.contains("start practicing")
                    || body.contains("start your interview preparation");
        } catch (Exception ignored) {}
        return false;
    }

    private void handleConfirmationModal() {
        try {
            WebElement btn = new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.elementToBeClickable(By.xpath(
                            "//button[contains(text(),'Apply') or contains(text(),'Submit') " +
                                    "or contains(text(),'Confirm') or contains(text(),'Send')]")));
            log.debug("[ APPLY ] Confirmation modal — clicking: {}", btn.getText());
            btn.click();
            sleep(2000);
        } catch (TimeoutException e) {
            log.debug("[ APPLY ] No confirmation modal.");
        }
    }

    private WebElement findApplyButton() {
        String[] xpaths = {
                "//button[contains(@class,'apply-button') and not(contains(@class,'save'))]",
                "//button[translate(normalize-space(text()),'abcdefghijklmnopqrstuvwxyz'," +
                        "'ABCDEFGHIJKLMNOPQRSTUVWXYZ')='EASY APPLY']",
                "//button[translate(normalize-space(text()),'abcdefghijklmnopqrstuvwxyz'," +
                        "'ABCDEFGHIJKLMNOPQRSTUVWXYZ')='APPLY']",
                "//a[translate(normalize-space(text()),'abcdefghijklmnopqrstuvwxyz'," +
                        "'ABCDEFGHIJKLMNOPQRSTUVWXYZ')='APPLY']",
                "//button[contains(translate(text(),'abcdefghijklmnopqrstuvwxyz'," +
                        "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'APPLY') and not(contains(@class,'save'))]",
                "//*[@id='apply-button']"
        };
        for (String xp : xpaths) {
            try {
                List<WebElement> els = new WebDriverWait(driver, Duration.ofSeconds(4))
                        .until(ExpectedConditions.presenceOfAllElementsLocatedBy(By.xpath(xp)));
                for (WebElement el : els)
                    if (el.isDisplayed() && el.isEnabled()) return el;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Counters ─────────────────────────────────────────────────────────────

    public int getApplied()   { return applied.get(); }
    public int getExternal()  { return external.get(); }
    public int getRejected()  { return rejected.get(); }
    public int getMatched()   { return matched.get(); }
    public int getMatchOnly() { return matchOnly.get(); }

    private String nvl(String s) { return s == null ? "" : s; }
}
