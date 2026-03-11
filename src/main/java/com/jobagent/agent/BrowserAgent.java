package com.jobagent.agent;

import com.jobagent.config.AppConfig;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Base class that manages the shared Chrome session.
 * All browser agents inherit from this.
 */
public abstract class BrowserAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected WebDriver driver;
    protected WebDriverWait wait;

    // ── Session lifecycle ─────────────────────────────────────

    protected void startBrowser() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions opts = new ChromeOptions();
        if (AppConfig.isChromeHeadless()) {
            opts.addArguments("--headless=new");
        }
        opts.addArguments(
            "--start-maximized",
            "--disable-blink-features=AutomationControlled",
            "--disable-notifications",
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu"
        );
        opts.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        opts.setExperimentalOption("useAutomationExtension", false);

        driver = new ChromeDriver(opts);
        wait   = new WebDriverWait(driver, Duration.ofSeconds(20));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
        log.info("[ BROWSER ] Chrome started.");
    }

    public void quitSession() {
        if (driver != null) {
            try { driver.quit(); } catch (Exception ignored) {}
            driver = null;
            log.info("[ BROWSER ] Chrome closed.");
        }
    }

    public WebDriver getDriver() { return driver; }
    public WebDriverWait getWait() { return wait; }

    /** Share an already-running driver (used by sub-agents that don't own the browser). */
    public void setDriver(WebDriver driver, WebDriverWait wait) {
        this.driver = driver;
        this.wait   = wait;
    }

    // ── Convenience helpers ────────────────────────────────────

    protected void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    protected boolean isPresent(By by) {
        try { return !driver.findElements(by).isEmpty(); }
        catch (Exception e) { return false; }
    }

    protected String safeText(By by) {
        try { return driver.findElement(by).getText().trim(); }
        catch (Exception e) { return ""; }
    }

    protected void safeClick(By by) {
        try {
            WebElement el = wait.until(ExpectedConditions.elementToBeClickable(by));
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", el);
            el.click();
        } catch (Exception e) {
            log.warn("[ BROWSER ] Could not click {}: {}", by, e.getMessage());
        }
    }

    protected String currentUrl() {
        try { return driver.getCurrentUrl(); } catch (Exception e) { return ""; }
    }
}
