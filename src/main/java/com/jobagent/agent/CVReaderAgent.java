package com.jobagent.agent;

import com.jobagent.config.AppConfig;
import com.jobagent.service.GroqClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Step 1 — Reads the PDF CV and extracts:
 *   • Full plain-text
 *   • Current roles / technologies
 *   • Smart job-search keywords (via Groq)
 */
public class CVReaderAgent {

    private static final Logger log = LoggerFactory.getLogger(CVReaderAgent.class);

    private final GroqClient groq;

    public CVReaderAgent(GroqClient groq) {
        this.groq = groq;
    }

    /** Load CV PDF and return raw text. */
    public String readCv() throws IOException {
        Path cvPath = Paths.get(AppConfig.getCvFilePath());
        if (!Files.exists(cvPath)) {
            throw new FileNotFoundException("CV not found at: " + cvPath.toAbsolutePath());
        }
        try (PDDocument doc = Loader.loadPDF(cvPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc).trim();
            log.info("[ CV ] Loaded {} chars from {}", text.length(), cvPath.getFileName());
            return text;
        }
    }

    /**
     * Ask Groq to infer the candidate's current roles and domain from the CV text.
     * Returns a short paragraph (for logging / info only).
     */
    public String inferRolesFromCv(String cvText) {
        String prompt = """
            You are a career advisor. Read the following CV and in 2-3 sentences describe:
            1) The candidate's current and most recent job roles/titles.
            2) Their primary technology domain and skill areas.
            
            CV TEXT:
            """ + cvText.substring(0, Math.min(6000, cvText.length()));

        return groq.chat("You are a career advisor.", prompt);
    }

    /**
     * Ask Groq to generate the best Naukri job-search keywords from the CV.
     * Returns a list of keyword strings.
     */
    public List<String> generateKeywordsFromCv(int count) {
        String cvText = "";
        try { cvText = readCv(); } catch (Exception e) {
            log.error("[ CV ] Could not re-read CV for keyword generation: {}", e.getMessage());
            return AppConfig.getJobSearchKeywords();
        }

        String prompt = String.format("""
            You are a recruitment expert. Read the CV below and generate exactly %d job-search keywords
            that are most likely to appear in relevant Naukri job postings for this candidate.
            
            Rules:
            - Each keyword should be a job title or role (e.g. "Senior QA Engineer", "Test Automation Lead")
            - Order from most specific/senior to more general
            - Return ONLY a plain numbered list, one keyword per line, no extra text
            
            CV TEXT:
            %s
            """, count, cvText.substring(0, Math.min(6000, cvText.length())));

        String response = groq.chat("You are a recruitment keyword expert.", prompt);
        List<String> keywords = new ArrayList<>();
        for (String line : response.split("\\n")) {
            String kw = line.replaceAll("^\\d+[.\\)\\-]\\s*", "").trim();
            if (!kw.isEmpty()) keywords.add(kw);
        }
        if (keywords.isEmpty()) {
            log.warn("[ CV ] Keyword generation returned empty list — using static keywords.");
            return AppConfig.getJobSearchKeywords();
        }
        return keywords.subList(0, Math.min(count, keywords.size()));
    }
}
