package com.spire.backend.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Appendix 1 "Schedule 1 – Phase 2 Monthly Deliverables" table edit:
 * the Month/Period column is one vertically-merged cell driven by the
 * ${phase2DeliverablePeriod} placeholder — and, critically, that this survives
 * applyTemplateSurgery (the pre-stamp preprocessing that strips orphan empty
 * paragraphs / adjusts the template). If preprocessing dropped the merged
 * continuation cells or the placeholder, the rendered table would break.
 */
class AgreementTemplateMergeTest {

    private static String documentXmlAfterSurgery() throws Exception {
        byte[] template;
        try (InputStream in = AgreementTemplateMergeTest.class
                .getResourceAsStream("/templates/SageITCO_Master_Agreement_TEMPLATE.docx")) {
            assertNotNull(in, "template resource must be on the classpath");
            template = in.readAllBytes();
        }
        byte[] processed = AgreementDocumentService.applyTemplateSurgery(template);
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(processed))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    return new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("word/document.xml not found in processed template");
    }

    @Test
    void mergedDeliverablePlaceholderSurvivesPreprocessing() throws Exception {
        String xml = documentXmlAfterSurgery();

        int placeholder = countOf(xml, "${phase2DeliverablePeriod}");
        int vmRestart = countOf(xml, "<w:vMerge w:val=\"restart\"/>");
        int vmCont = countOf(xml, "<w:vMerge/>");

        assertTrue(placeholder == 1,
                "exactly one ${phase2DeliverablePeriod} placeholder expected, got " + placeholder);
        assertTrue(vmRestart >= 1, "vertical-merge restart cell missing");
        assertTrue(vmCont >= 3,
                "expected >=3 vMerge continuation cells (the 4-row span), got " + vmCont);
        // The merged period cell replaced the old hardcoded first value.
        assertTrue(!xml.contains("<w:t>Month 1</w:t>"),
                "old hardcoded 'Month 1' period cell should be gone");
        // Deliverable summary column is untouched (still has its boilerplate).
        assertTrue(xml.contains("transition coaching"),
                "deliverable summary text should be preserved");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
