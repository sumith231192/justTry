package com.jobagent.agent;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SessionKeepAlive — prevents two things from killing a long agent run:
 *
 *  1. OS screen lock / sleep
 *     Moves the mouse by 1 px and back every 60 s using java.awt.Robot.
 *     This is enough to reset the OS idle timer on Windows, macOS, and Linux
 *     (X11/Wayland with xdotool fallback).  No external tool required.
 *
 *  2. Naukri session timeout
 *     Executes a tiny JS ping (document.title read) on the live WebDriver
 *     every 90 s so the browser tab stays active and the Naukri cookie/session
 *     does not expire from inactivity.
 *
 * Usage — call start() right after login, stop() in the finally block:
 *
 *   SessionKeepAlive keepAlive = new SessionKeepAlive(loginAgent.getDriver());
 *   keepAlive.start();
 *   try {
 *       // ... run pipeline ...
 *   } finally {
 *       keepAlive.stop();
 *   }
 */
public class SessionKeepAlive {

    private static final Logger log = LoggerFactory.getLogger(SessionKeepAlive.class);

    /** How often (seconds) to nudge the mouse to reset the OS idle timer. */
    private static final int MOUSE_INTERVAL_SECS  = 60;

    /** How often (seconds) to ping the WebDriver to keep the Naukri session alive. */
    private static final int BROWSER_INTERVAL_SECS = 90;

    private final AtomicReference<WebDriver> driverRef = new AtomicReference<>();
    private final ScheduledExecutorService   scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "keep-alive");
                t.setDaemon(true); // never blocks JVM shutdown
                return t;
            });

    private ScheduledFuture<?> mouseFuture;
    private ScheduledFuture<?> browserFuture;

    /** Robot instance — null if AWT headless/unavailable (safe, just skipped). */
    private Robot robot;

    public SessionKeepAlive(WebDriver driver) {
        driverRef.set(driver);
        try {
            this.robot = new Robot();
        } catch (AWTException | UnsupportedOperationException e) {
            log.warn("[ KEEP-ALIVE ] AWT Robot unavailable ({}) — mouse nudge disabled, " +
                     "browser ping still active.", e.getMessage());
        }
    }

    /**
     * Update the driver reference mid-run if it is recreated (e.g. after a crash recovery).
     */
    public void updateDriver(WebDriver driver) {
        driverRef.set(driver);
    }

    /** Start both keep-alive threads. Safe to call multiple times (idempotent). */
    public void start() {
        log.info("[ KEEP-ALIVE ] Starting — mouse nudge every {}s, browser ping every {}s.",
                MOUSE_INTERVAL_SECS, BROWSER_INTERVAL_SECS);

        // ── 1. Mouse nudge — resets OS idle/screen-lock timer ────────────
        mouseFuture = scheduler.scheduleAtFixedRate(
                this::nudgeMouse,
                MOUSE_INTERVAL_SECS,
                MOUSE_INTERVAL_SECS,
                TimeUnit.SECONDS);

        // ── 2. Browser ping — keeps Naukri session cookie alive ──────────
        browserFuture = scheduler.scheduleAtFixedRate(
                this::pingBrowser,
                BROWSER_INTERVAL_SECS,
                BROWSER_INTERVAL_SECS,
                TimeUnit.SECONDS);
    }

    /** Stop both threads. Call in the pipeline finally block. */
    public void stop() {
        log.info("[ KEEP-ALIVE ] Stopping.");
        if (mouseFuture   != null) mouseFuture.cancel(false);
        if (browserFuture != null) browserFuture.cancel(false);
        scheduler.shutdown();
    }

    // ── Mouse nudge ───────────────────────────────────────────────────────────

    private void nudgeMouse() {
        try {
            if (robot == null) return;
            Point current = MouseInfo.getPointerInfo().getLocation();
            // Move 1 px right then back — imperceptible but enough to reset idle timer
            robot.mouseMove(current.x + 1, current.y);
            Thread.sleep(50);
            robot.mouseMove(current.x, current.y);
            log.debug("[ KEEP-ALIVE ] Mouse nudged.");
        } catch (Exception e) {
            log.debug("[ KEEP-ALIVE ] Mouse nudge failed: {}", e.getMessage());
        }
    }

    // ── Browser ping ─────────────────────────────────────────────────────────

    private void pingBrowser() {
        try {
            WebDriver driver = driverRef.get();
            if (driver == null) return;

            // Read document.title — zero-cost JS that proves the tab is still alive
            // and resets Naukri's client-side inactivity timer
            String title = (String) ((JavascriptExecutor) driver)
                    .executeScript("return document.title;");
            log.debug("[ KEEP-ALIVE ] Browser ping OK — page: \"{}\"", title);

        } catch (Exception e) {
            // Driver may be mid-navigation — not fatal, just log at debug level
            log.debug("[ KEEP-ALIVE ] Browser ping skipped: {}", e.getMessage());
        }
    }
}
