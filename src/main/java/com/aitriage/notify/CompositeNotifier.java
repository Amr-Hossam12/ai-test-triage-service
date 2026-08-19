package com.aitriage.notify;

import com.aitriage.FailureCase;
import com.aitriage.TriageService;

import java.util.List;

/** Fans a diagnosis out to every configured notifier; one failing must not skip the rest. */
public class CompositeNotifier implements Notifier {

    private final List<Notifier> delegates;

    public CompositeNotifier(List<Notifier> delegates) {
        this.delegates = delegates;
    }

    @Override
    public void onDiagnosis(FailureCase failureCase, TriageService.TriageResult result) {
        for (Notifier delegate : delegates) {
            try {
                delegate.onDiagnosis(failureCase, result);
            } catch (Exception e) {
                System.out.println("[notify] Notifier " + delegate.getClass().getSimpleName() + " failed: " + e);
            }
        }
    }
}
