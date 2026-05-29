package com.spire.backend.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import com.spire.backend.config.BrandConfig;
import com.spire.backend.entity.*;
import com.spire.backend.exception.ResourceNotFoundException;
import com.spire.backend.exception.UnauthorizedException;
import com.spire.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CertificateService {

    // Sage navy. Kept as a fallback if BrandConfig.primaryColor is
    // unparseable; brandPrimary() reads the live brand config so a
    // re-deploy under a different brand swaps the PDF colour without
    // code changes.
    private static final Color BRAND_FALLBACK = new Color(27, 42, 92);   // #1B2A5C
    private static final Color TEAL_DARK = new Color(19, 78, 74);        // #134E4A (deep accent stripe)
    private static final Color TEAL_LIGHT = new Color(94, 234, 212);     // #5EEAD4 (light accent stripe)
    private static final Color GOLD_ACCENT = new Color(180, 142, 35);    // signature line color
    private static final Color INK = new Color(31, 41, 55);              // dark body
    private static final Color MUTED = new Color(107, 114, 128);         // muted body

    private static final Set<String> COURSE_CODE_STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "for", "to", "in", "with", "on"
    );

    private Color brandPrimary() {
        String hex = brandConfig.getPrimaryColor();
        if (hex == null || hex.isBlank()) return BRAND_FALLBACK;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            int rgb = Integer.parseInt(h, 16);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (NumberFormatException e) {
            return BRAND_FALLBACK;
        }
    }

    private final CertificateRepository certificateRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;
    private final ProgressRepository progressRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final BrandConfig brandConfig;
    private final QuizRepository quizRepository;
    private final SubmissionRepository submissionRepository;
    private final AssignmentRepository assignmentRepository;
    private final RecordService recordService;
    private final EmailTemplateService emailTemplateService;

    /**
     * Strict generation — throws when prerequisites aren't met. Used
     * by the manual "Generate Certificate" button so the student gets
     * a clear error message about what's still missing.
     */
    @Transactional
    public Certificate generateCertificate(Long courseId, Long userId) {
        // 1. Check enrollment
        if (!enrollmentRepository.existsByUserIdAndCourseId(userId, courseId)) {
            throw new UnauthorizedException("You must be enrolled in this course");
        }

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("Course", "id", courseId));

        // Services don't grant certificates (PRODUCT.md). Reject early so the
        // duplicate-check below doesn't accidentally return a stale cert from
        // before this guard existed.
        if (course.isService()) {
            throw new IllegalArgumentException("Services do not include certificates");
        }

        // 2. Check duplicate — return existing rather than re-issue.
        if (certificateRepository.existsByUserIdAndCourseId(userId, courseId)) {
            return certificateRepository.findByUserIdAndCourseId(userId, courseId).get();
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // 3. Verify all lessons completed
        List<Lesson> lessons = lessonRepository.findByCourseIdOrderByOrderIndex(courseId);
        long completedLessons = progressRepository.countCompletedLessons(userId, courseId);
        if (!lessons.isEmpty() && completedLessons < lessons.size()) {
            throw new IllegalArgumentException(
                    String.format("Complete all lessons first (%d/%d done)", completedLessons, lessons.size()));
        }

        // 4. Verify all quizzes passed (≥60%) and collect scores for the
        //    final score calculation. We average across whichever score
        //    field the attempt populated (new scorePercent or legacy
        //    percentage) so historic attempts don't drag the avg to 0.
        java.util.List<Double> scores = new java.util.ArrayList<>();
        for (Lesson lesson : lessons) {
            if (quizRepository.existsByLessonId(lesson.getId())) {
                Quiz quiz = quizRepository.findByLessonId(lesson.getId()).get();
                var attemptOpt = quizAttemptRepository.findByQuizIdAndUserId(quiz.getId(), userId);
                if (attemptOpt.isEmpty()) {
                    throw new IllegalArgumentException("Complete the quiz for lesson: " + lesson.getTitle());
                }
                QuizAttempt attempt = attemptOpt.get();
                double pct = attemptScore(attempt);
                if (pct < 60) {
                    throw new IllegalArgumentException("Pass the quiz for lesson: " + lesson.getTitle() + " (minimum 60%)");
                }
                scores.add(pct);
            }
        }

        // 5. Verify final course assignment submitted (if exists)
        List<Assignment> courseAssignments = assignmentRepository.findByCourseIdOrderByCreatedAtDesc(courseId)
                .stream().filter(a -> a.getAssignmentType() == Assignment.AssignmentType.COURSE).toList();
        for (Assignment assignment : courseAssignments) {
            if (!submissionRepository.existsByAssignmentIdAndStudentId(assignment.getId(), userId)) {
                throw new IllegalArgumentException("Submit the course assignment: " + assignment.getTitle());
            }
        }

        Double finalScore = scores.isEmpty() ? null
                : scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        // 6. Build certificate row with the SIT-format number. Number
        //    generation is collision-checked against existing rows.
        String certNumber = generateCertificateNumber(course, user);
        Certificate certificate = Certificate.builder()
                .certificateId(certNumber)
                .user(user)
                .course(course)
                .certificateUrl("") // patched after PDF write
                .finalScore(finalScore)
                .build();
        certificate = certificateRepository.save(certificate);

        // 7. Generate PDF
        String dirPath = String.format("certificates/%d", courseId);
        String fileName = String.format("%d-%d.pdf", userId, System.currentTimeMillis());
        String filePath = dirPath + "/" + fileName;

        new File(dirPath).mkdirs();
        generatePdf(filePath, user.getFullName(), course.getTitle(),
                certificate.getCertificateId(), finalScore,
                course.getInstructor() != null ? course.getInstructor().getFullName() : brandConfig.getName());

        // 8. Patch URL on the saved row
        certificate.setCertificateUrl("/api/certificates/download/" + courseId + "/" + fileName);
        Certificate saved = certificateRepository.save(certificate);

        recordService.record(userId, "CERTIFICATE_GENERATED", RecordService.Category.CERTIFICATE,
                "Certificate earned: " + course.getTitle(),
                "Certificate " + saved.getCertificateId() + " issued for '" + course.getTitle() + "'",
                java.util.Map.of(
                        "certificateId", saved.getCertificateId(),
                        "courseId", course.getId(),
                        "courseTitle", course.getTitle(),
                        "verificationUrl", "/verify/" + saved.getCertificateId()
                ));

        // Best-effort email — never blocks issuance.
        try { emailTemplateService.sendCertificateEmail(user, course, saved); } catch (Exception ignored) {}

        log.info("Certificate {} generated for user {} course {}", saved.getCertificateId(), userId, courseId);
        return saved;
    }

    /**
     * Lenient sibling of {@link #generateCertificate} — never throws.
     * Called from the lesson-completion path so finishing the last
     * lesson auto-issues the cert when the student is fully eligible,
     * and silently no-ops otherwise (quiz still pending, etc.). The
     * student can later manually trigger the strict path to get the
     * specific reason it was held back.
     */
    @Transactional
    public void tryAutoGenerate(Long courseId, Long userId) {
        try {
            generateCertificate(courseId, userId);
        } catch (Exception e) {
            log.info("Cert auto-gen skipped for user {} course {}: {}",
                    userId, courseId, e.getMessage());
        }
    }

    public Certificate getCertificate(Long courseId, Long userId) {
        return certificateRepository.findByUserIdAndCourseId(userId, courseId)
                .orElse(null);
    }

    public List<Certificate> getUserCertificates(Long userId) {
        return certificateRepository.findByUserId(userId);
    }

    public java.util.Optional<Certificate> findByCertificateId(String certificateId) {
        return certificateRepository.findByCertificateId(certificateId);
    }

    // ───────────────────────────────────────────────────────────────
    // Internals — number generation, score reading, PDF rendering
    // ───────────────────────────────────────────────────────────────

    /**
     * Builds {@code <PREFIX>-<COURSE>-<INITIALS>-<DDMMYY>}, where
     * PREFIX comes from {@link BrandConfig#getIdPrefix()}, suffixing
     * {@code -2}, {@code -3}, … on collision against existing rows.
     * Falls back to a UUID tail after 50 collisions (defensive, not
     * expected to fire).
     */
    private String generateCertificateNumber(Course course, User user) {
        String courseCode = Arrays.stream(course.getTitle().split("[\\s\\-/_]+"))
                .filter(w -> !w.isBlank())
                .filter(w -> !COURSE_CODE_STOPWORDS.contains(w.toLowerCase()))
                .map(w -> String.valueOf(Character.toUpperCase(w.charAt(0))))
                .collect(Collectors.joining());
        if (courseCode.length() > 4) courseCode = courseCode.substring(0, 4);
        if (courseCode.isEmpty()) courseCode = "CRS";

        String initials = Arrays.stream((user.getFullName() == null ? "" : user.getFullName()).split("\\s+"))
                .filter(w -> !w.isBlank())
                .map(w -> String.valueOf(Character.toUpperCase(w.charAt(0))))
                .limit(3)
                .collect(Collectors.joining());
        if (initials.isEmpty()) initials = "STU";

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyy"));

        String base = String.format("%s-%s-%s-%s", brandConfig.getIdPrefix(), courseCode, initials, date);
        if (!certificateRepository.findByCertificateId(base).isPresent()) {
            return base;
        }
        for (int i = 2; i <= 50; i++) {
            String candidate = base + "-" + i;
            if (!certificateRepository.findByCertificateId(candidate).isPresent()) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /**
     * Reads the best-available score percentage off a {@link QuizAttempt}.
     * Newer attempts populate {@code scorePercent} (BigDecimal); legacy
     * rows use {@code percentage} (int). Returns 0 when neither is set.
     */
    private double attemptScore(QuizAttempt attempt) {
        BigDecimal sp = attempt.getScorePercent();
        if (sp != null) return sp.doubleValue();
        Integer pct = attempt.getPercentage();
        if (pct != null) return pct.doubleValue();
        return 0;
    }

    /**
     * Renders a landscape A4 certificate. Uses {@link PdfContentByte}
     * for borders and signature lines (rather than text blocks) so the
     * design is layout-independent — the borders sit at fixed page
     * coordinates regardless of paragraph flow above.
     */
    private void generatePdf(
            String filePath, String studentName, String courseTitle,
            String certNumber, Double finalScore, String instructorName
    ) {
        try {
            Document doc = new Document(PageSize.A4.rotate(), 0, 0, 0, 0);
            PdfWriter writer = PdfWriter.getInstance(doc, new FileOutputStream(filePath));
            doc.open();

            PdfContentByte cb = writer.getDirectContent();
            float pageW = doc.getPageSize().getWidth();
            float pageH = doc.getPageSize().getHeight();
            Color brandColor = brandPrimary();

            // ── Outer double border (brand colour) ───────────────────
            float pad = 24f;
            cb.setColorStroke(brandColor);
            cb.setLineWidth(2.5f);
            cb.rectangle(pad, pad, pageW - 2 * pad, pageH - 2 * pad);
            cb.stroke();
            cb.setLineWidth(0.7f);
            cb.rectangle(pad + 6, pad + 6, pageW - 2 * (pad + 6), pageH - 2 * (pad + 6));
            cb.stroke();

            // ── Inner light teal frame ──────────────────────────────
            cb.setColorStroke(TEAL_LIGHT);
            cb.setLineWidth(0.5f);
            cb.rectangle(pad + 18, pad + 18, pageW - 2 * (pad + 18), pageH - 2 * (pad + 18));
            cb.stroke();

            // ── Top + bottom decorative rules ───────────────────────
            cb.setColorStroke(brandColor);
            cb.setLineWidth(2f);
            cb.moveTo(pageW / 2 - 80, pageH - 70);
            cb.lineTo(pageW / 2 + 80, pageH - 70);
            cb.stroke();
            cb.setColorStroke(TEAL_LIGHT);
            cb.setLineWidth(1f);
            cb.moveTo(pageW / 2 - 100, pageH - 75);
            cb.lineTo(pageW / 2 + 100, pageH - 75);
            cb.stroke();

            cb.setColorStroke(brandColor);
            cb.setLineWidth(2f);
            cb.moveTo(pageW / 2 - 80, 70);
            cb.lineTo(pageW / 2 + 80, 70);
            cb.stroke();
            cb.setColorStroke(TEAL_LIGHT);
            cb.setLineWidth(1f);
            cb.moveTo(pageW / 2 - 100, 65);
            cb.lineTo(pageW / 2 + 100, 65);
            cb.stroke();

            // ── Signature lines, drawn at fixed coords ──────────────
            float sigY = 130;
            float sigW = 180;
            float leftSigX = pageW / 2 - 230;
            float rightSigX = pageW / 2 + 50;
            cb.setColorStroke(GOLD_ACCENT);
            cb.setLineWidth(0.8f);
            cb.moveTo(leftSigX, sigY);
            cb.lineTo(leftSigX + sigW, sigY);
            cb.stroke();
            cb.moveTo(rightSigX, sigY);
            cb.lineTo(rightSigX + sigW, sigY);
            cb.stroke();

            // ── Underline under the student name ────────────────────
            cb.setColorStroke(TEAL_DARK);
            cb.setLineWidth(1.2f);
            cb.moveTo(pageW / 2 - 160, pageH / 2 + 20);
            cb.lineTo(pageW / 2 + 160, pageH / 2 + 20);
            cb.stroke();

            // ── Text content (centered, top-down) ───────────────────
            doc.add(spacer(40));

            doc.add(centered(brandConfig.getName().toUpperCase(),
                    new Font(Font.HELVETICA, 13, Font.BOLD, TEAL_DARK)));
            doc.add(spacer(2));
            doc.add(centered(brandConfig.getTagline(),
                    new Font(Font.HELVETICA, 9, Font.ITALIC, MUTED)));

            doc.add(spacer(24));
            doc.add(centered("CERTIFICATE OF COMPLETION",
                    new Font(Font.TIMES_ROMAN, 32, Font.BOLD, brandColor)));

            doc.add(spacer(20));
            doc.add(centered("This certifies that",
                    new Font(Font.TIMES_ROMAN, 14, Font.ITALIC, MUTED)));

            doc.add(spacer(18));
            doc.add(centered(studentName,
                    new Font(Font.TIMES_ROMAN, 36, Font.BOLD, INK)));

            doc.add(spacer(18));
            doc.add(centered("has successfully completed the course",
                    new Font(Font.TIMES_ROMAN, 14, Font.ITALIC, MUTED)));

            doc.add(spacer(12));
            doc.add(centered(courseTitle,
                    new Font(Font.TIMES_ROMAN, 22, Font.BOLD, TEAL_DARK)));

            doc.add(spacer(16));
            String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
            String achievement = (finalScore == null)
                    ? "Awarded on " + dateStr
                    : String.format("with a score of %.0f%% on %s", finalScore, dateStr);
            doc.add(centered(achievement,
                    new Font(Font.TIMES_ROMAN, 13, Font.NORMAL, INK)));

            // ── Signature labels (rendered at fixed coords via PdfContentByte) ──
            renderSignatureLabel(cb, leftSigX + sigW / 2, sigY - 14,
                    instructorName, "Course Instructor");
            renderSignatureLabel(cb, rightSigX + sigW / 2, sigY - 14,
                    brandConfig.getName(), "Issuing Platform");

            // ── Cert ID + verification URL, bottom-left ─────────────
            cb.beginText();
            cb.setFontAndSize(com.lowagie.text.pdf.BaseFont.createFont(
                    com.lowagie.text.pdf.BaseFont.COURIER,
                    com.lowagie.text.pdf.BaseFont.WINANSI, false), 9);
            cb.setColorFill(MUTED);
            cb.setTextMatrix(60, 50);
            cb.showText("Certificate ID: " + certNumber);
            cb.endText();

            cb.beginText();
            cb.setFontAndSize(com.lowagie.text.pdf.BaseFont.createFont(
                    com.lowagie.text.pdf.BaseFont.HELVETICA,
                    com.lowagie.text.pdf.BaseFont.WINANSI, false), 8);
            cb.setColorFill(MUTED);
            cb.setTextMatrix(60, 38);
            String verifyHost = brandConfig.getWebsite()
                    .replaceFirst("^https?://", "")
                    .replaceFirst("/.*$", "");
            cb.showText("Verify: " + verifyHost + "/verify/" + certNumber);
            cb.endText();

            doc.close();
            log.info("PDF generated: {}", filePath);
        } catch (Exception e) {
            log.error("Failed to generate PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate certificate PDF");
        }
    }

    private static Paragraph spacer(float leading) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(leading);
        return p;
    }

    private static Paragraph centered(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private static void renderSignatureLabel(PdfContentByte cb, float centerX, float y,
                                              String name, String role) throws Exception {
        var bf = com.lowagie.text.pdf.BaseFont.createFont(
                com.lowagie.text.pdf.BaseFont.HELVETICA_BOLD,
                com.lowagie.text.pdf.BaseFont.WINANSI, false);
        cb.beginText();
        cb.setFontAndSize(bf, 11);
        cb.setColorFill(INK);
        float w = bf.getWidthPoint(name, 11);
        cb.setTextMatrix(centerX - w / 2, y);
        cb.showText(name);
        cb.endText();

        var bfReg = com.lowagie.text.pdf.BaseFont.createFont(
                com.lowagie.text.pdf.BaseFont.HELVETICA,
                com.lowagie.text.pdf.BaseFont.WINANSI, false);
        cb.beginText();
        cb.setFontAndSize(bfReg, 9);
        cb.setColorFill(MUTED);
        float w2 = bfReg.getWidthPoint(role, 9);
        cb.setTextMatrix(centerX - w2 / 2, y - 12);
        cb.showText(role);
        cb.endText();
    }

    // Surfaces the public-facing list used by the verification UI.
    // Includes only fields that are safe to expose unauthenticated.
    @SuppressWarnings("unused")
    private static Set<String> safePublicFields() {
        return new HashSet<>(Arrays.asList(
                "certificateId", "studentName", "courseTitle", "issuedAt", "finalScore"
        ));
    }
}
