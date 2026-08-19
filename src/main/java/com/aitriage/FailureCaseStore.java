package com.aitriage;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Flat-file (JSONL) store for failure cases, multi-tenant via projectId.
 * A linear scan is fine at this scale (see README) - no vector database needed.
 */
public class FailureCaseStore {

    private final File file;
    private final File screenshotDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<FailureCase> cases = new ArrayList<>();

    public FailureCaseStore(String dataDir) {
        this.file = new File(dataDir, "failure_cases.jsonl");
        this.screenshotDir = new File(dataDir, "screenshots");
        screenshotDir.mkdirs();
        load();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file.toPath())) {
                if (line.isBlank()) {
                    continue;
                }
                cases.add(mapper.readValue(line, FailureCase.class));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load failure case store: " + file, e);
        }
    }

    public synchronized void append(FailureCase failureCase, byte[] screenshotPng) {
        cases.add(failureCase);
        try {
            String line = mapper.writeValueAsString(failureCase);
            Files.writeString(file.toPath(), line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (screenshotPng != null && screenshotPng.length > 0) {
                Files.write(new File(screenshotDir, failureCase.id + ".png").toPath(), screenshotPng);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to append to failure case store: " + file, e);
        }
    }

    public List<FailureCase> findSimilar(String projectId, float[] queryEmbedding, int topK) {
        return findSimilar(projectId, queryEmbedding, topK, -1.0);
    }

    /**
     * Only past incidents at or above minSimilarity are returned, so weakly-related history doesn't
     * dilute the prompt. Human-verified cases are preferred over unverified AI guesses at the same
     * relevance, so a corrected classification starts grounding future diagnoses immediately instead
     * of an unverified (possibly wrong) one being retrieved just as readily.
     * <p>
     * If nothing in the current project clears minSimilarity, falls back to a search across all
     * other projects - a brand-new project should still benefit from a pattern (e.g. a cookie-banner
     * overlay) another project has already seen and had confirmed by a human, instead of starting
     * every classification cold. An established project with real in-project history never triggers
     * this fallback. The fallback only considers human-verified cases from other projects - an
     * unverified AI guess borrowed cross-project would compound uncertainty on two axes at once.
     */
    public List<FailureCase> findSimilar(String projectId, float[] queryEmbedding, int topK, double minSimilarity) {
        List<FailureCase> result = findSimilarWithin(c -> c.projectId.equals(projectId), queryEmbedding, topK, minSimilarity);
        if (!result.isEmpty()) {
            return result;
        }
        return findSimilarWithin(c -> !c.projectId.equals(projectId) && c.verified, queryEmbedding, topK, minSimilarity);
    }

    private List<FailureCase> findSimilarWithin(java.util.function.Predicate<FailureCase> scope,
                                                 float[] queryEmbedding, int topK, double minSimilarity) {
        List<FailureCase> verified = new ArrayList<>();
        List<FailureCase> unverified = new ArrayList<>();
        for (FailureCase c : cases) {
            if (!scope.test(c) || cosineSimilarity(c.embedding, queryEmbedding) < minSimilarity) {
                continue;
            }
            (c.verified ? verified : unverified).add(c);
        }
        Comparator<FailureCase> bySimilarityDesc =
                Comparator.comparingDouble((FailureCase c) -> cosineSimilarity(c.embedding, queryEmbedding)).reversed();
        verified.sort(bySimilarityDesc);
        unverified.sort(bySimilarityDesc);

        List<FailureCase> result = new ArrayList<>(verified.subList(0, Math.min(topK, verified.size())));
        if (result.size() < topK) {
            int remaining = topK - result.size();
            result.addAll(unverified.subList(0, Math.min(remaining, unverified.size())));
        }
        return result;
    }

    /** Records a human confirmation/correction of a case's classification, used to ground future diagnoses. */
    public synchronized boolean verify(String id, String verifiedClassification) {
        Optional<FailureCase> found = cases.stream().filter(c -> c.id.equals(id)).findFirst();
        if (found.isEmpty()) {
            return false;
        }
        FailureCase c = found.get();
        c.verified = true;
        c.verifiedClassification = verifiedClassification;
        rewriteFile();
        return true;
    }

    private void rewriteFile() {
        try {
            StringBuilder sb = new StringBuilder();
            for (FailureCase c : cases) {
                sb.append(mapper.writeValueAsString(c)).append(System.lineSeparator());
            }
            Files.writeString(file.toPath(), sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to rewrite failure case store: " + file, e);
        }
    }

    public List<FailureCase> listAll(String projectIdFilter) {
        List<FailureCase> result = new ArrayList<>();
        for (FailureCase c : cases) {
            if (projectIdFilter == null || projectIdFilter.isBlank() || c.projectId.equals(projectIdFilter)) {
                result.add(c);
            }
        }
        result.sort(Comparator.comparing((FailureCase c) -> c.timestamp).reversed());
        return result;
    }

    public Optional<FailureCase> findById(String id) {
        return cases.stream().filter(c -> c.id.equals(id)).findFirst();
    }

    public File screenshotFile(String id) {
        return new File(screenshotDir, id + ".png");
    }

    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return -1;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
