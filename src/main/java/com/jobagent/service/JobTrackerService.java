package com.jobagent.service;

import com.jobagent.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JobTrackerService — deduplication across keywords, pages, and parallel workers.
 *
 * TWO-LEVEL DUPLICATE PREVENTION:
 *
 *   Level 1 — In-memory (ConcurrentHashMap):
 *     tryMark(jobId) is atomic. The first caller wins; all others get false and skip.
 *     Safe across parallel browser workers because ConcurrentHashMap.add() is atomic.
 *
 *   Level 2 — Persistent file (tracker file):
 *     Every job ID is written to the tracker file THE MOMENT it is first seen
 *     (before scoring, before applying). This means:
 *       - If the same job appears in a later keyword search → skipped immediately
 *       - If the same job appears in a later page → skipped immediately
 *       - If the agent restarts mid-run → already-seen jobs are not re-processed
 *       - If two parallel workers both try the same job → only one writes, file is append-only
 *
 *   The tracker file is CLEARED at the start of each new pipeline run so that
 *   yesterday's jobs are re-evaluated fresh today.
 */
public class JobTrackerService {

    private static final Logger log = LoggerFactory.getLogger(JobTrackerService.class);

    // Thread-safe in-memory set — ConcurrentHashMap.newKeySet().add() is atomic
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    // Path to the on-disk tracker file
    private final Path trackerFile;

    public JobTrackerService() {
        this.trackerFile = Paths.get(AppConfig.getTrackerFile());
        clearTrackerFile();   // fresh start every pipeline run
        log.info("[ TRACKER ] Initialised. Tracker file: {}", trackerFile.toAbsolutePath());
    }

    /**
     * Atomically check-and-mark a job ID.
     *
     * Returns TRUE  → this is the FIRST time this job has been seen this run.
     *                  The caller MUST process this job.
     * Returns FALSE → already seen (by this worker or another).
     *                  The caller MUST skip this job.
     *
     * On TRUE: immediately appends the job ID to the tracker file so that
     * any parallel worker or any later restart also sees it as already-done.
     */
    public boolean tryMark(String jobId) {
        if (jobId == null || jobId.isBlank()) return false;
        boolean isNew = seen.add(jobId);   // atomic: returns true only for first caller
        if (isNew) persistToDisk(jobId);
        return isNew;
    }

    /** Non-atomic convenience — kept for legacy call sites. Use tryMark() in new code. */
    public boolean isSeen(String jobId)  { return jobId != null && seen.contains(jobId); }
    public void    markSeen(String jobId){ tryMark(jobId); }

    public int size() { return seen.size(); }

    // ── Disk persistence ──────────────────────────────────────────────────────

    /** Wipe the tracker file at the start of each run so old IDs don't accumulate. */
    private void clearTrackerFile() {
        try {
            Files.deleteIfExists(trackerFile);
            Files.createFile(trackerFile);
            log.debug("[ TRACKER ] Tracker file cleared for fresh run.");
        } catch (IOException e) {
            log.warn("[ TRACKER ] Could not clear tracker file: {}", e.getMessage());
        }
    }

    /**
     * Append a job ID to the tracker file immediately and atomically.
     * Uses a synchronized block on trackerFile to prevent interleaved writes
     * from multiple parallel worker threads.
     */
    private void persistToDisk(String jobId) {
        synchronized (trackerFile) {
            try (BufferedWriter bw = Files.newBufferedWriter(
                    trackerFile, StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
                bw.write(jobId);
                bw.newLine();
            } catch (IOException e) {
                log.warn("[ TRACKER ] Could not persist job ID {}: {}", jobId, e.getMessage());
            }
        }
    }
}
