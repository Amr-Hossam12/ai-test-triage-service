package com.aitriage;

/**
 * A pointer back to a past case that grounded a diagnosis, so a human reading the reasoning
 * can jump straight to the source instead of just trusting the LLM's prose description of it.
 */
public class SimilarCaseRef {
    public String id;
    public String projectId;
    public String testName;
    public String classification;
    public boolean verified;
    public boolean crossProject;

    public SimilarCaseRef() {
    }

    public SimilarCaseRef(String id, String projectId, String testName, String classification,
                           boolean verified, boolean crossProject) {
        this.id = id;
        this.projectId = projectId;
        this.testName = testName;
        this.classification = classification;
        this.verified = verified;
        this.crossProject = crossProject;
    }
}
