package com.aitriage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of the failing locator (strategy + selector value) out of a raw exception
 * message, so locator-health tracking and flaky-test detection can group failures by "what actually
 * broke" instead of by the free-text message, which varies run to run (session ids, timestamps).
 * Heuristic and framework-agnostic on purpose - unrecognized formats just return null rather than
 * guessing wrong.
 */
public final class LocatorExtractor {

    // Selenium's standard NoSuchElementException shape: {"method":"css selector","selector":"#foo"}
    private static final Pattern JSON_LOCATOR = Pattern.compile(
            "\"method\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"selector\"\\s*:\\s*\"([^\"]+)\"");

    // By.xpath("..."), By.cssSelector("..."), By.id("..."), etc.
    private static final Pattern BY_CALL = Pattern.compile(
            "By\\.(xpath|cssSelector|id|className|linkText|partialLinkText|name|tagName)\\(\"(.*?)\"\\)");

    // A bare CSS id/class token mentioned in the message, e.g. "element not found: #submit-btn"
    private static final Pattern BARE_CSS_TOKEN = Pattern.compile("[#.][A-Za-z][\\w-]{1,60}");

    private LocatorExtractor() {
    }

    /** Returns a normalized "strategy: selector" string, or null if none could be confidently extracted. */
    public static String extract(String exceptionMessage) {
        if (exceptionMessage == null || exceptionMessage.isBlank()) {
            return null;
        }
        Matcher jsonMatcher = JSON_LOCATOR.matcher(exceptionMessage);
        if (jsonMatcher.find()) {
            return jsonMatcher.group(1).trim() + ": " + jsonMatcher.group(2).trim();
        }
        Matcher byMatcher = BY_CALL.matcher(exceptionMessage);
        if (byMatcher.find()) {
            return byMatcher.group(1).trim() + ": " + byMatcher.group(2).trim();
        }
        Matcher bareMatcher = BARE_CSS_TOKEN.matcher(exceptionMessage);
        if (bareMatcher.find()) {
            return "css selector: " + bareMatcher.group().trim();
        }
        return null;
    }
}
