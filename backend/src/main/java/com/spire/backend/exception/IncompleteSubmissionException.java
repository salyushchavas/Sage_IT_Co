package com.spire.backend.exception;

import java.util.List;

/**
 * Raised by {@code ConsultantApplicationService.consultantSubmit} when
 * the application is missing required content at submit time -- any
 * blank consultant-fillable field, any unticked section affirmation,
 * or the missing signature.
 *
 * Carries structured detail so the wizard UI can route the consultant
 * back to the offending section without a second round-trip:
 *
 * <pre>
 *   {
 *     "success": false,
 *     "message": "Agreement is incomplete.",
 *     "data": {
 *       "missingFields":        ["primaryPhone", "achRoutingNumber", ...],
 *       "missingAffirmations":  ["affirmedExhibitA", "affirmedAppendix2"],
 *       "missingSignature":     true
 *     }
 *   }
 * </pre>
 *
 * Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class IncompleteSubmissionException extends RuntimeException {

    private final List<String> missingFields;
    private final List<String> missingAffirmations;
    private final boolean missingSignature;

    public IncompleteSubmissionException(
            List<String> missingFields,
            List<String> missingAffirmations,
            boolean missingSignature) {
        super("Agreement is incomplete.");
        this.missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        this.missingAffirmations =
                missingAffirmations == null ? List.of() : List.copyOf(missingAffirmations);
        this.missingSignature = missingSignature;
    }

    public List<String> getMissingFields() {
        return missingFields;
    }

    public List<String> getMissingAffirmations() {
        return missingAffirmations;
    }

    public boolean isMissingSignature() {
        return missingSignature;
    }
}
