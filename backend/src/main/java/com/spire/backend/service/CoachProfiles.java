package com.spire.backend.service;

import com.spire.backend.entity.User;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checklist 3.2: what each coach does. A coach has coach types (the slots
 * they fill for a participant) and skills (from the same technology list
 * participants choose from in their program selection).
 */
public final class CoachProfiles {

    private CoachProfiles() {}

    public static final List<String> TYPES =
            List.of("CAREER_COACH", "RESUME_SPECIALIST", "TECHNICAL_ADVISOR", "INTERVIEW_COACH");

    /** The program's technology options (same list as the website's program selection). */
    public static final List<String> SKILLS = List.of(
            "Java Full Stack", "Python Full Stack", ".NET Full Stack", "Data Engineering",
            "Cloud & DevOps", "React / Angular Frontend", "QA / Testing", "Data Science & AI",
            "Salesforce", "ServiceNow", "Cybersecurity");

    /** Words in a coach's bio that point to each skill (for filling in existing coaches). */
    private static final Map<String, List<String>> SKILL_WORDS = Map.ofEntries(
            Map.entry("Java Full Stack", List.of("java")),
            Map.entry("Python Full Stack", List.of("python")),
            Map.entry(".NET Full Stack", List.of(".net")),
            Map.entry("Data Engineering", List.of("data engineering")),
            Map.entry("Cloud & DevOps", List.of("cloud", "devops")),
            Map.entry("React / Angular Frontend", List.of("react", "angular", "frontend")),
            Map.entry("QA / Testing", List.of("qa ", "testing")),
            Map.entry("Data Science & AI", List.of("data science", "machine learning")),
            Map.entry("Salesforce", List.of("salesforce")),
            Map.entry("ServiceNow", List.of("servicenow")),
            Map.entry("Cybersecurity", List.of("cybersecurity", "security")));

    /** A coach's types; without any set, the defaults for their account role. */
    public static Set<String> typesOf(User u) {
        Set<String> set = parse(u.getCoachTypes());
        if (!set.isEmpty()) return set;
        String role = u.getRole() == null ? "" : u.getRole().getName();
        return "TECHNICAL_ADVISOR".equals(role) ? Set.of("TECHNICAL_ADVISOR")
                : new LinkedHashSet<>(List.of("CAREER_COACH", "RESUME_SPECIALIST", "INTERVIEW_COACH"));
    }

    /** A coach's skills, lower-cased for comparison. */
    public static Set<String> skillsOf(User u) {
        return parse(u.getCoachSkills()).stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    public static boolean hasSkill(User coach, String skill) {
        return skill != null && !skill.isBlank() && skillsOf(coach).contains(skill.trim().toLowerCase(Locale.ROOT));
    }

    /** Types and skills read from a coach's bio, for coaches who had none recorded. */
    public static String[] fromBio(String accountRole, String bio) {
        String b = bio == null ? "" : bio.toLowerCase(Locale.ROOT) + " ";
        Set<String> types = new LinkedHashSet<>();
        if ("TECHNICAL_ADVISOR".equals(accountRole) || b.contains("technical advisor")) types.add("TECHNICAL_ADVISOR");
        if (b.contains("career")) types.add("CAREER_COACH");
        if (b.contains("resume")) types.add("RESUME_SPECIALIST");
        if (b.contains("interview")) types.add("INTERVIEW_COACH");
        if (types.isEmpty()) {
            types.addAll("TECHNICAL_ADVISOR".equals(accountRole) ? List.of("TECHNICAL_ADVISOR")
                    : List.of("CAREER_COACH", "RESUME_SPECIALIST", "INTERVIEW_COACH"));
        }
        List<String> skills = new ArrayList<>();
        for (String skill : SKILLS) {
            if (SKILL_WORDS.getOrDefault(skill, List.of()).stream().anyMatch(b::contains)) skills.add(skill);
        }
        return new String[]{String.join(",", types), String.join(",", skills)};
    }

    /** Checks and normalizes a list of types for storage. */
    public static String types(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values == null ? List.<String>of() : values) {
            String t = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
            if (!TYPES.contains(t)) throw new IllegalArgumentException("Unknown coach type: " + v);
            out.add(t);
        }
        return String.join(",", out);
    }

    /** Checks and normalizes a list of skills for storage (the program's options only). */
    public static String skills(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values == null ? List.<String>of() : values) {
            String match = SKILLS.stream().filter(s -> s.equalsIgnoreCase(v == null ? "" : v.trim())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown skill: " + v));
            out.add(match);
        }
        return String.join(",", out);
    }

    private static Set<String> parse(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
