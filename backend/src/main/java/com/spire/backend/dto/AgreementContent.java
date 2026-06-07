package com.spire.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Structured, per-section rendering of the master agreement template,
 * parsed straight from the binding .docx (single source of truth) so the
 * consultant reads the EXACT clauses that the signed PDF contains.
 *
 * The whole document is partitioned across wizard sections by heading
 * boundaries — the union of all sections equals the entire document, so
 * no clause can be dropped (asserted at startup).
 *
 * A {@link Segment} is either literal clause text or a {@code ${...}}
 * placeholder marker; the frontend fills consultant-editable markers live
 * from form state and the rest from {@link #values}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgreementContent(
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
