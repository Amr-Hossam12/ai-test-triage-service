package com.aitriage.notify;

import com.aitriage.FailureCase;
import com.aitriage.TriageService;

/** Always-on baseline notifier - prints the diagnosis to stdout so it's visible without a dashboard. */
public class LoggingNotifier implements Notifier {

    @Override
    public void onDiagnosis(FailureCase failureCase, TriageService.TriageResult result) {
        System.out.println("[triage] " + failureCase.projectId + " / " + failureCase.testName
                + " -> " + result.classification + ": " + result.reasoning);
    }
}
