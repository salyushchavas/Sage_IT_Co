package com.spire.backend.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the cheque-upload 429 fix: file uploads have their own per-appId
 * bucket, so a busy form-autosave / per-cheque-metadata write stream can no
 * longer exhaust the shared bucket and 429 the user's upload.
 */
class ConsultantRateLimiterTest {

    @Test
    void writeBucketAllows30ThenBlocks() {
        ConsultantRateLimiter rl = new ConsultantRateLimiter();
        for (int i = 0; i < 30; i++) {
            assertTrue(rl.allowWrite("app-1"), "write " + (i + 1) + " should pass");
        }
        assertFalse(rl.allowWrite("app-1"), "31st write should be blocked");
    }

    @Test
    void uploadBucketAllows30ThenBlocks() {
        ConsultantRateLimiter rl = new ConsultantRateLimiter();
        for (int i = 0; i < 30; i++) {
            assertTrue(rl.allowUpload("app-1"), "upload " + (i + 1) + " should pass");
        }
        assertFalse(rl.allowUpload("app-1"), "31st upload should be blocked");
    }

    @Test
    void uploadsAreNotStarvedByWrites() {
        // The reported bug: autosave + per-cheque metadata writes exhausted the
        // single shared bucket and 429'd the user's cheque upload. With a
        // separate upload bucket, exhausting WRITE must leave UPLOAD untouched.
        ConsultantRateLimiter rl = new ConsultantRateLimiter();
        for (int i = 0; i < 30; i++) rl.allowWrite("app-1");
        assertFalse(rl.allowWrite("app-1"), "write bucket is exhausted");

        for (int i = 0; i < 30; i++) {
            assertTrue(rl.allowUpload("app-1"),
                    "upload " + (i + 1) + " must still pass while writes are exhausted");
        }
    }

    @Test
    void bucketsAreKeyedPerAppId() {
        ConsultantRateLimiter rl = new ConsultantRateLimiter();
        for (int i = 0; i < 30; i++) rl.allowUpload("app-A");
        assertFalse(rl.allowUpload("app-A"), "app-A upload bucket exhausted");
        assertTrue(rl.allowUpload("app-B"), "app-B has its own independent bucket");
    }
}
