package com.hargun.crawler;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.FileWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkedIn Tech Jobs Crawler (COMP 8547 - Assignment 1)
 *
 * Website: LinkedIn Jobs (https://www.linkedin.com/jobs) - public job listings for Canada.
 *
 * Task 1 - searchOnWebsite():
 *     Opens the LinkedIn Jobs website, types a keyword and location into the search
 *     text boxes, clicks the search button, and extracts the job cards from the results.
 *
 * Task 2 - crawlProvinces():
 *     Crawls several result pages for every province/territory and combines everything
 *     into one list (duplicates are merged into a "Vacancies" count).
 *
 * Task 3 - advanced Selenium commands:
 *     - Explicit waits (WebDriverWait + ExpectedConditions) instead of fixed sleeps
 *     - Handling LinkedIn's sign-in pop-up window (detect, screenshot, dismiss)
 *     - JavaScript execution (scrolling, fallback clicks)
 *     - Screenshots (TakesScreenshot) saved to the "screenshots" folder
 *
 * Output: tech_jobs.csv
 */
public class App2 {

    // ------------------------------------------------------------------
    // SETTINGS
    // ------------------------------------------------------------------

    /** Keyword and location typed into the website's search boxes (Task 1). */
    static final String SEARCH_KEYWORD = "software developer";
    static final String SEARCH_LOCATION = "Canada";

    /** Keywords for the multi-page crawl (Task 2). OR = match any of these. */
    static final String KEYWORDS =
            "software OR developer OR programmer OR \"data analyst\" OR \"data engineer\" OR devops"
            + " OR \"IT support\" OR cybersecurity OR \"cloud engineer\"";

    /** Main LinkedIn Jobs page (the real website). */
    static final String HOME_URL = "https://www.linkedin.com/jobs";

    /** LinkedIn's public "guest" listing pages, used to page through results quickly. */
    static final String GUEST_URL =
            "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search?keywords="
            + enc(KEYWORDS) + "&location=";

    /** Every province and territory gets its own search. */
    static final String[] LOCATIONS = {
            "Ontario, Canada", "Quebec, Canada", "British Columbia, Canada", "Alberta, Canada",
            "Manitoba, Canada", "Saskatchewan, Canada", "Nova Scotia, Canada",
            "New Brunswick, Canada", "Newfoundland and Labrador, Canada",
            "Prince Edward Island, Canada", "Yukon, Canada",
            "Northwest Territories, Canada", "Nunavut, Canada"
    };

    /** Maximum job cards to read per province (LinkedIn returns about 10 per page). */
    static final int MAX_PER_SEARCH = 10;

    static final String OUT_FILE = "tech_jobs.csv";
    static final String SHOTS_DIR = "screenshots";

    /** CSS selector for one job card (same on the website and on the guest pages). */
    static final By JOB_CARD = By.cssSelector("div.base-card");

    /** Possible "close" buttons of LinkedIn's sign-in pop-up. */
    static final String POPUP_CLOSE =
            "button.modal__dismiss, button.contextual-sign-in-modal__modal-dismiss, "
            + "button[data-tracking-control-name*='modal_dismiss'], button[aria-label='Dismiss']";

    /** A job title must contain one of these words to count as a tech job. */
    static final Pattern TECH_TITLE = Pattern.compile(
            "(?i)\\b(developer|software|programmer|engineer|data|devops|cloud|cyber|security|"
            + "qa|full[- ]?stack|front[- ]?end|back[- ]?end|web|machine learning|ai|"
            + "network|system|database|analyst|architect|technical support|help ?desk)\\b|\\bIT\\b");

    /** ...but titles with these words are not tech jobs (e.g. "Sales Engineer"). */
    static final Pattern NOT_TECH = Pattern.compile(
            "(?i)\\b(sales|civil|mechanical|structural|chemical|maintenance|construction|"
            + "financial analyst|business analyst)\\b");

    /** Finds a pay figure in text, e.g. "$60,000 - $75,000 per year" or "CA$22/hr". */
    static final Pattern SALARY = Pattern.compile(
            "(?i)(CA)?\\$\\s?\\d[\\d,]*(\\.\\d+)?\\s?k?"
            + "(\\s?(-|–|to)\\s?(CA)?\\$?\\s?\\d[\\d,]*(\\.\\d+)?\\s?k?)?"
            + "(\\s?(/|per|an|a)\\s?(hour|hr|year|yr|annum|month|week))?");

    // ------------------------------------------------------------------
    // DATA
    // ------------------------------------------------------------------

    /** One job. Repeated postings of the same job are merged and counted in "vacancies". */
    static class Job {
        String title, company, location, salary, posted, url, logo;
        int vacancies = 1;
    }

    static WebDriver driver;
    static WebDriverWait wait;                               // explicit wait, up to 10 seconds
    static int shotNumber = 1;                               // numbers the screenshot files

    static Set<String> seenUrls = new HashSet<>();           // job pages already read
    static Map<String, Job> jobs = new LinkedHashMap<>();    // merged jobs, in order found

    // ------------------------------------------------------------------
    // MAIN
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        // Set up Chrome. Headless = no visible window (needed in GitHub Codespaces).
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
                "--window-size=1366,900");
        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        Files.createDirectories(Paths.get(SHOTS_DIR));

        try {
            searchOnWebsite();   // Task 1 (+ Task 3 waits and pop-up)
            crawlProvinces();    // Task 2
            fetchAllSalaries();  // extra detail from each job's own page
        } finally {
            driver.quit();       // always close the browser, even after an error
        }

        writeCsv();
        System.out.println("\nDONE. " + jobs.size() + " tech jobs saved to " + OUT_FILE);
    }

    // ------------------------------------------------------------------
    // TASK 1: open the website, use the search boxes and button, extract results
    // ------------------------------------------------------------------

    static void searchOnWebsite() {
        System.out.println("=== Task 1: searching on the LinkedIn Jobs website ===");
        try {
            // 1. Open the website and wait until the page body exists
            driver.get(HOME_URL);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
            screenshot("home_page");

            // 2. Close the sign-in pop-up if LinkedIn shows one (Task 3)
            closePopup();

            // 3. Type into the keyword text box
            WebElement keywordBox = firstVisible("input[name='keywords'], #job-search-bar-keywords");
            keywordBox.clear();
            keywordBox.sendKeys(SEARCH_KEYWORD);

            // 4. Type into the location text box (select-all + delete removes any old value)
            WebElement locationBox = firstVisible("input[name='location'], #job-search-bar-location");
            locationBox.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.DELETE);
            locationBox.sendKeys(SEARCH_LOCATION);
            screenshot("search_boxes_filled");

            // 5. Click the search button (press Enter if the button can't be found)
            try {
                safeClick(firstVisible("button.base-search-bar__submit-btn, button[type='submit']"));
            } catch (TimeoutException e) {
                keywordBox.sendKeys(Keys.ENTER);
            }

            // 6. Wait until the result cards have loaded (explicit wait, Task 3)
            wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(JOB_CARD));
            closePopup();  // the pop-up often appears again after searching

            // 7. Scroll down with JavaScript so more results load, then wait briefly
            ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 1500);");
            pause(1500);
            screenshot("search_results");

            // 8. Extract the job cards from the results page
            List<WebElement> cards = driver.findElements(JOB_CARD);
            System.out.println("Results on website: " + cards.size() + " job cards");
            readCards(cards);

        } catch (Exception e) {
            // LinkedIn sometimes redirects to a login page; the crawl in Task 2 still runs.
            System.out.println("Website search could not finish (" + e.getClass().getSimpleName()
                    + "). Current page: " + driver.getCurrentUrl());
            screenshot("task1_problem");
        }
    }

    // ------------------------------------------------------------------
    // TASK 3: handle the sign-in pop-up window
    // ------------------------------------------------------------------

    /** Waits up to 4 seconds for the sign-in pop-up. If it appears, screenshot and close it. */
    static void closePopup() {
        try {
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(4));
            WebElement closeBtn = shortWait.until(d -> {
                for (WebElement b : d.findElements(By.cssSelector(POPUP_CLOSE))) {
                    if (b.isDisplayed()) return b;
                }
                return null; // not visible yet, keep waiting
            });
            screenshot("popup_shown");
            safeClick(closeBtn);
            shortWait.until(ExpectedConditions.invisibilityOf(closeBtn)); // wait until it's gone
            System.out.println("Sign-in pop-up detected and closed.");
        } catch (Exception e) {
            // No pop-up appeared within 4 seconds, nothing to do.
        }
    }

    // ------------------------------------------------------------------
    // TASK 2: crawl several pages per province and combine the results
    // ------------------------------------------------------------------

    static void crawlProvinces() {
        System.out.println("\n=== Task 2: crawling every province ===");
        boolean firstPage = true;

        for (String location : LOCATIONS) {
            System.out.println("\n--- " + location + " ---");
            String searchUrl = GUEST_URL + enc(location) + "&start=";
            int start = 0;

            while (start < MAX_PER_SEARCH) {
                driver.get(searchUrl + start);
                List<WebElement> cards = waitForCards();  // explicit wait (Task 3)
                System.out.println("start=" + start + ": " + cards.size()
                        + " cards, total tech jobs: " + jobs.size());

                if (cards.isEmpty()) break;              // no more results for this province
                if (firstPage) { screenshot("listing_page"); firstPage = false; }

                start += cards.size();                   // next page starts after these cards
                readCards(cards);
                pause(1500);                             // polite gap so LinkedIn doesn't block us
            }
        }
    }

    /** Waits up to 8 seconds for job cards. Returns an empty list if none appear. */
    static List<WebElement> waitForCards() {
        try {
            return new WebDriverWait(driver, Duration.ofSeconds(8))
                    .until(ExpectedConditions.presenceOfAllElementsLocatedBy(JOB_CARD));
        } catch (TimeoutException e) {
            return Collections.emptyList();
        }
    }

    /** Reads every job card, keeps only tech jobs, and merges repeated postings. */
    static void readCards(List<WebElement> cards) {
        for (WebElement card : cards) {
            String url = attr(card, "a.base-card__full-link", "href").split("\\?")[0];
            if (url.isEmpty() || !seenUrls.add(url)) continue;   // skip pages already read

            Job job = new Job();
            job.title = text(card, ".base-search-card__title");
            job.company = text(card, ".base-search-card__subtitle");
            job.location = text(card, ".job-search-card__location");
            job.salary = text(card, ".job-search-card__salary-info");
            job.posted = text(card, "time");
            job.url = url;
            job.logo = attr(card, "img", "data-delayed-url");    // company logo image
            if (job.logo.isEmpty()) job.logo = attr(card, "img", "src");

            // Skip anything that isn't a tech job
            if (!TECH_TITLE.matcher(job.title).find() || NOT_TECH.matcher(job.title).find()) continue;

            // Same title + company + location = same job, so count another vacancy
            String key = (job.title + "|" + job.company + "|" + job.location).toLowerCase();
            Job existing = jobs.get(key);
            if (existing != null) {
                existing.vacancies++;
                if (existing.salary.isEmpty()) existing.salary = job.salary;
            } else {
                jobs.put(key, job);
            }
        }
    }

    // ------------------------------------------------------------------
    // SALARY: open each job's own page to find the pay
    // ------------------------------------------------------------------

    static void fetchAllSalaries() {
        System.out.println("\n=== Finding salaries ===");
        int i = 0;
        boolean firstJob = true;

        for (Job job : jobs.values()) {
            i++;
            if (!job.salary.isEmpty()) continue;         // already found on the card
            job.salary = fetchSalary(job.url, firstJob);
            firstJob = false;
            System.out.println("salary " + i + "/" + jobs.size() + ": "
                    + (job.salary.isEmpty() ? "-" : job.salary) + " | " + job.title);
        }
    }

    static String fetchSalary(String jobUrl, boolean takeScreenshot) {
        try {
            // The job ID is the number at the end of the job URL
            Matcher id = Pattern.compile("(\\d+)$").matcher(jobUrl);
            if (!id.find()) return "";

            driver.get("https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/" + id.group(1));

            // Wait for the salary box or the description to load
            try {
                new WebDriverWait(driver, Duration.ofSeconds(6)).until(
                        ExpectedConditions.presenceOfElementLocated(
                                By.cssSelector(".compensation__salary, .show-more-less-html__markup")));
            } catch (TimeoutException ignored) {
                // page loaded without these parts; we still try below
            }
            if (takeScreenshot) screenshot("job_detail_page");
            pause(1000); // polite gap between requests

            WebElement body = driver.findElement(By.tagName("body"));

            // 1) LinkedIn's own salary box, if the employer filled it in
            String box = text(body, ".compensation__salary");
            if (!box.isEmpty()) return box;

            // 2) Otherwise search the job description for a pay figure
            Matcher m = SALARY.matcher(text(body, ".show-more-less-html__markup"));
            return m.find() ? m.group().trim() : "";

        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // SAVE TO CSV
    // ------------------------------------------------------------------

    static void writeCsv() throws Exception {
        try (FileWriter w = new FileWriter(OUT_FILE)) {
            w.write("Job Title,Company,Location,Vacancies,Salary,Posted,URL,Logo URL\n");
            for (Job j : jobs.values()) {
                w.write(String.join(",",
                        csv(j.title), csv(j.company), csv(j.location),
                        String.valueOf(j.vacancies), csv(j.salary), csv(j.posted),
                        csv(j.url), csv(j.logo)) + "\n");
            }
        }
    }

    // ------------------------------------------------------------------
    // HELPERS
    // ------------------------------------------------------------------

    /** Waits until one of the matching elements is visible and returns it. */
    static WebElement firstVisible(String css) {
        return wait.until(d -> {
            for (WebElement e : d.findElements(By.cssSelector(css))) {
                if (e.isDisplayed() && e.isEnabled()) return e;
            }
            return null;
        });
    }

    /** Normal click; if something covers the element, close the pop-up and click with JavaScript. */
    static void safeClick(WebElement element) {
        try {
            element.click();
        } catch (ElementClickInterceptedException e) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        }
    }

    /** Saves a numbered screenshot of the current page, e.g. screenshots/fig01_home_page.png */
    static void screenshot(String name) {
        try {
            File image = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Path target = Paths.get(SHOTS_DIR, String.format("fig%02d_%s.png", shotNumber++, name));
            Files.copy(image.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Screenshot saved: " + target);
        } catch (Exception e) {
            System.out.println("Screenshot failed: " + e.getMessage());
        }
    }

    /** Text inside the first element matching the selector, or "" if there is none. */
    static String text(WebElement parent, String css) {
        List<WebElement> els = parent.findElements(By.cssSelector(css));
        if (els.isEmpty()) return "";
        String t = els.get(0).getAttribute("textContent");
        return t == null ? "" : t.replaceAll("\\s+", " ").trim();
    }

    /** An attribute (like href) of the first matching element, or "" if there is none. */
    static String attr(WebElement parent, String css, String name) {
        List<WebElement> els = parent.findElements(By.cssSelector(css));
        String v = els.isEmpty() ? null : els.get(0).getAttribute(name);
        return v == null ? "" : v;
    }

    /** Makes text safe for a URL (spaces, quotes, etc.). */
    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Wraps a value in quotes for CSV and escapes quotes inside it. */
    static String csv(String v) {
        return "\"" + (v == null ? "" : v.replace("\"", "\"\"")) + "\"";
    }

    /** Short pause between requests so we don't overload the website. */
    static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}