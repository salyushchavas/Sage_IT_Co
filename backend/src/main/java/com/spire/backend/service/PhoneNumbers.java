package com.spire.backend.service;

/**
 * Checklist 1.5 (roadmap step 1, "completeness and duplicate check"):
 * phone numbers are compared in one form, "+" followed by the country
 * code and the number, so "(555) 123-4567", "555.123.4567" and
 * "+1 555 123 4567" are the same number. Without a country code a
 * 10-digit number is taken as US (+1), Sage being a US company.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /**
     * The comparable form of a phone number, or null when none was given.
     * Throws IllegalArgumentException when it can't be a phone number.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (!trimmed.matches("[+\\d\\s().\\-]+")) {
            throw new IllegalArgumentException("Use only digits, spaces, +, -, or parentheses in the phone number.");
        }
        String digits = trimmed.replaceAll("\\D", "");
        String normalized;
        if (trimmed.startsWith("+")) {
            normalized = "+" + digits;
        } else if (digits.startsWith("00") && digits.length() > 9) {
            normalized = "+" + digits.substring(2);          // 00 international prefix
        } else if (digits.length() == 10) {
            normalized = "+1" + digits;                      // US number without the country code
        } else if (digits.length() == 11 && digits.startsWith("1")) {
            normalized = "+" + digits;                       // 1 + US number
        } else {
            normalized = "+" + digits;
        }
        int n = normalized.length() - 1;
        if (n < 8 || n > 15) {
            throw new IllegalArgumentException("Enter a valid phone number, including the area code.");
        }
        return normalized;
    }

    /**
     * The normalized number, after checking no other active account holds
     * it (a deactivated account's number can be reused). Null when blank.
     */
    public static String requireAvailable(com.spire.backend.repository.UserRepository users,
                                          String raw, Long ownerId) {
        String normalized = normalize(raw);
        if (normalized == null) return null;
        boolean taken = users.findByPhoneNormalized(normalized).stream()
                .anyMatch(u -> !u.getId().equals(ownerId) && Boolean.TRUE.equals(u.getIsActive()));
        if (taken) {
            throw new IllegalArgumentException(
                    "This phone number is already used by another account. "
                            + "Sign in to that account, or use a different number.");
        }
        return normalized;
    }

    /** Same as {@link #normalize}, but null instead of an error (for old stored values). */
    public static String normalizeOrNull(String raw) {
        try {
            return normalize(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
