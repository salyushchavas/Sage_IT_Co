package com.spire.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The website agreement's on-screen clauses: a copy of the console's
 * AgreementContent (never used by the website), with the same JSON shape,
 * so the participant's wizard reads the same fields. Kept separate so a
 * change to the console's never reaches the website.
 *
 * The template's clauses per wizard section (every paragraph and table in
 * exactly one section) plus the agreement's non-editable values. A
 * {@link Segment} is literal clause text or a {@code ${...}} placeholder;
 * the wizard fills the participant's own placeholders from the form and
 * the rest from {@link #values}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebAgreementContent(
        Map<String, List<Block>> sections,
        Map<String, String> values
) {

    /** "text" = literal clause text; "ph" = a ${name} placeholder. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Segment(String kind, String text, String name) {
        public static Segment text(String t) {
            return new Segment("text", t, null);
        }
        public static Segment placeholder(String n) {
            return new Segment("ph", null, n);
        }
    }

    /**
     * A body element. {@code kind}:
     *   - "heading"   : segments + level (1 = section title, 2 = sub-heading)
     *   - "paragraph" : segments
     *   - "table"     : rows -> cells -> segments
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(
            String kind,
            Integer level,
            List<Segment> segments,
            List<List<List<Segment>>> rows
    ) {
        public static Block heading(int level, List<Segment> segments) {
            return new Block("heading", level, segments, null);
        }
        public static Block paragraph(List<Segment> segments) {
            return new Block("paragraph", null, segments, null);
        }
        public static Block table(List<List<List<Segment>>> rows) {
            return new Block("table", null, null, rows);
        }
    }
}
