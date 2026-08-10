package com.aitriage;

import java.time.Instant;

/** A single triaged test failure, stored in the flat-file case store. */
public class FailureCase {

    public String id;
    public String timestamp;
    public String projectId;
    public String testName;
    public String exceptionType;
    public String exceptionMessage;
    public String pageSourceSnippet;
    public boolean hasScreenshot;
    public float[] embedding;
    public String classification;
    public String reasoning;
    public String suggestion;

    public FailureCase() {
    }

    public FailureCase(String id, String projectId, String testName, String exceptionType,
                        String exceptionMessage, String pageSourceSnippet, boolean hasScreenshot,
                        float[] embedding, String classification, String reasoning, String suggestion) {
        this.id = id;
        this.timestamp = Instant.now().toString();
        this.projectId = projectId;
        this.testName = testName;
        this.exceptionType = exceptionType;
        this.exceptionMessage = exceptionMessage;
        this.pageSourceSnippet = pageSourceSnippet;
        this.hasScreenshot = hasScreenshot;
        this.embedding = embedding;
        this.classification = classification;
        this.reasoning = reasoning;
        this.suggestion = suggestion;
    }
}
