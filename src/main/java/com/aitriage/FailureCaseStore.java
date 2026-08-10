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
        List<FailureCase> sorted = new ArrayList<>();
        for (FailureCase c : cases) {
            if (c.projectId.equals(projectId)) {
                sorted.add(c);
            }
        }
        sorted.sort(Comparator.comparingDouble((FailureCase c) -> cosineSimilarity(c.embedding, queryEmbedding)).reversed());
        return sorted.subList(0, Math.min(topK, sorted.size()));
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
