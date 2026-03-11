package com.jobagent.config;

import java.util.*;

/**
 * Immutable snapshot of all SRP filter selections.
 *
 * Each field is a List of labels exactly as they appear on Naukri's
 * filter panel (the text you see on screen).  An empty list means
 * "don't touch this filter".
 *
 * Built either from application.properties (static) or from a Groq
 * CV-analysis response (dynamic).
 */
public class FilterConfig {

    // ── Filter buckets (Naukri SRP left panel) ────────────────
    public final List<String> workMode;       // Work from office | Hybrid | Remote
    public final List<String> department;     // Engineering - Software & QA | ...
    public final List<String> roleCategory;   // Quality Assurance and Testing | ...
    public final List<String> companyType;    // Foreign MNC | Indian MNC | Corporate | Startup
    public final List<String> industry;       // IT Services & Consulting | Software Product | ...
    public final List<String> salary;         // 25-50 Lakhs | 50-75 Lakhs | ...
    public final String       freshness;      // Last 7 days  (single-select dropdown)
    public final List<String> postedBy;       // Company Jobs | Consultant Jobs
    public final List<String> topCompanies;   // Accenture | Infosys | ...  (optional whitelist)
    public final List<String> education;      // MCA | Any Postgraduate | ...

    private FilterConfig(Builder b) {
        this.workMode     = unmod(b.workMode);
        this.department   = unmod(b.department);
        this.roleCategory = unmod(b.roleCategory);
        this.companyType  = unmod(b.companyType);
        this.industry     = unmod(b.industry);
        this.salary       = unmod(b.salary);
        this.freshness    = b.freshness;
        this.postedBy     = unmod(b.postedBy);
        this.topCompanies = unmod(b.topCompanies);
        this.education    = unmod(b.education);
    }

    /** True if at least one filter has a non-empty selection. */
    public boolean hasAny() {
        return !workMode.isEmpty() || !department.isEmpty() || !roleCategory.isEmpty()
                || !companyType.isEmpty() || !industry.isEmpty() || !salary.isEmpty()
                || (freshness != null && !freshness.isBlank())
                || !postedBy.isEmpty() || !topCompanies.isEmpty() || !education.isEmpty();
    }

    @Override
    public String toString() {
        return String.format(
                "FilterConfig{workMode=%s, dept=%s, role=%s, company=%s, industry=%s, " +
                        "salary=%s, freshness='%s', postedBy=%s, topCo=%s, edu=%s}",
                workMode, department, roleCategory, companyType, industry,
                salary, freshness, postedBy, topCompanies, education);
    }

    // ── Static factory from Properties ───────────────────────

    public static FilterConfig fromProperties(java.util.Properties p) {
        return new Builder()
                .workMode    (csv(p, "filters.workMode"))
                .department  (csv(p, "filters.department"))
                .roleCategory(csv(p, "filters.roleCategory"))
                .companyType (csv(p, "filters.companyType"))
                .industry    (csv(p, "filters.industry"))
                .salary      (csv(p, "filters.salary"))
                .freshness   (str(p, "filters.freshness"))
                .postedBy    (csv(p, "filters.postedBy"))
                .topCompanies(csv(p, "filters.topCompanies"))
                .education   (csv(p, "filters.education"))
                .build();
    }

    private static List<String> csv(java.util.Properties p, String key) {
        return AppConfig.splitCsv(p.getProperty(key, ""));
    }
    private static String str(java.util.Properties p, String key) {
        String v = p.getProperty(key, "");
        return v.trim();
    }

    // ── Builder ───────────────────────────────────────────────

    public static class Builder {
        List<String> workMode     = new ArrayList<>();
        List<String> department   = new ArrayList<>();
        List<String> roleCategory = new ArrayList<>();
        List<String> companyType  = new ArrayList<>();
        List<String> industry     = new ArrayList<>();
        List<String> salary       = new ArrayList<>();
        String       freshness    = "";
        List<String> postedBy     = new ArrayList<>();
        List<String> topCompanies = new ArrayList<>();
        List<String> education    = new ArrayList<>();

        public Builder workMode(List<String> v)     { workMode     = v; return this; }
        public Builder department(List<String> v)   { department   = v; return this; }
        public Builder roleCategory(List<String> v) { roleCategory = v; return this; }
        public Builder companyType(List<String> v)  { companyType  = v; return this; }
        public Builder industry(List<String> v)     { industry     = v; return this; }
        public Builder salary(List<String> v)       { salary       = v; return this; }
        public Builder freshness(String v)          { freshness    = v; return this; }
        public Builder postedBy(List<String> v)     { postedBy     = v; return this; }
        public Builder topCompanies(List<String> v) { topCompanies = v; return this; }
        public Builder education(List<String> v)    { education    = v; return this; }
        public FilterConfig build()                 { return new FilterConfig(this); }
    }

    private static List<String> unmod(List<String> l) {
        return Collections.unmodifiableList(l == null ? new ArrayList<>() : l);
    }
}