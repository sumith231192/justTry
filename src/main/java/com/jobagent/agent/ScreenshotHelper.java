package com.jobagent.agent;

import org.openqa.selenium.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Saves a screenshot when an error occurs — useful for debugging.
 */
public class ScreenshotHelper {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotHelper.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static void captureOnError(WebDriver driver, String context, Exception e) {
        if (driver == null) return;
        try {
            TakesScreenshot ts = (TakesScreenshot) driver;
            byte[] bytes = ts.getScreenshotAs(OutputType.BYTES);
            String filename = String.format("error_%s_%s.png", context, LocalDateTime.now().format(FMT));
            Path out = Paths.get(filename);
            Files.write(out, bytes);
            log.info("[ SS ] Screenshot saved: {}", out.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("[ SS ] Could not save screenshot: {}", ex.getMessage());
        }
    }
}
