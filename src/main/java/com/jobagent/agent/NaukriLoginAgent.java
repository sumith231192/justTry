package com.jobagent.agent;

import com.jobagent.config.AppConfig;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.*;

import java.time.Duration;

/**
 * Step 2 — Opens Chrome, navigates to Naukri, and logs in.
 * Owns the shared browser session; hands it off to other agents.
 */
public class NaukriLoginAgent extends BrowserAgent {

    private static final String LOGIN_URL = "https://www.naukri.com/";

    /** Launch browser + login. Call once per pipeline run. */
    public void loginAndStart() {
        startBrowser();
        login();
    }

    private void login() {
        log.info("[ LOGIN ] Navigating to Naukri...");
        driver.get(LOGIN_URL);
        sleep(2000);

        // Close any popup / overlay
        dismissPopup();

        // Click the Login button in the header
        try {
            WebElement loginBtn = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//a[contains(@href,'login') and (contains(text(),'Login') or contains(text(),'login'))]")));
            loginBtn.click();
            sleep(1500);
        } catch (Exception e) {
            log.warn("[ LOGIN ] Header login link not found — trying direct URL.");
            driver.get("https://www.naukri.com/nlogin/login");
            sleep(2000);
        }

        // Fill email
        try {
            WebElement emailInput = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//input[@type='text' and (@placeholder or @id)]")));
            emailInput.clear();
            emailInput.sendKeys(AppConfig.getNaukriUsername());
            sleep(500);
        } catch (Exception e) {
            log.error("[ LOGIN ] Cannot find email input: {}", e.getMessage());
            throw new RuntimeException("Naukri login failed — email field not found");
        }

        // Fill password
        try {
            WebElement pwdInput = driver.findElement(By.xpath("//input[@type='password']"));
            pwdInput.clear();
            pwdInput.sendKeys(AppConfig.getNaukriPassword());
            sleep(500);
        } catch (Exception e) {
            log.error("[ LOGIN ] Cannot find password input: {}", e.getMessage());
            throw new RuntimeException("Naukri login failed — password field not found");
        }

        // Submit
        try {
            WebElement submit = driver.findElement(By.xpath(
                "//button[@type='submit' or contains(text(),'Login') or contains(text(),'login')]"));
            submit.click();
        } catch (Exception e) {
            // Try Enter key fallback
            driver.findElement(By.xpath("//input[@type='password']")).sendKeys(Keys.RETURN);
        }

        sleep(3000);

        // Verify logged in — look for profile icon or logged-in nav items
        try {
            wait.withTimeout(Duration.ofSeconds(10)).until(
                ExpectedConditions.or(
                    ExpectedConditions.presenceOfElementLocated(By.cssSelector(".nI-gNb-header__profile")),
                    ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-ga-track*='profile']")),
                    ExpectedConditions.urlContains("naukri.com")
                )
            );
            log.info("[ LOGIN ] ✓ Logged in successfully.");
        } catch (Exception e) {
            log.warn("[ LOGIN ] Could not confirm login — proceeding anyway.");
        }

        dismissPopup();
    }

    private void dismissPopup() {
        try {
            WebElement close = driver.findElement(
                By.xpath("//button[contains(@class,'close') or @aria-label='Close' or contains(text(),'×')]"));
            close.click();
            sleep(500);
        } catch (Exception ignored) {}
    }
}
