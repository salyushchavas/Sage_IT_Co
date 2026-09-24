package com.spire.backend.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthControllerTest {

    @Test
    void reportsTheShortCommitOrUnknown() {
        assertEquals("5ef9df8", HealthController.shortCommit("5ef9df8a1b2c3d4e5f60718293a4b5c6d7e8f901"));
        assertEquals("abc", HealthController.shortCommit(" abc "));
        assertEquals("unknown", HealthController.shortCommit(null));
        assertEquals("unknown", HealthController.shortCommit("  "));
    }
}
