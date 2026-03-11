package com.jobagent.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe counters for a single search keyword.
 *
 * Multiple parallel workers process different pages of the same keyword
 * simultaneously, so all fields are AtomicInteger.
 *
 * Counters:
 *   applied   — Easy Apply submitted successfully on Naukri
 *   external  — Apply redirected to external company site (written to .txt)
 *   linkOnly  — applyForJob=false; matched job written to .txt for manual apply
 *   rejected  — CV match score below threshold; skipped entirely
 *   skipped   — jobId already seen (duplicate across keywords/pages/workers)
 *
 * Derived:
 *   totalWrittenToFile = external + linkOnly
 *   totalMatched       = applied + external + linkOnly
 *   totalProcessed     = totalMatched + rejected   (skipped are not counted — never opened)
 */
public class KeywordStats {

    public final String keyword;

    private final AtomicInteger applied  = new AtomicInteger();
    private final AtomicInteger external = new AtomicInteger();
    private final AtomicInteger linkOnly = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private final AtomicInteger skipped  = new AtomicInteger();

    public KeywordStats(String keyword) {
        this.keyword = keyword;
    }

    public void incApplied()  { applied.incrementAndGet();  }
    public void incExternal() { external.incrementAndGet(); }
    public void incLinkOnly() { linkOnly.incrementAndGet(); }
    public void incRejected() { rejected.incrementAndGet(); }
    public void incSkipped()  { skipped.incrementAndGet();  }

    public int getApplied()  { return applied.get();  }
    public int getExternal() { return external.get(); }
    public int getLinkOnly() { return linkOnly.get(); }
    public int getRejected() { return rejected.get(); }
    public int getSkipped()  { return skipped.get();  }

    public int getTotalWrittenToFile() { return external.get() + linkOnly.get(); }
    public int getTotalMatched()       { return applied.get() + external.get() + linkOnly.get(); }
    public int getTotalProcessed()     { return getTotalMatched() + rejected.get(); }
}
