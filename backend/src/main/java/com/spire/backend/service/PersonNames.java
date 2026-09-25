package com.spire.backend.service;

import java.util.regex.Pattern;

/**
 * The one rule for a person's name, wherever it is typed (sign-up, profile,
 * staff accounts). Names go into emails the portal sends, so they may only
 * hold letters, digits, spaces and the marks names use (. ' - ,): no
 * markup, links or addresses that would turn an email into a phishing
 * message from the portal's own address.
 */
public final class PersonNames {

    private static final Pattern ALLOWED =
            Pattern.compile("^[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N} .,'’-]*$");

    private PersonNames() {}

    /** The tidied name, or 400 with a plain reason. */
    public static String clean(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.length() < 2) throw new IllegalArgumentException("Enter the full name.");
        if (name.length() > 100) throw new IllegalArgumentException("A name can be at most 100 characters.");
        if (!ALLOWED.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "A name can use letters, spaces, apostrophes, hyphens and periods only.");
        }
        return name;
    }

    /** For email greetings: the first word, with anything but name characters removed. */
    public static String firstWordForEmail(String fullName) {
        if (fullName == null) return "there";
        String name = fullName.trim();
        int sp = name.indexOf(' ');
        String first = (sp > 0 ? name.substring(0, sp) : name)
                .replaceAll("[^\\p{L}\\p{M}\\p{N}.'’-]", "");
        if (first.length() > 40) first = first.substring(0, 40);
        return first.isEmpty() ? "there" : first;
    }
}
