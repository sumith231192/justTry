package com.jobagent.service;

import com.jobagent.config.AppConfig;
import com.jobagent.model.JobListing;
import com.jobagent.model.KeywordStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Writes ONLY jobs that need manual action to the output .txt file.
 *
 * Written to file:
 *   ⬈  External redirect   — Apply opened a non-Naukri URL
 *   ⚠  Apply failed/unclear — Could not confirm submission
 *   📋 Link-only mode       — applyForJob=false
 *
 * NOT written to file:
 *   ✅ Easy Apply success  — already done, no action needed
 *   ❌ Below threshold     — not a good match
 *
 * Each entry includes: CV match %, keyword match %, matched keyword list,
 * all job metadata, and the external apply URL prominently at the top.
 */
public class OutputWriterService {

    private static final Logger            log = LoggerFactory.getLogger(OutputWriterService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path outPath;

    public OutputWriterService() {
        this.outPath = Paths.get(AppConfig.getJobLinksOutputFile());
        if (!Files.exists(outPath)) writeHeader();
    }

    // ── Public: write one job needing manual action ───────────

    public synchronized void writeExternal(JobListing job, String reason) {
        try (BufferedWriter bw = Files.newBufferedWriter(outPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            line(bw, "─", 80);

            // ── What you need to do ───────────────────────────
            boolean hasExtUrl = hasValue(job.getExternalUrl());
            if (hasExtUrl) {
                row(bw, "ACTION",     "⬈  APPLY AT EXTERNAL URL  (see APPLY HERE below)");
            } else if (reason.contains("applyForJob=false")) {
                row(bw, "ACTION",     "📋 VISIT NAUKRI URL AND APPLY MANUALLY");
            } else {
                row(bw, "ACTION",     "⚠  VISIT NAUKRI URL — apply outcome was unclear");
            }
            row(bw, "Note", reason);
            bw.newLine();

            // ── Match quality ─────────────────────────────────
            int    threshold = (int)(AppConfig.getJobMatchThreshold() * 100);
            row(bw, "CV Match",  job.getMatchScore() + "% out of 100%"
                    + "  (threshold: " + threshold + "%)");
            row(bw, "Keywords",  job.getKeywordsMatched() + " / "
                    + AppConfig.getJobSearchKeywords().size() + " matched"
                    + "  (" + job.getKeywordsMatchPct() + "%)");
            row(bw, "Kw list",   buildMatchedKeywords(job));
            bw.newLine();

            // ── Job details ───────────────────────────────────
            row(bw, "Title",      nvl(job.getTitle()));
            row(bw, "Company",    nvl(job.getCompany()));
            row(bw, "Location",   nvl(job.getLocation()));
            row(bw, "Experience", nvl(job.getExperience()));
            row(bw, "Salary",     nvl(job.getSalary()));
            row(bw, "Posted",     nvl(job.getPostedDate()));
            bw.newLine();

            // ── URLs ──────────────────────────────────────────
            row(bw, "Naukri URL", nvl(job.getApplyUrl()));
            if (hasExtUrl) {
                row(bw, "⬈ APPLY HERE", job.getExternalUrl());
            }
            bw.newLine();

            // ── JD snippet ────────────────────────────────────
            if (hasValue(job.getDescription())) {
                row(bw, "JD snippet",
                        truncate(job.getDescription().replaceAll("\\s+", " ").trim(), 350));
                bw.newLine();
            }

            row(bw, "Logged at", LocalDateTime.now().format(FMT));
            bw.newLine();

        } catch (IOException e) {
            log.error("[ OUTPUT ] Write error: {}", e.getMessage());
        }
    }

    // ── Public: run summary at end of pipeline ─────────────────────────────

    /**
     * Writes the full run summary to the .txt file:
     *   1. Per-keyword breakdown table
     *   2. Grand totals
     *   3. What you need to do (action guide)
     */
    public synchronized void writeSummary(
            List<String> orderedKeywords,
            Map<String, KeywordStats> kwStats,
            int totalApplied,
            int totalWritten,
            int totalSkipped,
            int totalRejected,
            long durationSecs,
            String mode) {

        try (BufferedWriter bw = Files.newBufferedWriter(outPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            String ts = LocalDateTime.now().format(FMT);

            line(bw, "═", 90);
            bw.write("  RUN SUMMARY  —  " + ts); bw.newLine();
            bw.write("  Mode : " + mode); bw.newLine();
            line(bw, "═", 90);
            bw.newLine();

            // ── Per-keyword table ─────────────────────────────────────────
            String hdr = String.format("  %-45s  %7s  %7s  %7s  %7s  %7s",
                    "Keyword", "Applied", "In-txt", "Skipped", "Reject", "Total");
            bw.write(hdr); bw.newLine();
            bw.write("  " + "─".repeat(87)); bw.newLine();

            for (String kw : orderedKeywords) {
                KeywordStats s = kwStats.get(kw);
                if (s == null) continue;
                bw.write(String.format("  %-45s  %7d  %7d  %7d  %7d  %7d",
                        truncate(kw, 45),
                        s.getApplied(),
                        s.getTotalWrittenToFile(),
                        s.getSkipped(),
                        s.getRejected(),
                        s.getTotalProcessed())); bw.newLine();
            }

            bw.write("  " + "─".repeat(87)); bw.newLine();
            bw.write(String.format("  %-45s  %7d  %7d  %7d  %7d  %7d",
                    "TOTAL",
                    totalApplied,
                    totalWritten,
                    totalSkipped,
                    totalRejected,
                    totalApplied + totalWritten + totalRejected)); bw.newLine();
            bw.newLine();

            // ── Grand total narrative ─────────────────────────────────────
            line(bw, "─", 90);
            bw.write(String.format("  ✅ Applied via Easy Apply : %3d  (done — no action needed, NOT listed above)", totalApplied)); bw.newLine();
            bw.write(String.format("  📄 Written to this file  : %3d  ← THESE NEED YOUR ACTION", totalWritten)); bw.newLine();
            bw.write(String.format("  ⏭  Skipped (duplicates)  : %3d  (same job seen in multiple keywords/pages)", totalSkipped)); bw.newLine();
            bw.write(String.format("  ❌ Rejected (low match)  : %3d  (below threshold — not listed)", totalRejected)); bw.newLine();
            line(bw, "─", 90);
            bw.write(String.format("  Total processed          : %3d",
                    totalApplied + totalWritten + totalRejected)); bw.newLine();
            bw.write(String.format("  Duration                 : %ds", durationSecs)); bw.newLine();
            bw.newLine();

            // ── Action guide ──────────────────────────────────────────────
            bw.write("  HOW TO ACTION THE " + totalWritten + " JOB(S) IN THIS FILE:"); bw.newLine();
            bw.write("  ⬈  External site  → open '⬈ APPLY HERE' URL and apply on company site"); bw.newLine();
            bw.write("  📋 applyForJob=false → open 'Naukri URL' and click Easy Apply manually"); bw.newLine();
            bw.write("  ⚠  Unclear outcome → open 'Naukri URL' and check if applied or re-apply"); bw.newLine();
            line(bw, "═", 90);
            bw.newLine();

        } catch (IOException e) {
            log.error("[ OUTPUT ] Summary error: {}", e.getMessage());
        }
    }

    // ── Header ────────────────────────────────────────────────

    private void writeHeader() {
        try (BufferedWriter bw = Files.newBufferedWriter(outPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            line(bw, "═", 80);
            bw.write("  Naukri Job Agent v2  —  Jobs Needing Manual Action"); bw.newLine();
            bw.write("  Generated  : " + LocalDateTime.now().format(FMT)); bw.newLine();
            bw.write("  Threshold  : " + (int)(AppConfig.getJobMatchThreshold() * 100) + "% CV match"); bw.newLine();
            bw.write("  Apply mode : " + (AppConfig.isApplyForJob()
                    ? "AUTO-APPLY ON  — Easy Apply success NOT listed (already done)"
                    : "AUTO-APPLY OFF — all matched jobs listed for manual apply")); bw.newLine();
            line(bw, "═", 80);
            bw.write("  Only jobs where YOU need to take action appear below."); bw.newLine();
            bw.write("  ✅ Easy Apply done    → not listed"); bw.newLine();
            bw.write("  ❌ Below CV threshold → not listed"); bw.newLine();
            line(bw, "═", 80);
            bw.newLine();
        } catch (IOException e) {
            log.error("[ OUTPUT ] Cannot create output file: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private String buildMatchedKeywords(JobListing job) {
        // Check JD body only — title excluded (varies per org, not a reliable signal)
        String hay = blank(job.getDescription()).toLowerCase();
        List<String> found = new ArrayList<>();
        for (String kw : AppConfig.getJobSearchKeywords())
            if (hay.contains(kw.toLowerCase())) found.add(kw);
        return found.isEmpty() ? "(none)" : String.join(", ", found);
    }

    private String blank(String s) { return (s != null) ? s : ""; }

    private void row(BufferedWriter bw, String label, String value) throws IOException {
        bw.write(String.format("  %-14s: %s", label, value == null ? "N/A" : value));
        bw.newLine();
    }

    private void line(BufferedWriter bw, String ch, int len) throws IOException {
        bw.write(ch.repeat(len)); bw.newLine();
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "…" : s);
    }

    private boolean hasValue(String s) { return s != null && !s.isBlank(); }
    private String  nvl(String s)      { return hasValue(s) ? s : "N/A"; }
}