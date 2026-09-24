package com.spire.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * The exact "Acknowledgment of Interest and Program Acceptance" text
 * participants accept (roadmap step 4, §4.1: "every participant-facing
 * acknowledgment must show the exact text being accepted and record the
 * version accepted"). The page renders this text from the server, and
 * each acceptance stores the version plus a SHA-256 fingerprint of it.
 *
 * <p>Never edit a published version in place: add a new version string,
 * so every stored fingerprint keeps matching the text it names.
 */
public final class AcknowledgmentText {

    public static final String VERSION = "ACK-v1.0";
    public static final String TITLE = "Acknowledgment of Interest and Program Acceptance";
    public static final String INTRO = "By accepting this acknowledgment, I confirm:";
    public static final List<String> CLAUSES = List.of(
            "I am expressing genuine interest in the career development and professional services offered by Sage IT Co.",
            "I understand that Sage IT Co provides career coaching, resume administration, interview preparation, technical development, and job-navigation support.",
            "I consent to providing required identification and documentation for program enrollment and verification.",
            "I consent to receiving program-related communications via email, phone, and the platform dashboard.",
            "I understand that my information will be handled securely and in accordance with the company's privacy and data policies.",
            "I acknowledge that completion of program phases and services is subject to my active participation and compliance with program requirements.");
    /** The interest box reads "I have read and accept the {TITLE} ({VERSION})." */
    public static final String CONSENT_INTEREST = "I have read and accept the " + TITLE + " (" + VERSION + ").";
    public static final String CONSENT_DOCUMENTATION =
            "I consent to providing required identification and documentation through the secure portal.";
    public static final String CONSENT_COMMUNICATION =
            "I consent to receiving program-related communications from Sage IT Co.";

    private static final String FINGERPRINT = sha256(canonical());

    private AcknowledgmentText() {}

    /** The text exactly as accepted, one line per element, in display order. */
    static String canonical() {
        StringBuilder sb = new StringBuilder(VERSION).append('\n').append(TITLE).append('\n').append(INTRO).append('\n');
        for (int i = 0; i < CLAUSES.size(); i++) sb.append(i + 1).append(". ").append(CLAUSES.get(i)).append('\n');
        return sb.append(CONSENT_INTEREST).append('\n')
                .append(CONSENT_DOCUMENTATION).append('\n')
                .append(CONSENT_COMMUNICATION).toString();
    }

    /** SHA-256 (hex) of {@link #canonical()}. */
    public static String fingerprint() {
        return FINGERPRINT;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
