package com.jobagent.model;

/**
 * Represents a single job listing scraped from Naukri.
 */
public class JobListing {

    private String title;
    private String company;
    private String location;
    private String experience;
    private String salary;
    private String postedDate;
    private String description;
    private String applyUrl;       // direct Naukri job URL
    private String externalUrl;    // set if "Apply" button redirects off-site
    private String jobId;          // Naukri internal ID (for deduplication)
    private int    matchScore;         // AI CV match score 0-100
    private int    keywordsMatched;    // number of search keywords found in JD
    private double keywordsMatchPct;   // percentage of keywords matched


    // ── Getters / setters ─────────────────────────────────────

    public String getTitle()        { return title; }
    public void   setTitle(String t){ this.title = t; }

    public String getCompany()           { return company; }
    public void   setCompany(String c)   { this.company = c; }

    public String getLocation()          { return location; }
    public void   setLocation(String l)  { this.location = l; }

    public String getExperience()        { return experience; }
    public void   setExperience(String e){ this.experience = e; }

    public String getSalary()            { return salary; }
    public void   setSalary(String s)    { this.salary = s; }

    public String getPostedDate()        { return postedDate; }
    public void   setPostedDate(String d){ this.postedDate = d; }

    public String getDescription()       { return description; }
    public void   setDescription(String d){ this.description = d; }

    public String getApplyUrl()          { return applyUrl; }
    public void   setApplyUrl(String u)  { this.applyUrl = u; }

    public String getExternalUrl()       { return externalUrl; }
    public void   setExternalUrl(String u){ this.externalUrl = u; }

    public String getJobId()             { return jobId; }
    public void   setJobId(String id)    { this.jobId = id; }


    public int    getMatchScore()              { return matchScore; }
    public void   setMatchScore(int s)         { this.matchScore = s; }

    public int    getKeywordsMatched()         { return keywordsMatched; }
    public void   setKeywordsMatched(int k)    { this.keywordsMatched = k; }

    public double getKeywordsMatchPct()        { return keywordsMatchPct; }
    public void   setKeywordsMatchPct(double p){ this.keywordsMatchPct = p; }

    @Override
    public String toString() {
        return String.format("[%s] %s @ %s | %s | %s", jobId, title, company, location, experience);
    }
}