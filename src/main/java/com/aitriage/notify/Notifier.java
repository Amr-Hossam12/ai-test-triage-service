package com.aitriage.notify;

import com.aitriage.FailureCase;
import com.aitriage.TriageService;

/**
 * Fired after a failure has been triaged and persisted. Implementations must not throw -
 * a broken notifier must never take down the triage request itself.
 */
public interface Notifier {
    void onDiagnosis(FailureCase failureCase, TriageService.TriageResult result);
}
