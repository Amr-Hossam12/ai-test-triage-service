package com.aitriage;

import com.aitriage.notify.Notifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * RAG-lite failure triage: retrieves similar past incidents (per-project) from
 * the flat-file case store, grounds a local llama3.1:8b call with them, and
 * records the new case back into the store for future retrieval.
 */
public class TriageService {

    private static final int TOP_K = 3;
    private static final double MIN_SIMILARITY = 0.55;
    private static final int SNIPPET_MAX_LEN = 1500;
    private static final Set<String> ALLOWED_CLASSIFICATIONS = Set.of(
            "SITE_RENDERING_ISSUE", "UI_OVERLAY_BLOCKING", "ASSERTION_MISMATCH", "LOCATOR_BROKEN", "UNKNOWN");
    private static final Set<String> ALLOWED_CONFIDENCE = Set.of("HIGH", "MEDIUM", "LOW");

    private final OllamaClient ollama;
    private final FailureCaseStore store;
    private final Notifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();

    public TriageService(OllamaClient ollama, FailureCaseStore store, Notifier notifier) {
        this.ollama = ollama;
        this.store = store;
        this.notifier = notifier;
    }

    public TriageResult diagnose(String projectId, String testName, String exceptionType,
                                  String exceptionMessage, String pageSource, byte[] screenshotPng) {
        String snippet = truncate(pageSource, SNIPPET_MAX_LEN);
        float[] queryEmbedding = new float[0];
        List<SimilarCaseRef> referencedCases = new java.util.ArrayList<>();
        TriageResult result;
        try {
            String queryText = exceptionType + " | " + exceptionMessage + " | " + snippet;
            queryEmbedding = ollama.embed(queryText);
            List<FailureCase> similar = store.findSimilar(projectId, queryEmbedding, TOP_K, MIN_SIMILARITY);
            for (FailureCase c : similar) {
                boolean crossProject = !c.projectId.equals(projectId);
                referencedCases.add(new SimilarCaseRef(c.id, c.projectId, c.testName,
                        c.verified ? c.verifiedClassification : c.classification, c.verified, crossProject));
            }
            String rawResponse = ollama.generateJson(
                    buildPrompt(projectId, testName, exceptionType, exceptionMessage, snippet, similar));
            result = parseResponse(rawResponse);
            applySelfCheck(result, testName, exceptionType, exceptionMessage, snippet);
        } catch (Exception e) {
            // Still record the case on total failure (e.g. Ollama unreachable/timed out) so it isn't
            // silently lost - a past incident with this exact symptom already burned us twice.
            result = new TriageResult();
            result.classification = "UNKNOWN";
            result.reasoning = "AI triage call failed: " + e;
            result.suggestion = "Review manually - triage service could not reach the local LLM.";
            result.confidence = "LOW";
        }
        result.similarCases = referencedCases;

        String id = UUID.randomUUID().toString();
        FailureCase failureCase = new FailureCase(id, projectId, testName, exceptionType, exceptionMessage,
                snippet, screenshotPng != null && screenshotPng.length > 0, queryEmbedding,
                result.classification, result.reasoning, result.suggestion);
        failureCase.similarCases = referencedCases;
        failureCase.confidence = result.confidence;
        failureCase.selfCheckFlag = result.selfCheckFlag;
        failureCase.failingLocator = LocatorExtractor.extract(exceptionMessage);
        store.append(failureCase, screenshotPng);
        notifier.onDiagnosis(failureCase, result);
        result.id = id;
        return result;
    }

    private String buildPrompt(String projectId, String testName, String exceptionType, String exceptionMessage,
                                String snippet, List<FailureCase> similar) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a failure-triage assistant for automated test suites (any framework/language). ")
                .append("Given the failed test, its exception, and a snippet of the page/response captured at the ")
                .append("moment of failure, respond with ONLY a JSON object of exactly this shape, no other text:\n")
                .append("{\"classification\": one of [SITE_RENDERING_ISSUE, UI_OVERLAY_BLOCKING, ASSERTION_MISMATCH, ")
                .append("LOCATOR_BROKEN, UNKNOWN], \"reasoning\": \"2-4 sentence explanation\", ")
                .append("\"suggestion\": \"one concrete next step to fix or investigate\", ")
                .append("\"confidence\": one of [HIGH, MEDIUM, LOW] - how sure you are given the evidence actually ")
                .append("shown to you. Use LOW whenever the page snippet is empty/uninformative, the exception message ")
                .append("is vague, or you are choosing UNKNOWN. Use HIGH only when the evidence unambiguously matches ")
                .append("one category. Do not default to HIGH.}\n\n")
                .append("Category definitions - use the FIRST one that fits, in this priority order:\n")
                .append("1. LOCATOR_BROKEN - the exception itself is a locator/element-lookup failure ")
                .append("(e.g. NoSuchElementException, ElementNotInteractableException, StaleElementReferenceException, ")
                .append("selector/xpath timeouts) AND the page snippet shows the page loaded normally with no blocking ")
                .append("overlay. The element the test expects simply isn't there or the selector is stale.\n")
                .append("2. UI_OVERLAY_BLOCKING - a click/interaction failed (e.g. ElementClickInterceptedException) ")
                .append("or a locator timed out, AND the page snippet shows evidence of a modal, cookie banner, promo ")
                .append("popup, or other overlay covering the target element.\n")
                .append("3. SITE_RENDERING_ISSUE - the page snippet is empty, mostly blank, shows an error page, ")
                .append("a 4xx/5xx status, or otherwise indicates the site itself failed to render/load correctly, ")
                .append("independent of any specific element.\n")
                .append("4. ASSERTION_MISMATCH - the element(s) were found and the page rendered fine, but an explicit ")
                .append("assertion on a value/text/state failed (e.g. AssertionError, expected vs actual mismatch). ")
                .append("Do NOT use this category for locator/element-lookup exceptions even if they surface via an ")
                .append("assertion framework - classify by what actually broke (the locator), not by which exception ")
                .append("class wraps it.\n")
                .append("5. UNKNOWN - none of the above clearly apply, or there isn't enough evidence to decide.\n\n");

        if (!similar.isEmpty()) {
            boolean crossProject = !similar.get(0).projectId.equals(projectId);
            if (crossProject) {
                prompt.append("This project has no relevant history yet, so these are similar incidents from ")
                        .append("OTHER projects. All are human-confirmed, but the failing site/app is different - ")
                        .append("use them only as a general pattern match (e.g. \"this also looks like a cookie ")
                        .append("banner overlay\"), not as project-specific ground truth:\n");
            } else {
                prompt.append("Similar past incidents from this project's history. Entries marked (human-confirmed) ")
                        .append("were verified by a person and should be trusted as ground truth when the current failure ")
                        .append("matches closely. Entries marked (unconfirmed AI guess) are unverified and may be wrong - ")
                        .append("weigh them accordingly:\n");
            }
            for (FailureCase c : similar) {
                String label = c.verified ? c.verifiedClassification : c.classification;
                String confidence = c.verified ? "human-confirmed" : "unconfirmed AI guess";
                String source = crossProject ? " | Project: " + c.projectId : "";
                prompt.append("- [Case ").append(shortId(c.id)).append("] Exception: ").append(c.exceptionType)
                        .append(" | Classification: ").append(label).append(" (").append(confidence).append(")")
                        .append(source)
                        .append(" | Reasoning: ").append(c.reasoning).append("\n");
            }
            prompt.append("If one of these past incidents is what informed your classification, mention its ")
                    .append("[Case xxxxxxxx] tag in your reasoning so a reviewer can trace it back.\n\n");
        }

        prompt.append("Current failure:\n")
                .append("Test: ").append(testName).append("\n")
                .append("Exception: ").append(exceptionType).append(": ").append(exceptionMessage).append("\n")
                .append("Page/response snippet:\n").append(snippet).append("\n");

        return prompt.toString();
    }

    /**
     * Runs a second, independent classification pass over the same evidence (no mention of the
     * first pass's answer, so it can't just parrot it back) and compares it to what was already
     * decided. Disagreement doesn't override the stored classification - a human should be the one
     * to pick a winner - but it does downgrade confidence to LOW and flags the case so it surfaces
     * at the top of the Review Queue instead of blending in as a normal high/medium-confidence case.
     * Any failure in this second call (timeout, malformed JSON) is swallowed - the self-check is a
     * bonus signal, not a required part of the pipeline, so it must never fail the whole diagnosis.
     */
    private void applySelfCheck(TriageResult result, String testName, String exceptionType,
                                 String exceptionMessage, String snippet) {
        try {
            String rawCritic = ollama.generateJson(
                    buildCriticPrompt(testName, exceptionType, exceptionMessage, snippet));
            JsonNode node = mapper.readTree(rawCritic);
            String criticClassification = node.path("classification").asText("UNKNOWN").trim().toUpperCase();
            if (!ALLOWED_CLASSIFICATIONS.contains(criticClassification)) {
                return;
            }
            if (!criticClassification.equals(result.classification)) {
                result.confidence = "LOW";
                result.selfCheckFlag = "Self-check disagreement: a second independent pass proposed "
                        + criticClassification + " instead of " + result.classification + " - flagged for review.";
            }
        } catch (Exception e) {
            // Self-check is best-effort; the primary diagnosis already succeeded, so just skip it.
        }
    }

    private String buildCriticPrompt(String testName, String exceptionType, String exceptionMessage, String snippet) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are independently double-checking a test-failure classification, with no knowledge of ")
                .append("any prior answer. Given the failed test, its exception, and a snippet of the page/response ")
                .append("captured at the moment of failure, respond with ONLY a JSON object of exactly this shape, ")
                .append("no other text: {\"classification\": one of [SITE_RENDERING_ISSUE, UI_OVERLAY_BLOCKING, ")
                .append("ASSERTION_MISMATCH, LOCATOR_BROKEN, UNKNOWN]}\n\n")
                .append("Category definitions - use the FIRST one that fits, in this priority order:\n")
                .append("1. LOCATOR_BROKEN - the exception itself is a locator/element-lookup failure ")
                .append("(e.g. NoSuchElementException, ElementNotInteractableException, StaleElementReferenceException, ")
                .append("selector/xpath timeouts) AND the page snippet shows the page loaded normally with no blocking ")
                .append("overlay. The element the test expects simply isn't there or the selector is stale.\n")
                .append("2. UI_OVERLAY_BLOCKING - a click/interaction failed (e.g. ElementClickInterceptedException) ")
                .append("or a locator timed out, AND the page snippet shows evidence of a modal, cookie banner, promo ")
                .append("popup, or other overlay covering the target element.\n")
                .append("3. SITE_RENDERING_ISSUE - the page snippet is empty, mostly blank, shows an error page, ")
                .append("a 4xx/5xx status, or otherwise indicates the site itself failed to render/load correctly, ")
                .append("independent of any specific element.\n")
                .append("4. ASSERTION_MISMATCH - the element(s) were found and the page rendered fine, but an explicit ")
                .append("assertion on a value/text/state failed (e.g. AssertionError, expected vs actual mismatch). ")
                .append("Do NOT use this category for locator/element-lookup exceptions even if they surface via an ")
                .append("assertion framework - classify by what actually broke (the locator), not by which exception ")
                .append("class wraps it.\n")
                .append("5. UNKNOWN - none of the above clearly apply, or there isn't enough evidence to decide.\n\n")
                .append("Failure to classify:\n")
                .append("Test: ").append(testName).append("\n")
                .append("Exception: ").append(exceptionType).append(": ").append(exceptionMessage).append("\n")
                .append("Page/response snippet:\n").append(snippet).append("\n");
        return prompt.toString();
    }

    private TriageResult parseResponse(String rawResponse) {
        TriageResult result = new TriageResult();
        try {
            JsonNode node = mapper.readTree(rawResponse);
            String classification = node.path("classification").asText("UNKNOWN").trim().toUpperCase();
            result.classification = ALLOWED_CLASSIFICATIONS.contains(classification) ? classification : "UNKNOWN";
            result.reasoning = node.path("reasoning").asText("");
            result.suggestion = node.path("suggestion").asText("");
            String confidence = node.path("confidence").asText("MEDIUM").trim().toUpperCase();
            result.confidence = ALLOWED_CONFIDENCE.contains(confidence) ? confidence : "MEDIUM";
            if (!ALLOWED_CLASSIFICATIONS.contains(classification)) {
                // Model returned a category outside the allowed set - we silently coerced it to UNKNOWN,
                // so the confidence it reported no longer applies to what we actually stored.
                result.confidence = "LOW";
            }
        } catch (Exception e) {
            result.classification = "UNKNOWN";
            result.reasoning = "Model returned malformed output: " + rawResponse;
            result.suggestion = "Review manually - AI response could not be parsed.";
            result.confidence = "LOW";
        }
        return result;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    public static class TriageResult {
        public String id;
        public String classification;
        public String reasoning;
        public String suggestion;
        public String confidence;
        public String selfCheckFlag;
        public List<SimilarCaseRef> similarCases;
    }
}
