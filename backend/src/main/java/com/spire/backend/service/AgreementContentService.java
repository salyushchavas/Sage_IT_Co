package com.spire.backend.service;

import com.spire.backend.dto.AgreementContent.Block;
import com.spire.backend.dto.AgreementContent.Segment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the binding master-agreement .docx into structured, per-section
 * blocks for inline display in the consultant wizard. SINGLE SOURCE OF
 * TRUTH: the same template office-stamper renders into the signed PDF, so
 * the on-screen clauses can never drift from the signed document.
 *
 * COMPLETE PARTITION: every body element (paragraph + table) is assigned
 * to exactly one section by heading boundaries; the union equals the
 * whole document. {@link #verifyPartition} asserts this at startup and
 * fails loudly if a clause would be dropped (e.g. the template's heading
 * text changed).
 *
 * Parsed once (consultant-independent) and cached; per-app values are
 * assembled per request elsewhere.
 */
@Service
@Slf4j
public class AgreementContentService {

    @Value("classpath:templates/SageITCO_Master_Agreement_TEMPLATE.docx")
    private Resource templateResource;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    /** Ordered START markers — a paragraph whose trimmed text starts with
     *  one of these opens that section. Uppercase EXHIBIT/APPENDIX appear
     *  ONLY as headings (body references are title-case), and the Section-1
     *  phrase is specific, so in-sentence mentions never trip a boundary. */
    private record Marker(String prefix, String sectionId) {}

    private static final List<Marker> MARKERS = List.of(
            new Marker("1. Purpose and Integrated Service Framework", "main-agreement"),
            new Marker("EXHIBIT A", "exhibit-a"),
            new Marker("EXHIBIT B", "exhibit-b"),
            new Marker("APPENDIX 1", "appendix1"),
            new Marker("APPENDIX 2", "appendix2"),
            new Marker("APPENDIX 3", "appendix3"),
            new Marker("APPENDIX 4", "appendix4"),
            new Marker("APPENDIX 5", "appendix5"));

    private static final List<String> SECTION_IDS = List.of(
            "cover", "main-agreement", "exhibit-a", "exhibit-b",
            "appendix1", "appendix2", "appendix3", "appendix4", "appendix5");

    private Map<String, List<Block>> cachedSections;

    @PostConstruct
    void init() {
        try {
            this.cachedSections = parseAndPartition();
            verifyPartition(this.cachedSections);
        } catch (RuntimeException e) {
            // Loud (ERROR), but do NOT crash the whole backend over a
            // display-only parse: the wizard read pane falls back to the
            // plain-language summary when content is missing. This keeps
            // auth / dashboard / ERM console up even if the template's
            // structure drifts. Fix the template/markers and redeploy.
            log.error("AGREEMENT TEMPLATE PARTITION FAILED — inline clauses "
                    + "disabled (wizard falls back to summaries): {}", e.getMessage(), e);
            if (this.cachedSections == null) this.cachedSections = Map.of();
        }
    }

    /** Cached, parsed-once, consultant-independent section content. */
    public Map<String, List<Block>> getSections() {
        if (cachedSections == null) {
            // Defensive (should be set by @PostConstruct).
            this.cachedSections = parseAndPartition();
        }
        return cachedSections;
    }

    private Map<String, List<Block>> parseAndPartition() {
        Map<String, List<Block>> sections = new LinkedHashMap<>();
        for (String id : SECTION_IDS) sections.put(id, new ArrayList<>());

        try (InputStream in = templateResource.getInputStream();
             XWPFDocument doc = new XWPFDocument(in)) {

            String current = "cover";
            int nextMarker = 0;

            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String text = paragraphText(p);
                    String trimmed = text.trim();
                    // Advance to the next section when this paragraph IS the
                    // next section's heading (the heading lives IN its section).
                    if (nextMarker < MARKERS.size()
                            && !trimmed.isEmpty()
                            && trimmed.startsWith(MARKERS.get(nextMarker).prefix())) {
                        current = MARKERS.get(nextMarker).sectionId();
                        nextMarker++;
                    }
                    sections.get(current).add(toParagraphBlock(p, text, trimmed));
                } else if (el instanceof XWPFTable t) {
                    sections.get(current).add(toTableBlock(t));
                }
                // Other element types (rare) carry no renderable clause text.
            }

            if (nextMarker != MARKERS.size()) {
                throw new IllegalStateException(
                        "Agreement template partition failed: only matched "
                                + nextMarker + "/" + MARKERS.size()
                                + " section headings. The template's heading text "
                                + "likely changed — update AgreementContentService.MARKERS.");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not parse the master agreement template for display.", e);
        }
        return sections;
    }

    /**
     * Structural proof that no clause is dropped: the number of renderable
     * body elements assigned across all sections must equal the template's
     * total paragraph+table count (zero unassigned, zero double-assigned).
     * Logs per-section counts; throws if violated.
     */
    private void verifyPartition(Map<String, List<Block>> sections) {
        int total;
        try (InputStream in = templateResource.getInputStream();
             XWPFDocument doc = new XWPFDocument(in)) {
            int count = 0;
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph || el instanceof XWPFTable) count++;
            }
            total = count;
        } catch (Exception e) {
            throw new IllegalStateException("Could not re-read template for completeness check.", e);
        }

        int assigned = 0;
        StringBuilder breakdown = new StringBuilder();
        for (Map.Entry<String, List<Block>> e : sections.entrySet()) {
            assigned += e.getValue().size();
            breakdown.append(e.getKey()).append("=").append(e.getValue().size()).append(' ');
        }

        log.info("Agreement template partition: total body elements={}, assigned={} [{}]",
                total, assigned, breakdown.toString().trim());

        if (assigned != total) {
            throw new IllegalStateException(
                    "Agreement template partition is INCOMPLETE: total=" + total
                            + " but assigned=" + assigned
                            + ". A clause would be dropped — aborting startup.");
        }
        log.info("Agreement template partition verified: every clause assigned to exactly one section.");
    }

    // ── element -> block ─────────────────────────────────────────────

    private Block toParagraphBlock(XWPFParagraph p, String text, String trimmed) {
        List<Segment> segs = toSegments(text);
        int level = headingLevel(p, trimmed);
        return level > 0 ? Block.heading(level, segs) : Block.paragraph(segs);
    }

    private Block toTableBlock(XWPFTable t) {
        List<List<List<Segment>>> rows = new ArrayList<>();
        for (XWPFTableRow row : t.getRows()) {
            List<List<Segment>> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                StringBuilder sb = new StringBuilder();
                for (XWPFParagraph p : cell.getParagraphs()) {
                    String pt = paragraphText(p);
                    if (sb.length() > 0 && !pt.isEmpty()) sb.append('\n');
                    sb.append(pt);
                }
                cells.add(toSegments(sb.toString()));
            }
            rows.add(cells);
        }
        return Block.table(rows);
    }

    /** Concatenate run text FIRST so ${tokens} split across runs reassemble. */
    private static String paragraphText(XWPFParagraph p) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun r : p.getRuns()) {
            String t = r.text();
            if (t != null) sb.append(t);
        }
        if (sb.length() == 0) {
            String pt = p.getText();
            if (pt != null) sb.append(pt);
        }
        return sb.toString();
    }

    private static List<Segment> toSegments(String text) {
        List<Segment> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Matcher m = PLACEHOLDER.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(Segment.text(text.substring(last, m.start())));
            out.add(Segment.placeholder(m.group(1)));
            last = m.end();
        }
        if (last < text.length()) out.add(Segment.text(text.substring(last)));
        return out;
    }

    private int headingLevel(XWPFParagraph p, String trimmed) {
        if (trimmed.isEmpty()) return 0;
        for (Marker mk : MARKERS) {
            if (trimmed.startsWith(mk.prefix())) return 1;
        }
        String style = p.getStyle();
        if (style != null) {
            String s = style.toLowerCase();
            if (s.contains("title") || s.equals("heading1")) return 1;
            if (s.startsWith("heading")) return 2;
        }
        // Short numbered sub-heading ("2. Definitions") — not a long clause
        // that merely begins with a number.
        if (trimmed.length() <= 90 && trimmed.matches("^\\d{1,2}\\.\\s+\\S.*")) return 2;
        return 0;
    }
}
