package com.spire.backend.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the consultant review-step preview gate. The autosave flips a
 * freshly-opened SUBMITTED row to UPDATED on the first edit, so the gate MUST
 * admit UPDATED (and the rest of the editable set) — otherwise the review-step
 * preview 409s for every consultant who has touched the form (the reported
 * "portal didn't respond" error). Terminal / approval states must stay closed.
 */
class ConsultantPreviewGateTest {

    @Test
    void editableStatesArePreviewable() {
        // The regression: UPDATED must be previewable (autosave sets it).
        assertTrue(ConsultantApplicationController.isConsultantPreviewable("UPDATED"));
        assertTrue(ConsultantApplicationController.isConsultantPreviewable("SUBMITTED"));
        assertTrue(ConsultantApplicationController.isConsultantPreviewable("REVISION_REQUESTED"));
        assertTrue(ConsultantApplicationController.isConsultantPreviewable("DRAFT"));
    }

    @Test
    void nonEditableStatesAreNotPreviewable() {
        for (String s : new String[]{
                "VERIFIED", "AWAITING_APPROVALS", "APPROVAL_REVISION_REQUESTED",
                "READY_TO_SIGN", "SIGNED", "COMPLETED", "CANCELLED", "EXPIRED"}) {
            assertFalse(ConsultantApplicationController.isConsultantPreviewable(s),
                    s + " must NOT be previewable");
        }
    }

    @Test
    void unknownOrNullStatusIsNotPreviewable() {
        assertFalse(ConsultantApplicationController.isConsultantPreviewable(null));
        assertFalse(ConsultantApplicationController.isConsultantPreviewable(""));
        assertFalse(ConsultantApplicationController.isConsultantPreviewable("BOGUS"));
    }
}
