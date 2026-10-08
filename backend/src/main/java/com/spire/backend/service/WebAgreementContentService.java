package com.spire.backend.service;

import com.spire.backend.dto.WebAgreementContent.Block;
import com.spire.backend.dto.WebAgreementContent.Segment;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agreement's clauses per wizard section for the website's wizard: a
 * copy of the console's clause parser (AgreementContentService, never
 * called from here) reading the same template the PDF is drawn from, so the
 * on-screen clauses are the signed document's. The blocks are the
 * website's own {@link com.spire.backend.dto.WebAgreementContent} shape.
 *
 * Every paragraph and table goes to exactly one section, cut at the section
 * headings; the parse checks that nothing is dropped. Parsed once at start
 * and kept. A template whose headings changed logs an error and leaves the
 * sections empty (the wizard then shows its summaries), never stopping the
 * backend.
 */
@Service
@Slf4j
public class WebAgreementContentService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    /**
     * The headings that open each section, in order. The uppercase
     * EXHIBIT / APPENDIX forms appear only as headings, so a mention in a
     * sentence never starts a section.
     */
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
            // Display only: log loudly and carry on with no clauses.
            log.error("WEB AGREEMENT TEMPLATE PARTITION FAILED — inline clauses "
                    + "disabled (wizard falls back to summaries): {}", e.getMessage(), e);
            if (this.cachedSections == null) this.cachedSections = Map.of();
        }
    }

    /** The clauses per section, parsed once. */
    public Map<String, List<Block>> getSections() {
        if (cachedSections == null) {
            // Only when built outside Spring (init not run).
            this.cachedSections = parseAndPartition();
        }
        return cachedSections;
    }

    private Map<String, List<Block>> parseAndPartition() {
        Map<String, List<Block>> sections = new LinkedHashMap<>();
        for (String id : SECTION_IDS) sections.put(id, new ArrayList<>());

        try (InputStream in = template();
             XWPFDocument doc = new XWPFDocument(in)) {

            String current = "cover";
            int nextMarker = 0;

            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String text = paragraphText(p);
                    String trimmed = text.trim();
                    // A heading opens its section (and belongs to it).
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
                // Other element types carry no clause text.
            }

            if (nextMarker != MARKERS.size()) {
                throw new IllegalStateException(
                        "Agreement template partition failed: only matched "
                                + nextMarker + "/" + MARKERS.size()
                                + " section headings. The template's heading text "
                                + "likely changed — update WebAgreementContentService.MARKERS.");
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
     * Nothing dropped: the blocks across all sections must number the
     * template's paragraphs + tables. Throws when they don't.
     */
    private void verifyPartition(Map<String, List<Block>> sections) {
        int total;
        try (InputStream in = template();
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

        log.info("Web agreement template partition: total body elements={}, assigned={} [{}]",
                total, assigned, breakdown.toString().trim());

        if (assigned != total) {
            throw new IllegalStateException(
                    "Agreement template partition is INCOMPLETE: total=" + total
                            + " but assigned=" + assigned
                            + ". A clause would be dropped.");
        }
    }

    private static InputStream template() throws java.io.IOException {
        return new ClassPathResource(WebAgreementDocumentEngine.TEMPLATE,
                WebAgreementContentService.class.getClassLoader()).getInputStream();
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

    /** The runs' text first, so a ${token} split across runs comes back whole. */
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
        // A short numbered sub-heading ("2. Definitions"), not a long clause.
        if (trimmed.length() <= 90 && trimmed.matches("^\\d{1,2}\\.\\s+\\S.*")) return 2;
        return 0;
    }
}
