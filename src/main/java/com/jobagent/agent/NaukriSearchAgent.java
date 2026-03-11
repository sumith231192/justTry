package com.jobagent.agent;

import com.jobagent.config.AppConfig;
import com.jobagent.model.JobListing;
import com.jobagent.model.KeywordStats;
import com.jobagent.service.JobTrackerService;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.*;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * NaukriSearchAgent — Search, paginate, open each job detail page, hand to scorer.
 *
 * ── LOCATION FIX ──────────────────────────────────────────────────────────────
 *
 * When running on GitHub Actions (or any cloud server), Naukri detects the
 * server's geo-IP (typically outside India) and silently overrides the location
 * parameter in the URL to the detected region. This causes all searches to land
 * on the wrong location even though the URL says "bangalore".
 *
 * Fix: We pass BOTH the slug AND the explicit city/state query parameters that
 * Naukri uses internally to pin the location regardless of geo-IP:
 *
 *   &location=bengaluru&locationId=6&locationLabel=bengaluru
 *
 * locationId=6 is Bengaluru's internal Naukri city ID.
 * This pins the SRP to Bengaluru even when running from GitHub Actions in the US.
 *
 * ── PARALLELISM MODEL (page-based) ──────────────────────────────────────────
 *
 * Each browser worker is assigned a LIST OF PAGE NUMBERS for a keyword, not a
 * keyword slice. This is more efficient because all workers attack the same
 * keyword together, then move to the next keyword together.
 *
 * Example: keyword "QA Lead", maxPages=10, browsers=3
 *   Worker-1 → pages [1, 4, 7, 10]
 *   Worker-2 → pages [2, 5, 8]
 *   Worker-3 → pages [3, 6, 9]
 *
 * After all workers finish their pages for "QA Lead", Main moves all workers
 * on to the next keyword the same way.
 *
 * ── DUPLICATE PREVENTION ────────────────────────────────────────────────────
 *
 * At stub-collection time (before opening any detail page), each job card's
 * jobId is checked via tracker.tryMark(). This is atomic — only the first
 * worker to see a jobId processes it; all others skip immediately.
 * The jobId is also written to disk so restarts don't re-process.
 */
public class NaukriSearchAgent extends BrowserAgent {

    // ── Bengaluru location constants ──────────────────────────────────────────
    // locationId=6 is Naukri's internal city ID for Bengaluru.
    // Appending these params forces Naukri to pin the search to Bengaluru
    // regardless of the server's geo-IP location (critical for GitHub Actions).
    private static final String BENGALURU_LOCATION_PARAMS =
            "&location=bengaluru&locationId=6&locationLabel=bengaluru&cityLabel=bengaluru";

    private final JobTrackerService tracker;
    private final NaukriFilterAgent filterAgent;
    private Consumer<JobListing>    jobProcessor;
    private JobScoringAgent         scorer;        // kept for setActiveStats() calls
    private String                  mainWindowHandle;

    // Per-keyword stats — keyed by keyword string, populated during the run
    // ConcurrentHashMap because parallel workers may update different keys simultaneously
    private final java.util.concurrent.ConcurrentHashMap<String, KeywordStats> allStats =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ── Constructors ──────────────────────────────────────────────────────────

    /** Sequential mode — all keywords, fresh tracker. */
    public NaukriSearchAgent(String cvText) {
        this(cvText, new JobTrackerService());
    }

    /** Parallel mode — shared tracker injected from Main. */
    public NaukriSearchAgent(String cvText, JobTrackerService sharedTracker) {
        this.tracker     = sharedTracker;
        this.filterAgent = new NaukriFilterAgent(cvText);
    }

    public void linkFilterAgent() {
        filterAgent.driver = this.driver;
        filterAgent.wait   = this.wait;
    }

    public void setJobProcessor(Consumer<JobListing> processor) {
        this.jobProcessor = processor;
    }

    /**
     * Wire the scorer directly so NaukriSearchAgent can call setActiveStats()
     * before each keyword starts. Also sets jobProcessor automatically.
     */
    public void setScorer(JobScoringAgent scorer) {
        this.scorer       = scorer;
        this.jobProcessor = job -> scorer.process(job);
    }

    /** Returns a snapshot of per-keyword stats accumulated so far. */
    public java.util.Map<String, KeywordStats> getAllStats() {
        return java.util.Collections.unmodifiableMap(allStats);
    }

    // ── Sequential entry point (parallel.enabled=false) ──────────────────────

    /**
     * Sequential mode: iterate all keywords, all pages one by one.
     * Builds the full page list [1..maxPages] and delegates.
     */
    public void searchAllKeywords(List<String> keywords) {
        int maxPages = AppConfig.getJobSearchMaxPages();
        List<Integer> allPages = new ArrayList<>();
        for (int p = 1; p <= maxPages; p++) allPages.add(p);
        searchAllKeywordsWithPages(keywords, allPages);
    }

    // ── Parallel entry point (parallel.enabled=true) ──────────────────────────

    /**
     * Parallel mode — called ONCE per worker for the ENTIRE run.
     *
     * The worker stays logged in and iterates through every keyword.
     * For each keyword it processes only its assigned page numbers.
     * This means ONE login per worker for all keywords, not one login per keyword.
     *
     * @param keywords    ALL keywords (same list for every worker)
     * @param pageNumbers this worker's page slice e.g. [1,4,7,10] for worker-1
     */
    public void searchAllKeywordsWithPages(List<String> keywords, List<Integer> pageNumbers) {
        mainWindowHandle = driver.getWindowHandle();
        int    exp       = AppConfig.getCandidateExperienceYears();

        log.info("[ W ] Starting — {} keyword(s), pages {} each", keywords.size(), pageNumbers);
        log.info("[ W ] Location pinned to: Bengaluru (locationId=6) — geo-IP independent");

        for (String keyword : keywords) {
            log.info("┌─────────────────────────────────────────────────────");
            log.info("│ KEYWORD: \"{}\"  pages: {}  | {} yrs | Bengaluru", keyword, pageNumbers, exp);
            log.info("└─────────────────────────────────────────────────────");

            // Get-or-create shared stats for this keyword
            // computeIfAbsent is atomic — parallel workers get the same object for the same keyword
            KeywordStats stats = allStats.computeIfAbsent(keyword, KeywordStats::new);

            // Tell the scorer which keyword we're on
            if (scorer != null) scorer.setActiveStats(stats);

            String kwSlug  = slugify(keyword);
            try {
                applyFiltersOnce(kwSlug, exp);
                processPages(keyword, kwSlug, exp, pageNumbers, stats);
            } catch (Exception e) {
                log.error("[ SEARCH ] Error on keyword \"{}\": {}", keyword, e.getMessage(), e);
                ensureMainWindow();
            }

            log.info("[ KW DONE ] \"{}\" → applied={} external={} linkOnly={} rejected={} skipped(dup)={}",
                    keyword, stats.getApplied(), stats.getExternal(),
                    stats.getLinkOnly(), stats.getRejected(), stats.getSkipped());
            sleep(2000);
        }
    }

    /**
     * Parallel single-keyword mode — legacy, kept for compatibility.
     * Prefer searchAllKeywordsWithPages for real parallel runs.
     */
    public void searchKeywordPages(String keyword, List<Integer> pageNumbers) {
        mainWindowHandle = driver.getWindowHandle();
        int    exp       = AppConfig.getCandidateExperienceYears();
        String kwSlug    = slugify(keyword);
        log.info("[ W ] Keyword: \"{}\" | Pages: {}", keyword, pageNumbers);
        KeywordStats stats = allStats.computeIfAbsent(keyword, KeywordStats::new);
        if (scorer != null) scorer.setActiveStats(stats);
        try {
            applyFiltersOnce(kwSlug, exp);
            processPages(keyword, kwSlug, exp, pageNumbers, stats);
        } catch (Exception e) {
            log.error("[ SEARCH ] Worker error on \"{}\": {}", keyword, e.getMessage(), e);
            ensureMainWindow();
        }
    }

    // ── Core: load + apply filters once per keyword ───────────────────────────

    private void applyFiltersOnce(String kwSlug, int exp) {
        String page1Url = buildPageUrl(kwSlug, exp, 1);
        log.info("[ SEARCH ] Loading SRP page 1 → {}", page1Url);
        driver.get(page1Url);
        waitForSrpCards(15);
        dismissOverlays();

        // ── Set experience in the GNB search bar dropdown ────────────────────
        if (exp > 0) {
            setExperienceInSearchBar(exp);
        }

        // ── Set experience in the left-panel Experience slider ────────────────
        if (exp > 0) {
            setExperienceSlider(exp);
        }

        int clicked = filterAgent.applyFilters();
        if (clicked > 0) {
            log.info("[ SEARCH ] {} filter(s) applied.", clicked);
            sleep(3000);
            dismissOverlays();
        }
    }

    /**
     * Sets the experience value in the GNB (top search bar) experience dropdown.
     */
    private void setExperienceInSearchBar(int exp) {
        try {
            WebElement expDD = driver.findElement(By.id("experienceDD"));
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", expDD);
            sleep(600);

            String targetValue = "a" + exp;
            List<WebElement> items = driver.findElements(
                    By.cssSelector("ul.dropdown li[value='" + targetValue + "']"));
            if (!items.isEmpty()) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", items.get(0));
                log.info("[ SEARCH ] ✓ GNB experience set to {} years.", exp);
                sleep(800);
            } else {
                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].value = arguments[1];", expDD, String.valueOf(exp));
                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].dispatchEvent(new Event('change', {bubbles:true}));", expDD);
                log.info("[ SEARCH ] GNB experience set via JS fallback ({} yrs).", exp);
                sleep(500);
            }
        } catch (Exception e) {
            log.debug("[ SEARCH ] GNB experience dropdown not set: {}", e.getMessage());
        }
    }

    /**
     * Sets the left-panel Experience slider to the candidate's years of experience.
     */
    private void setExperienceSlider(int exp) {
        try {
            WebElement handle = driver.findElement(
                    By.cssSelector("div.styles_filterOptns__1vq77[data-filter-id='experience'] div.handle"));

            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center'});", handle);
            sleep(300);
            handle.click();
            sleep(300);

            handle.sendKeys(Keys.HOME);
            sleep(200);
            for (int i = 0; i < Math.min(exp, 30); i++) {
                handle.sendKeys(Keys.ARROW_RIGHT);
            }
            sleep(800);

            waitForSrpCards(10);
            dismissOverlays();
            log.info("[ SEARCH ] ✓ Experience slider set to {} years.", exp);

        } catch (Exception e) {
            log.debug("[ SEARCH ] Experience slider not available: {}", e.getMessage());
        }
    }

    // ── Core: process a list of page numbers for one keyword ─────────────────

    private void processPages(String keyword, String kwSlug,
                              int exp, List<Integer> pageNumbers, KeywordStats stats) {
        for (int pageNum : pageNumbers) {
            String pageUrl = buildPageUrl(kwSlug, exp, pageNum);
            log.info("[ SEARCH ] ══ \"{}\" PAGE {} ══  URL: {}", keyword, pageNum, pageUrl);
            driver.get(pageUrl);
            waitForSrpCards(12);
            dismissOverlays();

            if (exp > 0) setExperienceInSearchBar(exp);

            scrollPageToLoadAllCards();

            List<JobStub> stubs = collectJobStubsFromSrp();
            log.info("[ SEARCH ] Page {} → {} card(s) found.", pageNum, stubs.size());

            if (stubs.isEmpty()) {
                log.info("[ SEARCH ] No cards on page {} — stopping for \"{}\".", pageNum, keyword);
                break;
            }

            String srpUrl = currentUrl();
            int idx = 0;
            for (JobStub stub : stubs) {
                idx++;
                if (!tracker.tryMark(stub.jobId)) {
                    log.debug("[ SEARCH ] Duplicate jobId {} — skipping.", stub.jobId);
                    stats.incSkipped();
                    continue;
                }
                log.info("[ SEARCH ] ── Job {}/{} : {} ──", idx, stubs.size(), stub.title);
                JobListing job = openJobDetailPage(stub);
                if (job == null) {
                    log.warn("[ SEARCH ] Could not open detail for: {}", stub.title);
                    navigateBackToSrp(srpUrl);
                    continue;
                }
                if (jobProcessor != null) {
                    try {
                        jobProcessor.accept(job);
                    } catch (Exception e) {
                        log.warn("[ SEARCH ] Processor error on \"{}\": {}", job.getTitle(), e.getMessage());
                    }
                }
                navigateBackToSrp(srpUrl);
            }
        }
    }

    // ── Collect job stubs from current SRP page ───────────────────────────────

    private List<JobStub> collectJobStubsFromSrp() {
        List<JobStub> stubs = new ArrayList<>();

        List<WebElement> cards = driver.findElements(By.cssSelector("div.srp-jobtuple-wrapper"));
        if (cards.isEmpty())
            cards = driver.findElements(By.cssSelector("article.jobTuple"));
        if (cards.isEmpty())
            cards = driver.findElements(By.cssSelector("div.cust-job-tuple"));
        if (cards.isEmpty())
            cards = driver.findElements(By.cssSelector("[class*='jobTuple']"));
        if (cards.isEmpty())
            cards = driver.findElements(By.cssSelector("[class*='job-tuple']"));

        log.debug("[ SEARCH ] Raw card count from DOM: {}", cards.size());

        for (WebElement card : cards) {
            try {
                String jobId = card.getAttribute("data-job-id");
                if (jobId == null || jobId.isBlank())
                    jobId = card.getAttribute("data-id");
                if (jobId == null || jobId.isBlank()) continue;

                WebElement titleLink = null;
                for (String sel : new String[]{
                        "a.title", "a.jobTitle", ".title a", "h2 a", "h3 a",
                        "a[class*='title']", "a[class*='jobTitle']",
                        ".row1 a", "a[href*='naukri.com/job-listings']"}) {
                    try { titleLink = card.findElement(By.cssSelector(sel)); break; }
                    catch (Exception ignored) {}
                }
                if (titleLink == null) continue;

                String href = titleLink.getAttribute("href");
                if (href == null || href.isBlank()) continue;

                String title = titleLink.getAttribute("title");
                if (title == null || title.isBlank()) title = titleLink.getText().trim();
                if (title.isBlank()) continue;

                JobStub stub     = new JobStub();
                stub.jobId       = jobId.trim();
                stub.href        = href.trim();
                stub.title       = title.trim();
                stub.company     = firstOf(
                        safeCardAttr(card, "a.comp-name",          "title", ""),
                        safeCardText(card, "a.comp-name"),
                        safeCardAttr(card, "a[class*='comp-name']","title", ""),
                        safeCardText(card, "a[class*='comp-name']"),
                        safeCardText(card, "[class*='company']"));
                stub.exp         = firstOf(
                        safeCardAttr(card, "span.expwdth",         "title", ""),
                        safeCardText(card, "span.expwdth"),
                        safeCardAttr(card, "span[class*='exp']",   "title", ""),
                        safeCardText(card, "span[class*='exp']")).trim();
                stub.location    = firstOf(
                        safeCardAttr(card, "span.locWdth",         "title", ""),
                        safeCardText(card, "span.locWdth"),
                        safeCardAttr(card, "span[class*='loc']",   "title", ""),
                        safeCardText(card, "span[class*='loc']")).trim();
                stub.salary      = firstOf(
                        safeCardText(card, ".sal"),
                        safeCardText(card, ".salary"),
                        safeCardText(card, "[class*='salary']"));
                stub.posted      = firstOf(
                        safeCardText(card, "span.job-post-day"),
                        safeCardText(card, "[class*='posted']"),
                        safeCardText(card, "span.date"));

                List<String> tags = new ArrayList<>();
                for (WebElement t : card.findElements(
                        By.cssSelector("ul.tags-gt li.tag-li, .tag-li"))) {
                    String tag = t.getText().trim();
                    if (!tag.isEmpty()) tags.add(tag);
                }
                stub.srpTagsHint = tags.isEmpty() ? "" : String.join(", ", tags);

                stubs.add(stub);

            } catch (StaleElementReferenceException ignored) {
                log.debug("[ SEARCH ] Stale card — skipping.");
            } catch (Exception e) {
                log.debug("[ SEARCH ] Card parse error: {}", e.getMessage());
            }
        }
        return stubs;
    }

    // ── Open job detail page + extract full JD ────────────────────────────────

    private JobListing openJobDetailPage(JobStub stub) {
        try {
            log.debug("[ JOB ] Opening: {}", stub.href);
            driver.get(stub.href);
            waitForJobDetailPage();

            JobListing job = new JobListing();
            job.setJobId   (stub.jobId);
            job.setApplyUrl(stub.href);

            job.setTitle(firstOf(
                    safeText(By.cssSelector("h1.jd-header-title")),
                    safeText(By.cssSelector("h1")), stub.title));
            job.setCompany(firstOf(
                    safeText(By.cssSelector("a.jd-header-comp-name")),
                    safeText(By.cssSelector(".comp-name")),
                    safeText(By.cssSelector(".company-name")), stub.company));
            job.setLocation(firstOf(
                    safeText(By.cssSelector(".location")),
                    safeText(By.cssSelector("[class*='location']")), stub.location));
            job.setExperience(firstOf(
                    safeText(By.cssSelector(".exp")),
                    safeText(By.cssSelector("[class*='experience']")),
                    safeText(By.cssSelector(".expIcon")), stub.exp));
            job.setSalary(firstOf(
                    safeText(By.cssSelector(".salary")),
                    safeText(By.cssSelector("[class*='salary']")),
                    safeText(By.cssSelector(".salaryIcon")), stub.salary));
            job.setPostedDate(stub.posted);

            String fullJd = extractFullJdText();

            List<String> detailTags = new ArrayList<>();
            for (String sel : new String[]{
                    ".key-skill a", ".keyskill a", "ul.tags-gt li",
                    "li.tag-li", "[class*='chip']", "[class*='skill'] a", ".jd-skills li"}) {
                try {
                    for (WebElement t : driver.findElements(By.cssSelector(sel))) {
                        String tag = t.getText().trim();
                        if (!tag.isEmpty() && !detailTags.contains(tag)) detailTags.add(tag);
                    }
                } catch (Exception ignored) {}
            }

            String skillsStr = detailTags.isEmpty() ? stub.srpTagsHint
                    : String.join(", ", detailTags);
            String desc = fullJd + (skillsStr.isBlank() ? "" : "\n\nSkills: " + skillsStr);
            if (desc.isBlank())
                desc = "Title: " + stub.title
                        + (stub.srpTagsHint.isBlank() ? "" : " | Skills: " + stub.srpTagsHint);

            job.setDescription(desc);
            log.info("[ JOB ] OK — {} chars JD, {} skill tag(s) | \"{}\"",
                    fullJd.length(), detailTags.size(), job.getTitle());
            return job;

        } catch (Exception e) {
            log.error("[ JOB ] Failed to open \"{}\": {}", stub.title, e.getMessage());
            return null;
        }
    }

    private void waitForJobDetailPage() {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(15)).until(
                    ExpectedConditions.or(
                            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".dang-inner-html")),
                            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".jd-desc")),
                            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".job-desc")),
                            ExpectedConditions.presenceOfElementLocated(By.cssSelector("#job_description")),
                            ExpectedConditions.presenceOfElementLocated(By.cssSelector("h1.jd-header-title")),
                            ExpectedConditions.presenceOfElementLocated(By.cssSelector("[class*='job-desc-container']")),
                            ExpectedConditions.presenceOfElementLocated(By.cssSelector("[class*='jd-container']")),
                            ExpectedConditions.presenceOfElementLocated(By.cssSelector("[class*='styles_jhc']")),
                            ExpectedConditions.presenceOfElementLocated(By.cssSelector("h1"))
                    )
            );
        } catch (TimeoutException e) {
            log.warn("[ JOB ] Detail page timeout — proceeding anyway.");
        }
        sleep(2000);
    }

    private String extractFullJdText() {
        String[][] selectors = {
                {"[class*='styles_jhc__desc']",       "80"},
                {"[class*='job-desc-container']",     "80"},
                {"[class*='jd-container']",           "80"},
                {".dang-inner-html",                  "80"},
                {".jd-desc",                          "80"},
                {".job-desc",                         "80"},
                {"#job_description",                  "80"},
                {".jobDescriptionText",               "80"},
                {"section.job-desc",                  "80"},
                {"[class*='jd-desc']",                "80"},
                {"[class*='jobDesc']",                "80"},
                {".description-section",              "80"},
                {".description",                      "80"},
                {"div[id*='description']",            "80"},
                {"body",                              "200"}
        };
        for (String[] pair : selectors) {
            try {
                WebElement el = driver.findElement(By.cssSelector(pair[0]));
                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].scrollIntoView({block:'center'});", el);
                sleep(300);
                String text = el.getText().trim();
                if (text.length() >= Integer.parseInt(pair[1])) {
                    log.debug("[ JD ] {} chars via \"{}\"", text.length(), pair[0]);
                    return text.substring(0, Math.min(4000, text.length()));
                }
            } catch (Exception ignored) {}
        }
        log.warn("[ JD ] No JD text found on: {}", currentUrl());
        return "";
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigateBackToSrp(String srpUrl) {
        try {
            ensureMainWindow();
            driver.navigate().back();
            sleep(2000);

            String currentUrl = currentUrl();
            if (currentUrl == null || (!currentUrl.contains("naukri.com") && !currentUrl.contains("job"))) {
                log.debug("[ SEARCH ] back() landed on unexpected page, reloading SRP.");
                driver.get(srpUrl);
            }

            waitForSrpCards(12);
            dismissOverlays();
        } catch (Exception e) {
            log.warn("[ SEARCH ] SRP return failed ({}), reloading URL...", e.getMessage());
            try {
                sleep(1500);
                driver.get(srpUrl);
                sleep(3000);
                waitForSrpCards(10);
            } catch (Exception ignored) {}
        }
    }

    private void ensureMainWindow() {
        try {
            Set<String> handles = new HashSet<>(driver.getWindowHandles());
            handles.remove(mainWindowHandle);
            for (String h : handles) {
                try { driver.switchTo().window(h); driver.close(); } catch (Exception ignored) {}
            }
            driver.switchTo().window(mainWindowHandle);
        } catch (Exception e) {
            log.warn("[ SEARCH ] ensureMainWindow: {}", e.getMessage());
        }
    }

    // ── Scroll helpers ────────────────────────────────────────────────────────

    private void scrollPageToLoadAllCards() {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            long last = -1;
            for (int i = 0; i < 15; i++) {
                js.executeScript("window.scrollBy(0, 800);");
                sleep(400);
                long offset = (Long) js.executeScript("return window.pageYOffset;");
                if (offset == last) break;
                last = offset;
            }
            js.executeScript("window.scrollTo(0, 0);");
            sleep(500);
        } catch (Exception ignored) {}
    }

    // ── Wait helpers ──────────────────────────────────────────────────────────

    private void waitForSrpCards(int seconds) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(seconds)).until(
                    ExpectedConditions.or(
                            ExpectedConditions.presenceOfElementLocated(
                                    By.cssSelector("div.srp-jobtuple-wrapper")),
                            ExpectedConditions.presenceOfElementLocated(
                                    By.cssSelector("article.jobTuple"))
                    )
            );
        } catch (TimeoutException e) {
            log.warn("[ SEARCH ] SRP card timeout. Title: {}", driver.getTitle());
        }
        sleep(1000);
    }

    // ── URL builder ───────────────────────────────────────────────────────────

    /**
     * Builds a Naukri SRP URL with Bengaluru pinned via explicit location params.
     *
     * Page 1:  /keyword-jobs-in-bengaluru?experience=N&jobAge=7&location=bengaluru&locationId=6...
     * Page 2+: /keyword-jobs-in-bengaluru-2?experience=N&jobAge=7&location=bengaluru&locationId=6...
     *
     * The BENGALURU_LOCATION_PARAMS appended at the end overrides Naukri's
     * geo-IP detection, ensuring results are always from Bengaluru even
     * when the job runs on GitHub Actions servers in the US/EU.
     */
    private String buildPageUrl(String kwSlug, int exp, int page) {
        // Always use bengaluru as the slug — regardless of application.properties location setting.
        // This ensures the URL itself already specifies the right city.
        String locSlug = "bengaluru";

        String base;
        if (page <= 1) {
            base = String.format(
                    "https://www.naukri.com/%s-jobs-in-%s?experience=%d&jobAge=7",
                    kwSlug, locSlug, exp);
        } else {
            base = String.format(
                    "https://www.naukri.com/%s-jobs-in-%s-%d?experience=%d&jobAge=7",
                    kwSlug, locSlug, page, exp);
        }

        // Append explicit Bengaluru location params to override geo-IP detection
        return base + BENGALURU_LOCATION_PARAMS;
    }

    /** Distribute page numbers across N workers using round-robin interleaving. */
    public static List<List<Integer>> distributePages(int totalPages, int workers) {
        List<List<Integer>> buckets = new ArrayList<>();
        for (int i = 0; i < workers; i++) buckets.add(new ArrayList<>());
        for (int page = 1; page <= totalPages; page++)
            buckets.get((page - 1) % workers).add(page);
        return buckets;
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private void dismissOverlays() {
        try {
            driver.findElement(By.cssSelector(
                    "button[aria-label='Close'], .close-button, .modal-close, " +
                            "[class*='closeBtn'], [class*='close-btn']")).click();
            sleep(300);
        } catch (Exception ignored) {}
        try { driver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE); }
        catch (Exception ignored) {}
    }

    @Override
    protected String safeText(By by) {
        try { return driver.findElement(by).getText().trim(); }
        catch (Exception e) { return ""; }
    }

    private String safeCardText(WebElement root, String css) {
        try { return root.findElement(By.cssSelector(css)).getText().trim(); }
        catch (Exception e) { return ""; }
    }

    private String safeCardAttr(WebElement root, String css, String attr, String fallback) {
        try {
            String v = root.findElement(By.cssSelector(css)).getAttribute(attr);
            return (v != null && !v.isBlank()) ? v.trim() : fallback;
        } catch (Exception e) { return fallback; }
    }

    private String firstOf(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private String slugify(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    // ── Inner class ───────────────────────────────────────────────────────────

    private static class JobStub {
        String jobId, href, title, company, exp, location, salary, posted, srpTagsHint;
    }
}
