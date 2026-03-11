package com.jobagent.agent;

import com.jobagent.config.AppConfig;
import com.jobagent.config.FilterConfig;
import com.jobagent.service.GroqClient;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.*;

import java.time.Duration;
import java.util.*;
import java.util.NoSuchElementException;

/**
 * Applies the Naukri SRP left-panel filters after a search results page loads.
 *
 * Flow:
 *   1. If filters.getFromCv=true  → ask Groq which filter values match the CV,
 *      store result in AppConfig as runtime FilterConfig (done once per run).
 *   2. Apply FilterConfig to the live Naukri filter panel:
 *        • Checkbox filters (Work mode, Department, Role, Company type, etc.)
 *        • Single-select dropdown (Freshness)
 *   3. Wait for the result list to refresh before returning.
 *
 * All selectors come from the confirmed live Naukri HTML (March 2025).
 */
public class NaukriFilterAgent extends BrowserAgent {

    // ── data-filter-id values on Naukri's filter panel ────────
    private static final String FID_WORK_MODE    = "wfhType";
    private static final String FID_DEPARTMENT   = "functionalAreaGid";
    private static final String FID_ROLE_CAT     = "glbl_RoleCat";
    private static final String FID_COMPANY_TYPE = "business_size";
    private static final String FID_INDUSTRY     = "industryTypeGid";
    private static final String FID_SALARY       = "salaryRange";
    private static final String FID_POSTED_BY    = "employement";
    private static final String FID_TOP_COMPANY  = "topGroupId";
    private static final String FID_EDUCATION    = "ugTypeGid";

    private final String cvText;          // full CV text, used for Groq prompt
    private boolean filtersResolved = false;

    public NaukriFilterAgent(String cvText) {
        this.cvText = cvText;
    }

    // ── Public entry point ─────────────────────────────────────

    /**
     * Call this once per SRP page, AFTER the page has loaded job cards.
     * Returns the number of filter checkboxes actually clicked.
     */
    public int applyFilters() {
        // Master switch — if filters.apply=false, skip everything.
        // Only location + experience (baked into the search URL) will be used.
        if (!AppConfig.isFiltersEnabled()) {
            log.info("[ FILTER ] filters.apply=false — all SRP filters skipped.");
            return 0;
        }

        // Step 1 — resolve filter config (once per run)
        if (!filtersResolved) {
            resolveFilters();
            filtersResolved = true;
        }

        FilterConfig fc = AppConfig.getFilters();
        if (!fc.hasAny()) {
            log.info("[ FILTER ] No filters configured — skipping.");
            return 0;
        }

        log.info("[ FILTER ] Applying: {}", fc);

        // ── Hide the sticky Naukri header before any filter clicks ──
        // The header sits at y=0 and intercepts all clicks at the top of the page.
        // Hiding it temporarily lets JS clicks reach the filter checkboxes.
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "var h = document.querySelector('header, .nI-gNb-wrapper, [class*=\"header\"]');" +
                            "if(h) h.style.display='none';");
            log.debug("[ FILTER ] Sticky header hidden for filter clicks.");
        } catch (Exception ignored) {}

        // Also scroll down slightly so filter panel is clear of any top chrome
        try {
            ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 200);");
            sleep(300);
        } catch (Exception ignored) {}

        int clicked = 0;

        // ── Checkbox filters ──────────────────────────────────
        clicked += applyCheckboxFilter(FID_WORK_MODE,    fc.workMode);
        clicked += applyCheckboxFilter(FID_DEPARTMENT,   fc.department);
        clicked += applyCheckboxFilter(FID_ROLE_CAT,     fc.roleCategory);
        clicked += applyCheckboxFilter(FID_COMPANY_TYPE, fc.companyType);
        clicked += applyCheckboxFilter(FID_INDUSTRY,     fc.industry);
        clicked += applyCheckboxFilter(FID_SALARY,       fc.salary);
        clicked += applyCheckboxFilter(FID_POSTED_BY,    fc.postedBy);
        clicked += applyCheckboxFilter(FID_TOP_COMPANY,  fc.topCompanies);
        clicked += applyCheckboxFilter(FID_EDUCATION,    fc.education);

        // ── Freshness single-select dropdown ──────────────────
        if (fc.freshness != null && !fc.freshness.isBlank()) {
            clicked += applyFreshnessFilter(fc.freshness);
        }

        if (clicked > 0) {
            log.info("[ FILTER ] {} filter(s) clicked — waiting for results to refresh.", clicked);
            waitForResultsRefresh();
        }

        // Restore the header after all filter clicks are done
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "var h = document.querySelector('header, .nI-gNb-wrapper, [class*=\"header\"]');" +
                            "if(h) h.style.display='';");
        } catch (Exception ignored) {}

        return clicked;
    }

    // ── Step 1: resolve which filters to use ──────────────────

    private void resolveFilters() {
        if (AppConfig.isGetFiltersFromCv()) {
            log.info("[ FILTER ] filters.getFromCv=true — asking Groq to choose filters from CV.");
            try {
                FilterConfig cvFilters = deriveFiltersFromCv();
                AppConfig.setRuntimeFilters(cvFilters);
                log.info("[ FILTER ] CV-derived filters: {}", cvFilters);
            } catch (Exception e) {
                log.warn("[ FILTER ] Groq filter derivation failed ({}), falling back to properties.", e.getMessage());
            }
        } else {
            log.info("[ FILTER ] filters.getFromCv=false — using application.properties values.");
        }
    }

    private FilterConfig deriveFiltersFromCv() {
        String prompt = buildFilterPrompt();
        String response = GroqClient.getInstance().chat(prompt);
        return parseGroqFilterResponse(response);
    }

    private String buildFilterPrompt() {
        return "You are a job search assistant. Based on the candidate's CV below, " +
                "select the most relevant filter values for a Naukri.com job search.\n\n" +
                "Return ONLY a JSON object with these exact keys and ONLY values from the allowed lists:\n\n" +
                "{\n" +
                "  \"workMode\":     [\"Work from office\", \"Hybrid\"],\n" +
                "  \"department\":   [\"Engineering - Software & QA\"],\n" +
                "  \"roleCategory\": [\"Quality Assurance and Testing\"],\n" +
                "  \"companyType\":  [\"Foreign MNC\", \"Indian MNC\", \"Corporate\"],\n" +
                "  \"industry\":     [\"IT Services & Consulting\", \"Software Product\"],\n" +
                "  \"salary\":       [\"25-50 Lakhs\"],\n" +
                "  \"freshness\":    \"Last 7 days\",\n" +
                "  \"postedBy\":     [\"Company Jobs\"],\n" +
                "  \"topCompanies\": [],\n" +
                "  \"education\":    []\n" +
                "}\n\n" +
                "Allowed values:\n" +
                "  workMode: Work from office | Hybrid | Remote\n" +
                "  department: Engineering - Software & QA | IT & Information Security | Data Science & Analytics | Consulting\n" +
                "  roleCategory: Quality Assurance and Testing | Software Development | DevOps | DBA / Data warehousing\n" +
                "  companyType: Foreign MNC | Indian MNC | Corporate | Startup\n" +
                "  industry: IT Services & Consulting | Software Product | BPM / BPO | Banking | Insurance | Healthcare | Telecom\n" +
                "  salary: 0-3 Lakhs | 3-6 Lakhs | 6-10 Lakhs | 10-15 Lakhs | 15-25 Lakhs | 25-50 Lakhs | 50-75 Lakhs | 75-100 Lakhs | 1-5 Cr\n" +
                "  freshness: Last 1 day | Last 3 days | Last 7 days | Last 15 days | Last 30 days\n" +
                "  postedBy: Company Jobs | Consultant Jobs\n" +
                "  topCompanies: [] (leave empty unless you have strong reason)\n" +
                "  education: MCA | Any Postgraduate | BCA - Bachelor of Computer Applications | Any Graduate\n\n" +
                "Use empty arrays [] for categories that don't strongly match the CV. " +
                "Do not add any explanation. Return raw JSON only.\n\n" +
                "CV:\n" + (cvText != null ? cvText.substring(0, Math.min(cvText.length(), 3000)) : "Not available");
    }

    @SuppressWarnings("unchecked")
    private FilterConfig parseGroqFilterResponse(String json) {
        // Simple JSON parser — avoids pulling in a full JSON library
        FilterConfig.Builder b = new FilterConfig.Builder();
        try {
            // strip markdown fences if present
            json = json.replaceAll("```json|```", "").trim();

            b.workMode    (extractJsonArray(json, "workMode"))
                    .department  (extractJsonArray(json, "department"))
                    .roleCategory(extractJsonArray(json, "roleCategory"))
                    .companyType (extractJsonArray(json, "companyType"))
                    .industry    (extractJsonArray(json, "industry"))
                    .salary      (extractJsonArray(json, "salary"))
                    .freshness   (extractJsonString(json, "freshness"))
                    .postedBy    (extractJsonArray(json, "postedBy"))
                    .topCompanies(extractJsonArray(json, "topCompanies"))
                    .education   (extractJsonArray(json, "education"));
        } catch (Exception e) {
            log.warn("[ FILTER ] Could not parse Groq filter JSON: {}", e.getMessage());
        }
        return b.build();
    }

    // ── Step 2: click checkboxes ──────────────────────────────

    /**
     * Finds the filter section with matching data-filter-id, then clicks the
     * checkbox whose label text matches each wanted value (case-insensitive).
     * Returns number of checkboxes successfully clicked.
     */
    private int applyCheckboxFilter(String filterId, List<String> wantedValues) {
        if (wantedValues == null || wantedValues.isEmpty()) return 0;

        int clicked = 0;
        int delay   = AppConfig.getFilterClickDelayMs();
        int retries = AppConfig.getFilterClickRetries();

        for (String wanted : wantedValues) {
            if (wanted == null || wanted.isBlank()) continue;
            String wantedLower = wanted.trim().toLowerCase();

            try {
                // Target the specific filter section
                WebElement section = driver.findElement(
                        By.cssSelector("[data-filter-id='" + filterId + "']"));

                // Expand "View More" if present and our target isn't visible yet
                expandIfNeeded(section, wantedLower);

                // Find matching label
                List<WebElement> labels = section.findElements(
                        By.cssSelector("label.styles_chkLbl__n2x09"));

                boolean found = false;
                for (WebElement label : labels) {
                    String labelText = label.getText().toLowerCase();
                    // match by contains so minor spacing differences don't matter
                    if (labelText.contains(wantedLower)) {
                        found = true;
                        if (!isChecked(label)) {
                            clickWithRetry(label, retries);
                            sleep(delay);
                            clicked++;
                            log.info("[ FILTER ] ✓ Checked: [{}] → \"{}\"", filterId, wanted);
                        } else {
                            log.debug("[ FILTER ] Already checked: [{}] → \"{}\"", filterId, wanted);
                        }
                        break;
                    }
                }
                if (!found) {
                    log.warn("[ FILTER ] Label not found: [{}] → \"{}\"", filterId, wanted);
                }
            } catch (NoSuchElementException e) {
                log.debug("[ FILTER ] Filter panel [{}] not present on this SRP.", filterId);
            } catch (Exception e) {
                log.warn("[ FILTER ] Error on [{}] → \"{}\": {}", filterId, wanted, e.getMessage());
            }
        }
        return clicked;
    }

    // ── Step 3: freshness dropdown ────────────────────────────

    /**
     * Freshness is a single-select dropdown (button + ul menu).
     * Naukri HTML:
     *   button#filter-freshness  → click to open
     *   ul[data-filter-id="freshness"] li a[data-id="filter-freshness-7"]  etc.
     *
     * Mapping: "Last N days" → data-id suffix = N
     *          "Last 1 day"  → data-id suffix = 1
     */
    private int applyFreshnessFilter(String freshness) {
        try {
            // Map label to the data-id number
            String suffix = freshnessToSuffix(freshness);
            if (suffix == null) {
                log.warn("[ FILTER ] Unknown freshness value: \"{}\"", freshness);
                return 0;
            }

            // Open dropdown using JS click — avoids header interception
            WebElement btn = driver.findElement(By.id("filter-freshness"));
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center'}); arguments[0].click();", btn);
            sleep(400);

            // Click the matching item
            String dataId = "filter-freshness-" + suffix;
            WebElement item = driver.findElement(By.cssSelector(
                    "ul[data-filter-id='freshness'] a[data-id='" + dataId + "']"));
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", item);
            sleep(AppConfig.getFilterClickDelayMs());
            log.info("[ FILTER ] ✓ Freshness set to: \"{}\"", freshness);
            return 1;
        } catch (NoSuchElementException e) {
            log.debug("[ FILTER ] Freshness filter not present on this page.");
            return 0;
        } catch (Exception e) {
            log.warn("[ FILTER ] Freshness filter error: {}", e.getMessage());
            return 0;
        }
    }

    private String freshnessToSuffix(String label) {
        if (label == null) return null;
        String l = label.trim().toLowerCase();
        if (l.contains("1 day"))  return "1";
        if (l.contains("3 day"))  return "3";
        if (l.contains("7 day"))  return "7";
        if (l.contains("15 day")) return "15";
        if (l.contains("30 day")) return "30";
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────

    /** Expand "View More" link in a filter section if target label isn't visible. */
    private void expandIfNeeded(WebElement section, String wantedLower) {
        try {
            // Check if the label is already visible
            List<WebElement> visible = section.findElements(By.cssSelector("label.styles_chkLbl__n2x09"));
            boolean alreadyVisible = visible.stream()
                    .anyMatch(l -> l.getText().toLowerCase().contains(wantedLower));
            if (!alreadyVisible) {
                WebElement viewMore = section.findElement(By.cssSelector("a.styles_read-more-link__DU4hQ"));
                viewMore.click();
                sleep(500);
            }
        } catch (Exception ignored) {}
    }

    /** Check if a label's associated checkbox is already ticked. */
    private boolean isChecked(WebElement label) {
        try {
            // The checkbox input precedes the label — go up to parent, find input
            WebElement parent = label.findElement(By.xpath(".."));
            WebElement input  = parent.findElement(By.cssSelector("input[type='checkbox']"));
            return input.isSelected();
        } catch (Exception e) {
            // Fallback: check for checked icon class
            return label.findElements(By.cssSelector("i.ni-icon-checked")).size() > 0;
        }
    }

    private void clickWithRetry(WebElement el, int retries) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        for (int i = 0; i < retries; i++) {
            try {
                // Scroll element to center of viewport — avoids sticky header overlap
                js.executeScript(
                        "arguments[0].scrollIntoView({block: 'center', inline: 'nearest'});", el);
                sleep(300);
                // Always use JS click — native click hits the sticky Naukri header
                js.executeScript("arguments[0].click();", el);
                return;
            } catch (StaleElementReferenceException ex) {
                if (i == retries - 1) throw ex;
                sleep(500);
            }
        }
    }

    /** Wait for job card count to stabilise after filter clicks. */
    private void waitForResultsRefresh() {
        try {
            // Short pause for Naukri's live reload
            sleep(2000);
            new WebDriverWait(driver, Duration.ofSeconds(8)).until(
                    ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector("div.srp-jobtuple-wrapper")));
        } catch (Exception ignored) {}
    }

    // ── Tiny JSON helpers (no external lib needed) ────────────

    private List<String> extractJsonArray(String json, String key) {
        List<String> result = new ArrayList<>();
        try {
            // Find "key": [ ... ]
            String pattern = "\"" + key + "\"\\s*:\\s*\\[([^\\]]*)]";
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(pattern).matcher(json);
            if (m.find()) {
                String inner = m.group(1).trim();
                if (inner.isEmpty()) return result;
                for (String part : inner.split(",")) {
                    String val = part.trim().replaceAll("^\"|\"$", "").trim();
                    if (!val.isEmpty()) result.add(val);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private String extractJsonString(String json, String key) {
        try {
            String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(pattern).matcher(json);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {}
        return "";
    }
}