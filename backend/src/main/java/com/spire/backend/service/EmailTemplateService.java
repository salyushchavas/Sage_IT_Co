package com.spire.backend.service;

import com.spire.backend.config.BrandConfig;
import com.spire.backend.entity.Certificate;
import com.spire.backend.entity.ConsultantApplication;
import com.spire.backend.entity.Course;
import com.spire.backend.entity.SessionRequest;
import com.spire.backend.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Builds and dispatches every transactional email the platform
 * sends. Each public method maps to one of the ten template
 * categories the product spec defines; a single {@link #wrap}
 * helper renders the shared chrome (teal header + body card +
 * footer) so individual emails stay terse.
 *
 * Templates are inline string-builders rather than a templating
 * engine — keeps the dependency footprint small (no Thymeleaf /
 * Mustache) and the markup is small enough that the duplication
 * doesn't matter. If the template count grows past ~20 we'd switch.
 *
 * Every send delegates to {@link EmailService}, which silently
 * skips when SMTP isn't configured.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailTemplateService {


    private final EmailService emailService;
    private final BrandConfig brandConfig;
    private final com.spire.backend.repository.UserRepository userRepository;
    /** Phase B — resolve the owning ERM (and super-admin fallback) for
     *  the owner-routed consultant-submit review notification. */
    private final com.spire.backend.repository.AgreementUserRepository agreementUserRepository;
    /** Lazy to break a potential cycle with AgreementDocumentService
     *  (which doesn't currently inject EmailTemplateService, but might
     *  in a future batch -- @Lazy makes the wiring safe regardless). */
    @org.springframework.context.annotation.Lazy
    private final AgreementDocumentService agreementDocumentService;

    /** Short helper — the brand name shows up in dozens of subject /
     *  body strings, so this keeps each call site terse and the
     *  template files diffable when a brand swap happens. */
    private String brandName() {
        return brandConfig.getName();
    }

    @Value("${app.url:https://sageitco.com}")
    private String appUrl;

    /** Single hardcoded agreement-ERM operator email. Used as the
     *  notification destination for two-stage workflow events the
     *  ERM needs to act on (consultant submitted, completed PDF). */
    @Value("${agreement-erm.email:ermuser@sageitco.com}")
    private String agreementErmEmail;

    /**
     * Internal operations mailbox that receives a CC of certain
     * lifecycle events (program selection, future review queues).
     * Empty default keeps the internal-copy branch a no-op when
     * the env var isn't set on a dev box.
     */
    @Value("${program.operations.email:}")
    private String operationsEmail;

    /** Phase 4 — program coordinator display name on the intro email. */
    @Value("${program.coordinator.name:Deepthi R}")
    private String coordinatorName;

    /** Phase 6 — finance team inbox for Phase 1 completion + check
     *  notifications. Empty default skips the finance copy. */
    @Value("${program.finance.email:}")
    private String financeEmail;

    // ── 1. Welcome (sent LAST, after agreement is fully accepted) ───
    /**
     * Final onboarding email. Fires after the user has completed
     * email verification and the OTP-confirmed agreement acceptance.
     *
     * Branches on whether the user is on the Phase 1-3B participant
     * lifecycle (has a participantId) or the legacy LMS flow (no
     * participantId, course-only). The Phase 4 copy primes the
     * participant for the team-assembly step that runs immediately
     * after; the legacy copy points them at the course catalog.
     */
    public boolean sendWelcomeEmail(User user) {
        String body;
        String subject;
        String title;
        if (user.getParticipantId() != null && !user.getParticipantId().isBlank()) {
            // Phase 4 — participant lifecycle.
            body = p("Dear " + escape(user.getFullName() == null ? "there" : user.getFullName()) + ",")
                    + p("Congratulations! Your enrollment is confirmed and your agreement is on file.")
                    + receipt(
                            "Participant ID: " + safe(user.getParticipantId()),
                            "Technology: " + safe(user.getSelectedTechnology())
                    )
                    + p("Your team is being assembled. You will receive introduction emails "
                            + "shortly with your:")
                    + bullet("Program Coordinator")
                    + bullet("Relationship Manager (ERM)")
                    + bullet("Career Coach and Technical Advisor")
                    + p("Once your team is ready, your dashboard will open with your "
                            + "personalised roadmap and next steps.")
                    + p("We're excited to support your career journey!")
                    + p("Regards,<br/>" + brandName() + "");
            subject = "Welcome to " + brandName() + ", " + firstName(user) + "!";
            title = "Welcome aboard, " + firstName(user) + "!";
        } else {
            // Legacy LMS flow.
            body = p("Hi " + firstName(user) + ",")
                    + p("You're all set! Your account is verified and your agreement is on file.")
                    + p("You've joined a learning platform where every course comes with personal "
                            + "mentorship, career services, and verified certificates.")
                    + p("Here's what to do next:")
                    + bullet("Browse courses and find your first one")
                    + bullet("Each course includes a dedicated mentor")
                    + bullet("Complete courses to earn verified certificates")
                    + button("Browse Courses", appUrl + "/courses")
                    + p("Welcome aboard!")
                    + muted("— The " + brandName() + " Team");
            subject = "Welcome to " + brandName() + ", " + firstName(user) + "!";
            title = "You're all set, " + firstName(user) + "!";
        }
        return emailService.sendEmail(user.getEmail(), subject, wrap(title, body));
    }

    // ── Phase 1C: profile reminder (cron) ────────────────────────────
    /**
     * Daily reminder for participants still under 100% profile
     * completion. Cron-driven; subject mirrors the on-dashboard
     * banner copy. Sent at most 3 times per user (controlled by the
     * cron caller, not here).
     */
    public boolean sendProfileReminderEmail(User user, int completionPct,
                                         java.util.List<String> remainingSteps) {
        String first = firstName(user);
        StringBuilder steps = new StringBuilder();
        for (String step : remainingSteps) {
            steps.append(bullet(escape(step)));
        }
        String body = p("Hi " + escape(first) + ",")
                + p("Your " + brandName() + " profile is "
                        + "<strong>" + completionPct + "%</strong> complete.")
                + p("To start enrolling in courses, finish these quick steps:")
                + steps.toString()
                + button("Continue Setup", appUrl + "/dashboard?tab=complete-profile")
                + muted("— " + brandName() + "");
        String subject = "You're " + completionPct
                + "% there — finish your profile in 10 minutes";
        return emailService.sendEmail(user.getEmail(), subject,
                wrap("Finish your " + brandConfig.getShortName() + " profile", body));
    }

    // ── Phase 1C: profile-complete celebration ──────────────────────
    /**
     * Fired the moment the participant ticks the last of the six
     * profile-completion boxes. The welcome chain is a separate
     * email (sendWelcomeEmail) sent right after — this one just
     * confirms the gate has been crossed.
     */
    public void sendProfileCompleteEmail(User user) {
        String first = firstName(user);
        String body = p("Hi " + escape(first) + ",")
                + p("Your profile is complete! You can now enroll in courses, "
                        + "request mentor sessions, and access every feature on " + brandName() + ".")
                + p("Your team — Relationship Manager, Career Coach, "
                        + "Technical Advisor — is being assembled. You'll hear from "
                        + "them shortly with personalised intros.")
                + button("Open Dashboard", appUrl + "/dashboard")
                + muted("— " + brandName() + "");
        emailService.sendEmail(user.getEmail(),
                "Welcome aboard, " + first + "! Your profile is complete",
                wrap("Profile complete!", body));
    }

    // ── Build T — approval-request notification (Manager / Accounts) ──
    /**
     * Build T — notify a ROUTED approver (the specific Manager / Accounts the
     * ERM selected via Build K) that an agreement is awaiting their approval
     * after the ERM sends it for approval. Branded body with consultant, ERM,
     * and phase; a login-required CTA to the approver dashboard (no public deep
     * link). Best-effort: the caller swallows failures so the send-for-approval
     * action never blocks. Only the routed approver(s) are ever emailed.
     */
    public void sendApprovalRequestNotification(
            String toEmail, String approverName, String consultantName,
            String ermName, int phase) {
        if (toEmail == null || toEmail.isBlank()) return;
        String greetName = (approverName == null || approverName.isBlank())
                ? "there" : approverName;
        String body = p("Hi " + escape(greetName) + ",")
                + p("An agreement is awaiting your approval on " + brandName() + ".")
                + receipt(
                        // escape(safe(..)) — null-guard then HTML-escape, since
                        // the consultant name is user-controlled (injection).
                        "Consultant: " + escape(safe(consultantName)),
                        "ERM: " + escape(safe(ermName)),
                        "Phase: " + phase)
                + p("Please review the agreement and record your decision "
                        + "(Approve or Request revision) from your approvals "
                        + "dashboard. You'll need to sign in.")
                + button("Review in your dashboard", appUrl + "/agreements/approvals")
                + muted("— " + brandName() + "");
        emailService.sendEmail(toEmail,
                "Action needed — an agreement is awaiting your approval",
                wrap("Agreement awaiting your approval", body));
    }

    // ── 12. Weekly report reminder (Phase 5A — Mondays) ─────────────
    /**
     * Monday nudge for participants in WEEKLY_REPORTING_ACTIVE who
     * haven't submitted the current week's report yet. Best-effort —
     * the job logs and continues on any per-user failure.
     */
    public boolean sendWeeklyReminderEmail(User user, java.time.LocalDate weekStart, java.time.LocalDate weekEnd) {
        String first = firstName(user);
        String body = p("Hi " + escape(first) + ",")
                + p("A quick reminder that your weekly submission report for "
                        + escape(weekStart.toString()) + " – " + escape(weekEnd.toString())
                        + " is due. Logging your job submissions, resume activity, and any "
                        + "interview prep keeps your ERM in the loop and your roadmap on track.")
                + button("Submit Weekly Report", appUrl + "/dashboard")
                + muted("If you've already submitted, you can ignore this reminder.");
        return emailService.sendEmail(user.getEmail(),
                "Weekly report due — " + brandName() + "",
                wrap("Your weekly report is due", body));
    }

    // ── 16b. Weekly report overdue (checklist 4.2) ──────────────────
    /** The participant's report for a finished week wasn't submitted by its due date. */
    public boolean sendWeeklyOverdueEmail(User user, java.time.LocalDate weekStart, java.time.LocalDate weekEnd) {
        String first = firstName(user);
        String body = p("Hi " + escape(first) + ",")
                + p("Your weekly report for <strong>" + escape(weekStart.toString()) + " – "
                        + escape(weekEnd.toString()) + "</strong> was due on "
                        + escape(weekEnd.plusDays(1).toString()) + " and hasn't been submitted yet. "
                        + "You can still file it from your dashboard; it will show as late.")
                + button("Submit last week's report", appUrl + "/dashboard?tab=weekly")
                + muted("Your ERM can see which weeks are overdue.");
        return emailService.sendEmail(user.getEmail(),
                "Weekly report overdue — " + brandName(),
                wrap("Your weekly report is overdue", body));
    }

    // ── 16c. "I need help" in a weekly report (checklist 4.2) ───────
    /** The participant ticked "I need help": their ERM is told straight away. */
    public boolean sendWeeklyEscalationEmail(User erm, User participant, java.time.LocalDate weekStart, String detail) {
        if (erm == null || erm.getEmail() == null) return false;
        String url = appUrl + "/erm-dashboard?participant=" + participant.getId();
        String body = p("Hi " + escape(firstName(erm)) + ",")
                + p("<strong>" + escape(safe(participant.getFullName())) + "</strong> ("
                        + escape(safe(participant.getParticipantId())) + ") asked for help in their weekly report "
                        + "for the week of " + escape(weekStart.toString()) + ".")
                + (detail == null || detail.isBlank() ? "" : quote(escape(detail)))
                + button("Open their weekly report", url)
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(erm.getEmail(),
                "Help requested: " + safe(participant.getFullName()) + " (" + safe(participant.getParticipantId()) + ")",
                wrap("A participant asked for help", body));
    }

    // ── 19. Staff onboarding ────────────────────────────────────────
    /**
     * Login details for a staff account an admin created (or new ones): the
     * sign-in email and a temporary password, sent to the person's own
     * email. They choose their own password at first sign-in. The email log
     * keeps only the subject, never the password.
     */
    public boolean sendStaffLoginEmail(User user, String roleLabel, String temporaryPassword, boolean newDetails) {
        String body = p("Hi " + escape(firstName(user)) + ",")
                + p(newDetails
                        ? "Here are new login details for your " + brandName() + " account."
                        : "An account has been created for you on the " + brandName() + " portal as <strong>"
                                + escape(roleLabel) + "</strong>.")
                + receipt("Sign-in email: " + user.getEmail(),
                        "Temporary password: " + temporaryPassword)
                + button("Sign in", appUrl + "/login")
                + p("When you sign in you'll be asked to choose your own password; the temporary one stops "
                        + "working after that.")
                + muted("If you weren't expecting this email, reply and let us know.")
                + p("Regards,<br/>" + brandName() + "");
        String to = user.getPersonalEmail() != null && !user.getPersonalEmail().isBlank()
                ? user.getPersonalEmail() : user.getEmail();
        return emailService.sendEmail(to,
                (newDetails ? "New login details" : "Your staff account") + " — " + brandName(),
                wrap(newDetails ? "New login details" : "Welcome to " + escape(brandName()), body));
    }

    /** An invitation to enroll in the program, with the enrollment link. */
    public boolean sendParticipantInviteEmail(String email, String fullName, String link) {
        String first = fullName == null || fullName.isBlank() ? "there" : fullName.trim().split("\\s+")[0];
        String body = p("Hi " + escape(first) + ",")
                + p("You're invited to join the " + brandName() + " program. Enrolling takes a few minutes: "
                        + "you'll confirm your email, get your Participant ID, and then complete your profile.")
                + button("Start your enrollment", link)
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(email, "You're invited to enroll — " + brandName(),
                wrap("You're invited", body));
    }

    // ── 18. Online course purchase (checklist 5.4) ──────────────────
    /** The participant paid for courses online; they're enrolled. */
    public boolean sendCoursePurchaseEmail(User user, java.util.List<com.spire.backend.entity.Course> courses,
                                           java.math.BigDecimal total, String reference) {
        StringBuilder list = new StringBuilder();
        for (com.spire.backend.entity.Course c : courses) list.append(bullet(c.getTitle()));
        String body = p("Dear " + escape(firstName(user)) + ",")
                + p("Thank you, your payment went through and you're enrolled in:")
                + list
                + receipt("Amount paid: " + Money.usd(total),
                        "Date: " + usDateTime(java.time.LocalDateTime.now()),
                        "Reference: " + (reference == null ? "—" : reference))
                + button("Go to My Courses", appUrl + "/dashboard?tab=courses")
                + muted("This email is your receipt. Save it for your records.");
        return emailService.sendEmail(user.getEmail(),
                "Payment received — you're enrolled — " + brandName(),
                wrap("Payment received", body));
    }

    // ── 17. Employment (checklist 4.5) ──────────────────────────────
    /** The participant submitted (or corrected) their employment details: their ERM verifies them. */
    public boolean sendEmploymentToVerifyEmail(User erm, User participant,
                                               com.spire.backend.entity.EmploymentAcceptance row, boolean corrected) {
        if (erm == null || erm.getEmail() == null) return false;
        String body = p("Hi " + escape(firstName(erm)) + ",")
                + p("<strong>" + escape(safe(participant.getFullName())) + "</strong> ("
                        + escape(safe(participant.getParticipantId())) + ") "
                        + (corrected ? "sent corrected employment details" : "accepted a job offer")
                        + ". Please verify the details, or send them back with what needs correcting.")
                + receipt("Employer: " + safe(row.getEmployerClient()),
                        "Job title: " + safe(row.getJobTitle()),
                        "Start date: " + (row.getStartDate() == null ? "—" : row.getStartDate().toString()))
                + button("Review employment", appUrl + "/erm-dashboard?tab=employment")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(erm.getEmail(),
                "Employment to verify: " + safe(participant.getFullName()) + " (" + safe(participant.getParticipantId()) + ")",
                wrap("Employment to verify", body));
    }

    /** The ERM sent the participant's employment details back for correction. */
    public boolean sendEmploymentReturnedEmail(User user, String reason) {
        String body = p("Dear " + escape(firstName(user)) + ",")
                + p("Your relationship manager reviewed your employment details and needs a correction before "
                        + "they can verify them.")
                + p("<strong>What needs correcting:</strong>")
                + quote(escape(reason))
                + button("Correct your employment details", appUrl + "/dashboard?tab=employment")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(user.getEmail(),
                "Action needed: please correct your employment details — " + brandName(),
                wrap("Employment details need a correction", body));
    }

    /** Phase 1 is approved: Phase 2 (post-offer support) begins on the employment start date. */
    public boolean sendPhase2StartedEmail(User user, java.time.LocalDate startDate) {
        String when = startDate == null ? "now" : startDate.format(
                java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.US));
        String body = p("Dear " + escape(firstName(user)) + ",")
                + p("Your relationship manager has approved your Phase 1 completion. Congratulations again on "
                        + "your new role!")
                + p("Your Phase 2 post-offer support begins on <strong>" + escape(when) + "</strong>: "
                        + "transition and onboarding support, role-aligned coaching, documentation support and "
                        + "technical guidance, as set out in your agreement.")
                + button("Open your dashboard", appUrl + "/dashboard?tab=employment")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(user.getEmail(),
                "Phase 1 approved — your Phase 2 support begins — " + brandName(),
                wrap("Your Phase 2 support begins", body));
    }

    // ── 3. Document upload reminder (cron-driven) ────────────────────
    /**
     * Email #3, roadmap "Acknowledgment / Document Upload Reminder"
     * (checklist 1.5): says what is still to do — the acknowledgment, or
     * the named required documents — and links straight to that page.
     * Fired by the daily document-reminder job, which rate-limits.
     */
    public boolean sendDocumentReminderEmail(User user, boolean acknowledgmentPending,
                                             List<String> missingDocuments) {
        String firstName = firstName(user);
        StringBuilder todo = new StringBuilder();
        if (acknowledgmentPending) {
            todo.append(bullet("Accept the program acknowledgment"))
                .append(bullet("Upload your required documents"));
        } else {
            for (String doc : missingDocuments) todo.append(bullet(doc));
        }
        String body = p("Dear " + escape(firstName) + ",")
                + p("Your Participant ID (<strong>"
                        + safe(user.getParticipantId())
                        + "</strong>) is ready. To continue your enrollment, please "
                        + (acknowledgmentPending ? "complete these steps:" : "upload these required documents:"))
                + todo
                + button(acknowledgmentPending ? "Continue enrollment" : "Upload Documents",
                        appUrl + (acknowledgmentPending ? "/acknowledgment" : "/document-upload"))
                + p("If you've already done this, you can ignore this reminder.")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(user.getEmail(),
                "Action needed: Complete your documents — " + brandName() + "",
                wrap("Document upload reminder", body));
    }

    // ── 5b. Document review outcome (roadmap step 5) ────────────────
    /**
     * Operations sent a document back: an upload was rejected, or a
     * request to mark a required document "not applicable" was
     * declined. Says which document and why, and links to the upload
     * page. The document itself is never attached (sensitive documents
     * stay in the portal).
     */
    public void sendDocumentResubmitEmail(User user, String documentLabel,
                                          String reason, boolean requestDeclined) {
        String firstName = firstName(user);
        String what = requestDeclined
                ? "Operations reviewed your request to mark your <strong>"
                        + escape(documentLabel) + "</strong> as not applicable, "
                        + "and needs you to upload it after all."
                : "Operations reviewed your <strong>" + escape(documentLabel)
                        + "</strong> and needs you to upload it again.";
        String body = p("Dear " + escape(firstName) + ",")
                + p(what)
                + p("<strong>Reason:</strong>")
                + quote(escape(reason))
                + button("Upload again", appUrl + "/document-upload")
                + p("Your other documents are not affected.")
                + p("Regards,<br/>" + brandName() + "");
        emailService.sendEmail(user.getEmail(),
                "Action needed: please upload your " + documentLabel + " again — " + brandName(),
                wrap("Document needs attention", body));
    }

    /** Operations approved a request to mark a required document "not applicable". */
    public void sendDocumentExceptionApprovedEmail(User user, String documentLabel) {
        String firstName = firstName(user);
        String body = p("Dear " + escape(firstName) + ",")
                + p("Operations approved your request: your <strong>"
                        + escape(documentLabel) + "</strong> is marked as not "
                        + "applicable, so you don't need to upload it.")
                + p("Once your other required documents are in, you can continue "
                        + "your enrollment.")
                + button("Continue enrollment", appUrl + "/document-upload")
                + p("Regards,<br/>" + brandName() + "");
        emailService.sendEmail(user.getEmail(),
                "Your document request was approved — " + brandName(),
                wrap("Request approved", body));
    }

    // ── 6. Check upload confirmation (Phase 3B) ─────────────────────
    /**
     * Email #6 — fired immediately after a participant uploads a
     * check soft-copy. Confirms receipt to the participant; finance
     * sees the upload on their dashboard.
     */
    public void sendCheckUploadConfirmationEmail(User user) {
        String firstName = firstName(user);
        String body = p("Dear " + escape(firstName) + ",")
                + p("Your check soft-copies have been uploaded successfully "
                        + "and are stored securely. Our finance team will "
                        + "review them shortly.")
                + receipt(
                        "Participant ID: " + safe(user.getParticipantId()))
                + button("View dashboard", appUrl + "/dashboard")
                + p("Regards,<br/>" + brandName() + "");
        emailService.sendEmail(user.getEmail(),
                "Check upload received — " + brandName() + "",
                wrap("Check upload received", body));
    }

    // ── 6b. Check copy rejected by Finance (checklist 2.4) ─────────
    /**
     * Finance couldn't accept a check copy: says why and links to a
     * re-upload. The image is never attached (roadmap §13.1).
     */
    public boolean sendCheckRejectedEmail(User user, String maskedNumber, String reason, Long checkId) {
        String firstName = firstName(user);
        String body = p("Dear " + escape(firstName) + ",")
                + p("Our finance team reviewed your check copy"
                        + (maskedNumber == null ? "" : " (check " + escape(maskedNumber) + ")")
                        + " and needs you to upload it again.")
                + p("<strong>Reason:</strong>")
                + quote(escape(reason))
                + button("Upload a new copy", appUrl + "/check-upload?replace=" + checkId)
                + muted("Check images are only ever handled through the secure portal, never by email.")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(user.getEmail(),
                "Action needed: please upload your check copy again — " + brandName(),
                wrap("Check copy needs attention", body));
    }

    // ── 11. Coordinator intro (Phase 4 Step 12) ─────────────────────
    /**
     * "Meet your program coordinator" — fired right after the
     * welcome email. Coordinator name + email come from env
     * (program.coordinator.name / program.coordinator.email),
     * defaulting to the values shipped in the PRD spec.
     */
    public boolean sendCoordinatorIntroEmail(User user) {
        String coordName = coordinatorName == null || coordinatorName.isBlank()
                ? "Deepthi" : coordinatorName;
        String body = p("Dear " + escape(user.getFullName() == null ? "there" : user.getFullName()) + ",")
                + p("I'm " + escape(coordName) + ", your program coordinator at " + brandName() + ".")
                + p("I'll be overseeing your overall program experience and ensuring everything "
                        + "runs smoothly. If you have any general questions about the program, "
                        + "feel free to reach out.")
                + p("Your relationship manager will be introduced shortly — they'll be your "
                        + "primary point of contact going forward.")
                + p("Looking forward to working with you!")
                + p("Best regards,<br/>"
                        + escape(coordName) + "<br/>"
                        + "<span style=\"color:#6b7280;\">Program Coordinator, " + brandName() + "</span>");
        return emailService.sendEmail(
                user.getEmail(),
                "Meet your program coordinator — " + brandName() + "",
                wrap("Meet " + escape(coordName), body));
    }

    // ── 12. ERM intro — participant copy (Phase 4 Step 13) ─────────
    public boolean sendErmIntroEmail(User user, com.spire.backend.entity.User erm) {
        String ermName = erm == null || erm.getFullName() == null
                ? "Your ERM" : erm.getFullName();
        String ermEmail = erm == null ? "" : safe(erm.getEmail());
        String body = p("Dear " + escape(user.getFullName() == null ? "there" : user.getFullName()) + ",")
                + p("Your Employee Relationship Manager (ERM) has been assigned:")
                + receipt(
                        "Name: " + safe(ermName),
                        "Email: " + ermEmail
                )
                + p(escape(ermName) + " is your primary communication owner. They will guide you "
                        + "through your program, review your weekly reports, and support you at "
                        + "every step.")
                + p("You can reach " + escape(ermName) + " via email or through your dashboard "
                        + "once it's ready.")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(
                user.getEmail(),
                "Your relationship manager — " + brandName() + "",
                wrap("Meet your ERM", body));
    }

    // ── 10. Signed agreement to the ERM (roadmap step 10) ───────────
    /**
     * Checklist 2.3: tells the ERM a participant's agreement is signed and
     * waiting for their review. A secure link only: the agreement opens in
     * the ERM dashboard after sign-in, never as an email attachment.
     */
    public boolean sendSignedAgreementToErmEmail(User erm, User participant,
                                                 String programSummary, String signedOn) {
        if (erm == null || erm.getEmail() == null) return false;
        String url = appUrl + "/erm-dashboard?participant=" + participant.getId();
        String body = p("Hi " + escape(firstName(erm)) + ",")
                + p("<strong>" + escape(safe(participant.getFullName())) + "</strong> has signed their "
                        + brandName() + " agreement. Please review it and confirm the participant is "
                        + "ready for onboarding.")
                + receipt(
                        "Participant: " + safe(participant.getFullName()),
                        "Participant ID: " + safe(participant.getParticipantId()),
                        "Program: " + safe(programSummary),
                        "Signed: " + safe(signedOn))
                + button("Review the signed agreement", url)
                + ctaFallback(url)
                + muted("The agreement opens in your ERM dashboard after you sign in; it is never sent as an attachment.")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(erm.getEmail(),
                "Signed agreement ready for review: " + safe(participant.getFullName())
                        + " (" + safe(participant.getParticipantId()) + ")",
                wrap("Signed agreement ready for review", body));
    }

    // ── 14b. Coach — new participant (checklist 3.2) ─────────────────
    /**
     * Tells a coach about their new participant: the slot they fill and
     * the program facts they need (roadmap §13: no identity documents, SSN
     * or finance data for coaches).
     */
    public boolean sendCoachNewParticipantEmail(User coach, User participant, String slotLabel,
                                                com.spire.backend.entity.ProgramSelection program) {
        if (coach == null || coach.getEmail() == null) return false;
        String url = appUrl + "/coach-dashboard";
        String body = p("Hi " + escape(firstName(coach)) + ",")
                + p("You're the <strong>" + escape(safe(slotLabel)) + "</strong> for a new participant.")
                + receipt(
                        "Participant: " + safe(participant.getFullName()),
                        "Participant ID: " + safe(participant.getParticipantId()),
                        "Program: " + safe(program == null ? null : program.getProgram())
                                + (program != null && program.getPhase() != null ? " · " + program.getPhase() : ""),
                        "Skillset: " + safe(program == null ? participant.getSelectedTechnology() : program.getSkillset()),
                        "Target role: " + safe(program == null ? null : program.getTargetJobTitle()),
                        "Availability: " + safe(program == null ? participant.getAvailability() : program.getAvailability()))
                + button("Open your coach dashboard", url)
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(coach.getEmail(),
                "New participant: " + safe(participant.getFullName()) + " (" + safe(participant.getParticipantId())
                        + ") — " + safe(slotLabel),
                wrap("New participant assigned", body));
    }

    // ── 12b. ERM intro — ERM-side notification ──────────────────────
    public boolean sendErmAssignmentNotification(com.spire.backend.entity.User erm,
                                              User participant,
                                              com.spire.backend.entity.ProgramSelection program) {
        if (erm == null || erm.getEmail() == null) return false;
        String programStr = program == null ? "—" : safe(program.getProgram());
        String phaseStr = program == null ? "—" : safe(program.getPhase());
        String tech = program != null && program.getSkillset() != null
                ? program.getSkillset() : safe(participant.getSelectedTechnology());
        String target = program == null ? "—" : safe(program.getTargetJobTitle());

        String body = p("Hi " + firstName(erm) + ",")
                + p("A new participant has been assigned to you:")
                + receipt(
                        "Name: " + safe(participant.getFullName()),
                        "Participant ID: " + safe(participant.getParticipantId()),
                        "Email: " + safe(participant.getEmail()),
                        "Program: " + programStr,
                        "Phase: " + phaseStr,
                        "Technology: " + safe(tech),
                        "Target: " + safe(target)
                )
                + p("Please review their profile and prepare for onboarding.")
                + muted("— " + brandName() + " operations");
        return emailService.sendEmail(
                erm.getEmail(),
                "New participant assigned: " + safe(participant.getFullName())
                        + " (" + safe(participant.getParticipantId()) + ")",
                wrap("New participant assignment", body));
    }

    // ── 13. Coach / advisor assignment (Phase 4 Step 14) ────────────
    /**
     * Sent after coaches have been assigned (or marked pending).
     * Accepts a map keyed by role label ("Career Coach", "Technical
     * Advisor", …) → coach display name. Roles with no available
     * assignee can pass through with value "Awaiting assignment".
     */
    public boolean sendCoachAssignmentEmail(User user, java.util.Map<String, String> coachesByRole) {
        StringBuilder rows = new StringBuilder();
        if (coachesByRole != null) {
            for (java.util.Map.Entry<String, String> e : coachesByRole.entrySet()) {
                rows.append(safe(e.getKey())).append(": ").append(safe(e.getValue())).append("\n");
            }
        }
        String[] receiptLines = rows.toString().split("\n");
        String body = p("Dear " + escape(user.getFullName() == null ? "there" : user.getFullName()) + ",")
                + p("Your support team has been assembled:")
                + receipt(receiptLines)
                + p("Your first checkpoint will be scheduled by your ERM. You can view your "
                        + "team contacts in your dashboard.")
                + button("Enter Your Dashboard", appUrl + "/dashboard")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(
                user.getEmail(),
                "Your coaching team — " + brandName() + "",
                wrap("Your coaching team", body));
    }

    // ── 1c. Program selection confirmation (Phase 3A) ───────────────
    /**
     * Sent immediately after a participant finalises their program
     * selection. Confirms the chosen program / phase / skillset back
     * to the participant and points them at the next onboarding step
     * (/agreement). The internal operations-mailbox copy is optional
     * — controlled by {@code program.operations.email} — so a dev
     * env without that env var still sends the participant copy.
     */
    public void sendProgramSelectionConfirmationEmail(
            com.spire.backend.entity.User user,
            com.spire.backend.entity.ProgramSelection selection
    ) {
        String greeting = user.getFullName() == null || user.getFullName().isBlank()
                ? "there" : user.getFullName();
        String body = p("Dear " + escape(greeting) + ",")
                + p("Your program selection has been recorded:")
                + receipt(
                        "Program: " + safe(selection.getProgram()),
                        "Phase: " + safe(selection.getPhase()),
                        "Technology: " + safe(selection.getSkillset()),
                        "Target Job Title: " + safe(selection.getTargetJobTitle()),
                        "Availability: " + safe(selection.getAvailability()),
                        "Participant ID: " + safe(user.getParticipantId())
                )
                + p("Your next step: Review and sign your agreement.")
                + button("Continue to Agreement", appUrl + "/agreement")
                + p("Regards,<br/>" + brandName() + "");
        emailService.sendEmail(
                user.getEmail(),
                "Program selection confirmed — " + brandName() + "",
                wrap("Program selected", body));

        // Internal operations notification — kept best-effort and
        // off the participant's eyeline. Same template wrapper as
        // the participant copy so the inbox layout stays consistent.
        if (operationsEmail != null && !operationsEmail.isBlank()) {
            String opsBody = p("New program selection on " + brandName() + ":")
                    + receipt(
                            "Participant: " + safe(user.getFullName())
                                    + " (" + safe(user.getParticipantId()) + ")",
                            "Email: " + safe(user.getEmail()),
                            "Program: " + safe(selection.getProgram()),
                            "Phase: " + safe(selection.getPhase()),
                            "Skillset: " + safe(selection.getSkillset()),
                            "Target: " + safe(selection.getTargetJobTitle()),
                            "Availability: " + safe(selection.getAvailability())
                    )
                    + p("→ Ready for agreement generation.");
            try {
                emailService.sendEmail(
                        operationsEmail,
                        "New program selection: "
                                + safe(user.getFullName())
                                + " (" + safe(user.getParticipantId()) + ")",
                        wrap("Program selection — internal", opsBody));
            } catch (Exception ignored) {
                // Internal-copy outage doesn't fail the participant flow.
            }
        }
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    // ── 1b. Participant ID (Phase 1B) ───────────────────────────────
    /**
     * Sent immediately after OTP verification once the platform has
     * minted a SIT-2026-XXXXX participant ID. Confirms the ID to the
     * user and routes them to the next onboarding step.
     */
    public boolean sendParticipantIdEmail(User user, String participantId) {
        String greeting = user.getFullName() == null || user.getFullName().isBlank()
                ? "there" : user.getFullName();
        String idBlock =
                "<div style=\"text-align:center; margin:24px 0;\">"
                        + "<span style=\"display:inline-block; font-size:26px; font-weight:bold; "
                        + "letter-spacing:4px; color:" + brandConfig.getPrimaryColor() + "; background:#f9fafb;"
                        + "padding:14px 28px; border-radius:8px; "
                        + "border:1px solid #e5e7eb; font-family:'Courier New',monospace;\">"
                        + escape(participantId)
                        + "</span></div>";
        String body = p("Dear " + escape(greeting) + ",")
                + p("Welcome! Your email has been verified successfully.")
                + p("Your official " + brandName() + " Participant ID is:")
                + idBlock
                + p("Please keep this ID for your records. It will be used in all future "
                        + "communications and documents.")
                + p("Your next step: Complete the acknowledgment and upload your required documents.")
                + button("Continue to Next Step", appUrl + "/participant-id")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(
                user.getEmail(),
                "Your " + brandName() + " Participant ID: " + participantId,
                wrap("Welcome to " + brandName() + "", body));
    }

    // ── 2. Email verification (6-digit OTP) ─────────────────────────
    public void sendVerificationCodeEmail(User user, String code) {
        // Big, centered code block — built inline rather than reusing
        // button() because the styling is intentionally distinct
        // (huge letter-spaced number on a teal-tinted card) so the
        // recipient's eye lands on the code immediately.
        String codeBlock =
                "<div style=\"text-align:center; margin:24px 0;\">"
                        + "<span style=\"display:inline-block; font-size:32px; font-weight:bold; "
                        + "letter-spacing:8px; color:" + brandConfig.getPrimaryColor() + "; background:#f9fafb;"
                        + "padding:12px 24px; border-radius:8px; "
                        + "border:1px solid #e5e7eb; font-family:'Courier New',monospace;\">"
                        + escape(code)
                        + "</span>"
                        + "</div>";

        String body = p("Hi " + firstName(user) + ",")
                + p("Your verification code is:")
                + codeBlock
                + p("This code expires in 10 minutes.")
                + muted("If you didn't create an account on " + brandName() + ", you can safely ignore this email.");
        emailService.sendEmail(
                user.getEmail(),
                "Your verification code: " + code,
                wrap("Verify your email", body)
        );
    }

    // ── 2b. Agreement: "Reply YES" request ──────────────────────────
    /**
     * Sent the moment the user clicks "Accept" on /agreement. The
     * subject embeds a tracking marker {@code [AGREE-{userId}-{ts}]}
     * the IMAP inbox cron uses to match the user's reply back to
     * the pending acceptance row. Body asks the user to reply with
     * the literal word YES; any other reply is ignored. Returns
     * the subject so the caller can persist it on the row.
     */
    public String sendAgreementReplyRequestEmail(
            User user, String legalName, String ipAddress,
            long userId, long trackingTimestamp, byte[] pendingPdfBytes
    ) {
        // Subject embeds the tracking marker the IMAP cron uses to
        // match incoming replies back to this row. Format must stay
        // [AGREE-{userId}-{ts}] — see TRACKING_REGEX in the cron.
        String tracking = String.format("[AGREE-%d-%d]", userId, trackingTimestamp);
        String subject = "" + brandName() + " — Terms of Service Agreement " + tracking;
        // ipAddress is captured on the row for the audit trail but
        // intentionally not surfaced in the email body — the spec
        // wording deliberately reads as a formal letter rather than
        // a security receipt.
        if (ipAddress != null) { /* keep on signature for callers */ }

        String replyCallout =
                "<div style=\"text-align:center; margin:24px 0;\">"
                        + "<span style=\"display:inline-block; font-size:20px; font-weight:bold; "
                        + "letter-spacing:1px; color:" + brandConfig.getPrimaryColor() + "; background:#f9fafb;"
                        + "padding:12px 28px; border-radius:8px; "
                        + "border:1px solid #e5e7eb; font-family:Arial,Helvetica,sans-serif;\">"
                        + "Yes, I agree"
                        + "</span></div>";

        String body = p("Dear " + escape(legalName == null || legalName.isBlank()
                        ? firstName(user) : legalName) + ",")
                + p("Please find attached the Terms of Service agreement for <strong>" + brandName() + "</strong>.")
                + p("We request you to review the attached document carefully.")
                + p("To confirm your acceptance of these terms, please <strong>reply</strong> to this email with:")
                + replyCallout
                + p("By replying, you acknowledge that you have read and accept all terms and conditions stated in the attached document.")
                + p("This request expires in <strong>30 minutes</strong>.")
                + p("Regards,<br/>" + brandName() + "<br/>"
                        + "<span style=\"color:#6b7280;\">" + brandConfig.getContactEmail()
                        + " &nbsp;•&nbsp; " + brandConfig.getWebsite() + "</span>")
                + muted("If you did not initiate this, please ignore this email — no agreement will be recorded.");

        java.util.List<EmailService.Attachment> attachments =
                pendingPdfBytes == null || pendingPdfBytes.length == 0
                        ? java.util.List.of()
                        : java.util.List.of(new EmailService.Attachment(
                                brandConfig.getShortName() + "_Agreement_" + AgreementService.CURRENT_VERSION + ".pdf",
                                "application/pdf", pendingPdfBytes));

        emailService.sendEmail(user.getEmail(), subject,
                wrap("Terms of Service Agreement", body), attachments);
        return subject;
    }

    // ── 2c. Agreement: verification code (post-reply) ───────────────
    /**
     * Sent when the IMAP cron has detected a "YES" reply and the
     * backend has issued the OTP. Entering this code on the website
     * completes the acceptance.
     */
    public void sendAgreementCodeEmail(User user, String legalName, String code) {
        String codeBlock =
                "<div style=\"text-align:center; margin:24px 0;\">"
                        + "<span style=\"display:inline-block; font-size:32px; font-weight:bold; "
                        + "letter-spacing:8px; color:" + brandConfig.getPrimaryColor() + "; background:#f9fafb;"
                        + "padding:12px 24px; border-radius:8px; "
                        + "border:1px solid #e5e7eb; font-family:'Courier New',monospace;\">"
                        + escape(code)
                        + "</span>"
                        + "</div>";

        String greeting = legalName == null || legalName.isBlank()
                ? firstName(user) : legalName;

        String body = p("Dear " + escape(greeting) + ",")
                + p("Thank you for accepting the Terms of Service.")
                + p("Your verification code is:")
                + codeBlock
                + p("Enter this code on the website to complete your agreement.")
                + p("This code expires in 10 minutes.")
                + p("Regards,<br/>" + brandName() + "")
                + muted("If you didn't request this, ignore this email — your agreement won't be recorded.");
        emailService.sendEmail(
                user.getEmail(),
                "Agreement verification code: " + code,
                wrap("Verification code", body)
        );
    }

    /**
     * @deprecated Legacy magic-link flow. Kept compiling so any
     * straggling callers don't break, but unused under the OTP gate;
     * remove after the next deploy when the call sites are confirmed
     * gone from staging.
     */
    @Deprecated
    public void sendVerificationEmail(User user, String token) {
        String url = appUrl + "/verify-email?token=" + token;
        String body = p("Hi " + firstName(user) + ",")
                + p("Please verify your email to activate your account.")
                + button("Verify Email", url)
                + p("This link expires in 24 hours.")
                + muted("If you didn't create an account, ignore this email.");
        emailService.sendEmail(
                user.getEmail(),
                "Verify your email — " + brandName() + "",
                wrap("Verify your email", body)
        );
    }

    // ── 2d. Agreement: signed PDF delivery (post-verification) ──────
    /**
     * Sent immediately after the OTP verifies. Body confirms the
     * acceptance and attaches the personalized signed agreement PDF
     * generated by {@code AgreementPdfService}.
     *
     * @param pdfBytes  raw PDF bytes — base64-encoded into the relay
     *                  payload by {@link EmailService}
     * @param recordId  e.g. "AGR-2026-00042" — surfaced in the body
     *                  as the user's reference number
     */
    public void sendSignedAgreementEmail(
            User user, String legalName, String acceptedAtIst,
            String version, String recordId, byte[] pdfBytes
    ) {
        // Filename uses the user's legal name when available so the
        // attachment lands in their inbox with a personal anchor;
        // strip whitespace + non-filename-safe chars first.
        String namePart = (legalName == null || legalName.isBlank())
                ? (recordId == null ? "signed" : recordId)
                : legalName.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        String filename = brandConfig.getShortName() + "_Agreement_Signed_" + namePart + ".pdf";
        // legalName + version are kept on the signature for audit
        // logging on the caller side; the user-facing body shows
        // only the acceptance ID + timestamp per spec.

        String greeting = legalName == null || legalName.isBlank()
                ? firstName(user) : legalName;

        String body = p("Dear " + escape(greeting) + ",")
                + p("Your agreement with <strong>" + brandName() + "</strong> has been confirmed.")
                + p("Attached is your signed copy of the Terms of Service. Please keep this document for your records.")
                + receipt(
                        "Agreement ID: " + (recordId == null ? "—" : recordId),
                        "Accepted on: " + acceptedAtIst
                )
                + button("View on Platform", appUrl + "/dashboard")
                + p("Regards,<br/>" + brandName() + "");

        java.util.List<EmailService.Attachment> attachments =
                pdfBytes == null || pdfBytes.length == 0
                        ? java.util.List.of()
                        : java.util.List.of(new EmailService.Attachment(
                                filename, "application/pdf", pdfBytes));

        emailService.sendEmail(
                user.getEmail(),
                "Your signed agreement — " + brandName() + "",
                wrap("Agreement confirmed", body),
                attachments
        );
    }

    // ── 3. Password reset ───────────────────────────────────────────
    public void sendPasswordResetEmail(User user, String token) {
        String url = appUrl + "/reset-password?token=" + token;
        String body = p("Hi " + firstName(user) + ",")
                + p("We received a request to reset your password.")
                + button("Reset Password", url)
                + p("This link expires in 1 hour.")
                + muted("If you didn't request this, ignore this email. Your password won't change.");
        emailService.sendEmail(
                user.getEmail(),
                "Reset your password — " + brandName() + "",
                wrap("Reset your password", body)
        );
    }

    /**
     * Emails an agreement-console user their sign-in credentials (email +
     * admin-set temporary password) from the brand NO-REPLY address. Sent
     * manually by the super-admin from the admin console. The plaintext
     * password is supplied by the caller because it is only known at create /
     * reset time — it is hashed in storage and can never be re-derived.
     */
    public boolean sendAgreementUserCredentials(
            String email, String fullName, String tempPassword) {
        String loginUrl = appUrl + "/agreements/login";
        String name = (fullName == null || fullName.isBlank()) ? "there" : fullName.trim();
        String body = p("Hi " + escape(name) + ",")
                + p("An account has been created for you on the " + brandName()
                        + " agreement console. Use the temporary credentials below to sign in:")
                + receipt(
                        "Email: " + email,
                        "Temporary password: " + tempPassword)
                + button("Sign in", loginUrl)
                + muted("This is a temporary password — please contact your "
                        + "administrator if you need it changed. If you weren't "
                        + "expecting this email, you can safely ignore it.");
        return emailService.sendEmailFrom(
                brandConfig.getNoreplyEmail(),
                email,
                "Your " + brandName() + " console sign-in details",
                wrap("Your console account", body));
    }

    // ── Consultant Agreement feature (hidden / internal) ────────────

    /**
     * Sent to the consultant when an ERM creates (or resends) an
     * application. The link drops them on the public /consultant
     * landing where they enter their application ID + email and
     * receive an OTP.
     */
    public void sendConsultantApplicationCreated(ConsultantApplication application) {
        String url = appUrl + "/consultant/" + application.getApplicationId() + "/login";
        String displayName = application.getConsultantName() == null
                || application.getConsultantName().isBlank()
                ? "there"
                : application.getConsultantName().split(" ")[0];
        String body = p("Hi " + escape(displayName) + ",")
                + p(brandName() + " has prepared engagement details for your review. "
                        + "Please open the secure link below to review and sign.")
                + button("Review and Sign", url)
                + ctaFallback(url)
                + receipt(
                        "Application ID: " + application.getApplicationId(),
                        "Link expires: 7 days from issue")
                + muted("If you didn't expect this, please ignore. "
                        + "Your contact details with " + brandName() + " won't change.");
        emailService.sendEmail(
                application.getConsultantEmail(),
                "Action needed: Review your " + brandName() + " engagement details",
                wrap("Engagement details ready for review", body));
    }

    /**
     * Sent to the owning ERM when the consultant clicks "request
     * changes" on the review page. The ERM is the actor who edits
     * the application, so this email points them at the detail page
     * with the consultant's reason highlighted.
     */
    public boolean sendConsultantRevisionRequested(ConsultantApplication application) {
        if (!ermEmail(application).isPresent()) return false;
        String url = appUrl + "/agreement-erm/" + application.getApplicationId();
        String reason = application.getRevisionNotes() == null
                || application.getRevisionNotes().isBlank()
                ? "(no reason provided)"
                : application.getRevisionNotes();
        String body = p("Hi,")
                + p(escape(safeName(application)) + " has requested changes to the "
                        + "engagement details before signing.")
                + quote(escape(reason))
                + button("Open application", url)
                + ctaFallback(url)
                + receipt(
                        "Application ID: " + application.getApplicationId(),
                        "Consultant: " + safeName(application),
                        "Email: " + application.getConsultantEmail())
                + muted("Edit the details from the link above and the consultant "
                        + "will be re-notified automatically.");
        return emailService.sendEmail(
                ermEmail(application).get(),
                "Consultant requested revisions — Application " + application.getApplicationId(),
                wrap("Revision requested", body));
    }

    /**
     * Sent to the consultant after the ERM revises an application
     * following a {@code REVISION_REQUESTED} off-ramp. Tells them to
     * re-review and decide whether to verify or push back again.
     */
    public boolean sendConsultantApplicationUpdated(ConsultantApplication application) {
        String url = appUrl + "/consultant/" + application.getApplicationId() + "/login";
        String body = p("Hi " + escape(firstName(application)) + ",")
                + p(brandName() + " has updated your engagement details based on "
                        + "the changes you requested.")
                + button("Review and continue", url)
                + ctaFallback(url)
                + receipt(
                        "Application ID: " + application.getApplicationId(),
                        "Link expires: 7 days from issue")
                + muted("If you didn't expect this, please ignore.");
        return emailService.sendEmail(
                application.getConsultantEmail(),
                "Updated for your review — " + brandName() + " engagement",
                wrap("Details updated", body));
    }

    /**
     * Sent to the ERM once the consultant signs. Embeds the
     * Cloudinary-hosted signed PDF link. No attachment -- the URL
     * already gates by Cloudinary's authenticated delivery.
     */
    public boolean sendConsultantApplicationSigned(ConsultantApplication application) {
        if (!ermEmail(application).isPresent()) return false;
        String pdf = application.getSignedPdfUrl();
        String dashboard = appUrl + "/agreement-erm/"
                + application.getApplicationId();
        String body = p("Hi,")
                + p("Good news: " + escape(safeName(application))
                        + " just signed the consultant agreement.")
                + receipt(
                        "Application ID: " + application.getApplicationId(),
                        "Consultant: " + safeName(application),
                        "Signed legal name: "
                                + (application.getSignedLegalName() == null
                                        ? "--"
                                        : application.getSignedLegalName()))
                + (pdf == null || pdf.isBlank()
                        ? muted("PDF generation is still in progress -- "
                                + "the file will appear on the dashboard shortly.")
                        : button("Download signed PDF", pdf) + ctaFallback(pdf))
                + secondaryButton("Open application", dashboard)
                + ctaFallback(dashboard);
        return emailService.sendEmail(
                ermEmail(application).get(),
                "Signed agreement received from "
                        + safeName(application),
                wrap("Signed agreement received", body));
    }

    /**
     * Sent to the consultant. Triggered automatically right after
     * signing and again on request (the /done page exposes a button).
     */
    public boolean sendConsultantApplicationCopy(ConsultantApplication application) {
        // Build O — no download link. The signed PDF is held in Sage IT's
        // records and is not sent over email (mirrors the completed-agreement
        // policy); the consultant is simply confirmed.
        String body = p("Hi " + escape(firstName(application)) + ",")
                + p("Thanks for signing your engagement agreement with " + brandName() + ". "
                        + "Your signed agreement is securely held in " + brandName()
                        + "'s records — there's nothing further you need to do.")
                + receipt(
                        "Application ID: " + application.getApplicationId(),
                        "Signed: " + (application.getSignedAt() == null
                                ? "--"
                                : application.getSignedAt().toString()))
                + muted("Keep this email for your records.");
        return emailService.sendEmail(
                application.getConsultantEmail(),
                "Your signed " + brandName() + " engagement agreement",
                wrap("Your signed agreement", body));
    }

    // ── Two-stage workflow templates (Phase 3) ──────────────────────
    //
    // All five share the standard wrap() chrome + button() / ctaFallback()
    // / receipt() / muted() helpers introduced earlier so they inherit
    // the Gmail-safe inline styling without per-template overrides.

    /**
     * Initial "action required" email sent the moment the ERM creates
     * an application. Replaces the legacy sendConsultantApplicationCreated
     * on the new workflow -- the consultant lands on /fill (the new
     * appendix-driven form) instead of /review.
     */
    public boolean sendConsultantInitialFill(ConsultantApplication application) {
        String url = appUrl + "/consultant/" + application.getApplicationId() + "/login";
        // Build O — ERM-authored pre-text becomes the intro; blank → the
        // default copy. Escaped (HTML-safe) with newlines kept as breaks.
        String pretext = application.getEmailPretext();
        String introHtml = (pretext != null && !pretext.isBlank())
                ? p(escape(pretext).replace("\n", "<br/>"))
                : p(brandName() + " has prepared your engagement agreement and "
                        + "needs you to complete a few details so the document "
                        + "can be finalized.");
        String body = p("Hi " + escape(firstName(application)) + ",")
                + introHtml
                + button("Complete Your Details", url)
                + ctaFallback(url)
                + receipt(
                        "Application ID: " + application.getApplicationId(),
                        "Link expires: 7 days from issue")
                + muted("Your responses are saved as you go -- you can "
                        + "close the tab and pick up where you left off.");
        return emailService.sendEmail(
                application.getConsultantEmail(),
                "Action Required: Complete Your " + brandName() + " Agreement",
                wrap("Complete your agreement", body));
    }

    /**
     * Phase D — emails the 6-digit verification code for the consultant
     * OTP gate to the on-record consultant email. Shows the code
     * prominently; states the 10-minute single-use expiry and an
     * ignore-if-not-you line. Sent only when the typed email matches the
     * record (the caller enforces that), so this never reaches a
     * stranger.
     */
    public void sendConsultantOtp(ConsultantApplication application, String code) {
        String codeBlock =
                "<div style=\"margin:20px 0;padding:18px 0;text-align:center;"
                + "font-family:'Courier New',monospace;font-size:34px;"
                + "font-weight:bold;letter-spacing:10px;color:#1B2A5C;"
                + "background:#f4f6fb;border-radius:10px;\">"
                + escape(code) + "</div>";
        String body = p("Hi " + escape(firstName(application)) + ",")
                + p("Use this verification code to access and sign your "
                        + brandName() + " agreement:")
                + codeBlock
                + muted("This code expires in 10 minutes and can be used once. "
                        + "If you didn't request it, you can safely ignore this email.");
        emailService.sendEmail(
                application.getConsultantEmail(),
                "Your " + brandName() + " verification code",
                wrap("Verification code", body));
    }

    /**
     * Build T — sent when the consultant requests a fresh OTP to
     * download their released consultant-version agreement. Same code
     * block treatment as the portal OTP but the copy is download-
     * specific so the consultant can distinguish the two flows.
     */
    public void sendConsultantDownloadOtp(
            ConsultantApplication application, String code) {
        String codeBlock =
                "<div style=\"margin:20px 0;padding:18px 0;text-align:center;"
                + "font-family:'Courier New',monospace;font-size:34px;"
                + "font-weight:bold;letter-spacing:10px;color:#1B2A5C;"
                + "background:#f4f6fb;border-radius:10px;\">"
                + escape(code) + "</div>";
        String body = p("Hi " + escape(firstName(application)) + ",")
                + p("Use this code to download your " + brandName()
                        + " consultant agreement:")
                + codeBlock
                + muted("This code expires in 10 minutes and can be used once. "
                        + "If you didn't request it, you can safely ignore this email.");
        emailService.sendEmail(
                application.getConsultantEmail(),
                "Your " + brandName() + " download verification code",
                wrap("Download verification code", body));
    }

    /**
     * Build O — sent the moment the ERM verifies/releases the consultant
     * version of the agreement. Pure notification: NO download link, no
     * OTP, no portal button (consultant downloads were retired). Just
     * confirms the agreement is verified and that we'll reach out if a
     * revision is needed.
     */
    public boolean sendConsultantVersionReleased(ConsultantApplication application) {
        String body = p("Hi " + escape(firstName(application)) + ",")
                + p("Your " + brandName() + " consultant agreement is verified — "
                        + "you'll be notified if any revision is needed.")
                + receipt("Application ID: " + application.getApplicationId());
        return emailService.sendEmail(
                application.getConsultantEmail(),
                "Your " + brandName() + " consultant agreement is verified",
                wrap("Your agreement is verified", body));
    }

    /**
     * Sent to the consultant when the ERM kicks the application back
     * for revisions after reviewing the signed submission. Includes the
     * ERM's remarks in a styled blockquote so the consultant knows
     * exactly what to fix.
     */
    public boolean sendConsultantRevisionRequest(
            ConsultantApplication application, String remarks) {
        String url = appUrl + "/consultant/" + application.getApplicationId() + "/login";
        String safeRemarks = remarks == null || remarks.isBlank()
                ? "(no remarks provided)"
                : remarks;
        String body = p("Hi " + escape(firstName(application)) + ",")
                + p(brandName() + " has reviewed your submission and asked "
                        + "for a few changes before signing the agreement.")
                + quote(escape(safeRemarks))
                + button("Review and Update", url)
                + ctaFallback(url)
                + receipt(
                        "Application ID: " + application.getApplicationId(),
                        "Your saved fields remain in place")
                + muted("Re-open the link, update the highlighted sections, "
                        + "and submit again.");
        return emailService.sendEmail(
                application.getConsultantEmail(),
                "Revision Requested for Your " + brandName() + " Agreement",
                wrap("Revision requested", body));
    }

    /**
     * Build AQ — sent to the consultant when the ERM takes back a change
     * request that went out by mistake. The consultant already has the
     * "Revision Requested" email in their inbox, so this one has to be
     * unambiguous: nothing is needed from them, and the earlier email should
     * be ignored. No portal link — there is nothing for them to do.
     *
     * <p>It can only say "exactly as you submitted it" because the take-back
     * is refused outright once the consultant has entered anything this round
     * ({@code CONSULTANT_ACTED_REASON}). If that guard is ever loosened, this
     * wording stops being true and has to change with it.
     */
    public boolean sendConsultantRevisionWithdrawn(ConsultantApplication application) {
        String body = p("Hi " + escape(firstName(application)) + ",")
                + p("The change request we sent you for your " + brandName()
                        + " agreement has been withdrawn — it was sent in error. "
                        + "Please ignore that earlier email; nothing is needed "
                        + "from you.")
                + p("Your agreement is back with " + brandName()
                        + " exactly as you submitted it. We'll be in touch if "
                        + "anything genuinely needs changing.")
                + receipt("Application ID: " + application.getApplicationId());
        return emailService.sendEmail(
                application.getConsultantEmail(),
                "Please ignore: change request withdrawn for your "
                        + brandName() + " agreement",
                wrap("Change request withdrawn", body));
    }

    /**
     * Build M — sent to the consultant the moment the ERM advances a
     * Phase-1 COMPLETED agreement to Phase 2 on the same document.
     * No PDF attachment (post-Build-K policy: the consultant never
     * receives the generated PDF over email). Body says the agreement
     * has been reopened for Phase 2, points back to the portal, and
     * notes that everything they previously filled is still in place.
     */
    public boolean sendConsultantPhase2Notification(ConsultantApplication application) {
        String url = appUrl + "/consultant/" + application.getApplicationId() + "/login";
        String body = p("Hi " + escape(firstName(application)) + ",")
                + p(brandName() + " has advanced your engagement agreement "
                        + "to Phase 2 on the same document. Everything you "
                        + "completed for Phase 1 is preserved -- you'll only "
                        + "need to fill the additional sections that Phase 2 "
                        + "requires, then re-sign and submit.")
                + button("Open your portal", url)
                + ctaFallback(url)
                + receipt(
                        "Application ID: " + application.getApplicationId(),
                        "Phase: 2",
                        "Window: 15 days from this email")
                + muted("Re-open the link, complete the remaining sections, "
                        + "and submit again. There is no PDF attached -- "
                        + "Sage IT's records carry the agreement.");
        return emailService.sendEmail(
                application.getConsultantEmail(),
                "Phase 2 -- please complete the remaining sections of your "
                        + brandName() + " agreement",
                wrap("Phase 2 reopened", body));
    }

    /**
     * Sent to the operator the moment the consultant signs and
     * submits. Links to the agreement-erm detail page so the operator
     * can approve-and-sign or send-back-for-revision in one click.
     */
    public boolean sendErmReviewNotification(ConsultantApplication application) {
        String url = appUrl + "/agreement-erm/" + application.getApplicationId();
        String body = p("Hi,")
                + p(escape(safeName(application)) + " has signed and submitted "
                        + "their consultant agreement. It's now waiting for your "
                        + "review and countersignature.")
                + receipt(
                        "Application ID: " + application.getApplicationId(),
                        "Consultant: " + safeName(application),
                        "Email: " + application.getConsultantEmail())
                + button("Review Now", url)
                + ctaFallback(url)
                + muted("Approve to lock and email the signed PDF, or send "
                        + "the application back with remarks.");
        // Phase B — route to the OWNING ERM, not the global address.
        String recipient = resolveErmNotificationRecipient(application);
        log.info("ERM review notification for {} routed to {}",
                application.getApplicationId(), recipient);
        return emailService.sendEmail(
                recipient,
                "Consultant Agreement Ready for Review: " + safeName(application),
                wrap("Ready for your review", body));
    }

    /**
     * Phase B — resolve the recipient for the consultant-submit "review
     * needed" notification: the owning ERM's email. Falls back to the
     * super-admin (so a notification is never silently dropped if the
     * owner can't be resolved), and finally to the legacy global address.
     */
    private String resolveErmNotificationRecipient(ConsultantApplication application) {
        String ownerId = application.getOwnerErmId();
        if (ownerId != null) {
            var owner = agreementUserRepository.findById(ownerId);
            if (owner.isPresent()) {
                return owner.get().getEmail();
            }
        }
        var admins = agreementUserRepository.findByRole(
                com.spire.backend.entity.AgreementUserRole.SUPER_ADMIN);
        if (!admins.isEmpty()) {
            return admins.get(0).getEmail();
        }
        return agreementErmEmail;
    }

    /**
     * Sent once the ERM countersigns. Delivers the final PDF as an
     * attachment to BOTH the consultant and the operator inbox. Two
     * separate sends -- BCC would expose one recipient to the other.
     *
     * Bytes-direct overload: callers that just rendered + uploaded the
     * PDF pass the in-memory bytes through so the email path never
     * re-fetches from Cloudinary. The stored {@code finalPdfUrl} is a
     * per-upload signed URL that 401s on later GET in production,
     * which shipped the early "Your Signed Agreement" emails with
     * attachments=0. {@link ConsultantApplicationService#ermApproveAndSign}
     * always takes this path.
     */
    public boolean sendCompletedAgreementToParties(
            ConsultantApplication application, byte[] pdfBytes) {
        List<EmailService.Attachment> attachments =
                buildPdfAttachmentFromBytes(pdfBytes, application);
        return sendCompletedAgreementBody(application, attachments);
    }

    /**
     * Fallback for callers without freshly-rendered bytes (operator
     * "resend completion email", legacy backfill jobs). Fetches from
     * Cloudinary via a freshly-signed URL -- avoid where possible
     * since signed URL fetches have been observed to 401 in prod.
     */
    public boolean sendCompletedAgreementToParties(ConsultantApplication application) {
        String pdfUrl = resolveFinalPdfFetchUrl(application);
        List<EmailService.Attachment> attachments = buildPdfAttachment(
                pdfUrl, application);
        return sendCompletedAgreementBody(application, attachments);
    }

    private boolean sendCompletedAgreementBody(
            ConsultantApplication application,
            List<EmailService.Attachment> attachments) {
        // Build K — separate sends. The ERM gets the PDF attachment as
        // before. The consultant gets a plain, no-attachment "your
        // agreement has been accepted" note so they know the loop is
        // closed but never receive a downloadable copy. This mirrors
        // the dashboard rule: post-submit the consultant sees status
        // only, no PDF anywhere.
        String ermBody = p("Hi,")
                + p("The " + brandName() + " engagement agreement for "
                        + escape(safeName(application))
                        + " has been countersigned and is now complete. "
                        + "A copy of the signed PDF is attached for your records.")
                + receipt(
                        "Application ID: " + application.getApplicationId(),
                        "Consultant: " + safeName(application),
                        "Signed: " + (application.getSignatureDate() == null
                                ? "--"
                                : application.getSignatureDate().toString()))
                + muted("Internal copy. The consultant has been notified separately "
                        + "without an attachment per policy.");
        String ermSubject = "Signed " + brandName() + " Agreement -- "
                + safeName(application);
        boolean ermSent = emailService.sendEmail(
                agreementErmEmail, ermSubject,
                wrap("Signed agreement (internal)", ermBody),
                attachments);

        String url = appUrl + "/consultant/" + application.getApplicationId() + "/login";
        String consultantBody = p("Hi " + escape(firstName(application)) + ",")
                + p("Good news -- your " + brandName() + " engagement agreement "
                        + "has been accepted. Nothing else is needed from you.")
                + p("You can sign back into your portal any time to see your "
                        + "current status.")
                + button("Open your portal", url)
                + ctaFallback(url)
                + muted("For your security, the signed PDF is held in Sage IT's "
                        + "records and is not sent over email.");
        boolean consultantSent = emailService.sendEmail(
                application.getConsultantEmail(),
                "Your " + brandName() + " Agreement has been accepted",
                wrap("Agreement accepted", consultantBody));
        return ermSent && consultantSent;
    }

    /**
     * Sends the signed agreement PDF to an arbitrary recipient with
     * an optional operator note. Used for forwarding to legal,
     * payroll, the end client, etc.
     */
    public boolean sendAgreementToCustomRecipient(
            ConsultantApplication application,
            String recipientEmail,
            String note) {
        String pdfUrl = resolveFinalPdfFetchUrl(application);
        List<EmailService.Attachment> attachments = buildPdfAttachment(
                pdfUrl, application);
        String safeNote = note == null || note.isBlank()
                ? "Please find the agreement attached."
                : note;
        String body = p("Hi,")
                + p(escape(safeNote))
                + receipt(
                        "Consultant: " + safeName(application),
                        "Application ID: " + application.getApplicationId())
                + muted("Forwarded from the " + brandName()
                        + " agreement console.");
        return emailService.sendEmail(
                recipientEmail,
                brandName() + " Agreement: " + safeName(application),
                wrap("Signed agreement", body),
                attachments);
    }

    /**
     * Downloads the Cloudinary-hosted PDF into a single-attachment
     * list ready for {@link EmailService#sendEmail(String, String, String, List)}.
     * Returns an empty list on any failure (missing URL, network error)
     * so the email still goes through -- without an attachment is
     * better than not at all when an operator is forwarding.
     */
    /**
     * Picks the right URL to GET when fetching the final PDF for an
     * email attachment. Always mints a freshly-signed URL via the SDK
     * -- the public_id is derived deterministically from {@code appId}
     * so rows persisted before {@code final_pdf_public_id} was a
     * column are still served correctly. The stored
     * {@code finalPdfUrl} is intentionally NOT used: its embedded
     * signature is per-upload and 401s on later GET.
     */
    private String resolveFinalPdfFetchUrl(ConsultantApplication application) {
        // Phase 1 — dual-read: S3 (new records, s3_key) → presigned GET URL;
        // else the Cloudinary signed URL (old records). Bytes are then GET'd
        // server-side for the email attachment, same as before.
        return agreementDocumentService.finalPdfSourceUrl(
                application, java.time.Duration.ofMinutes(5));
    }

    /**
     * Wraps freshly-rendered PDF bytes (e.g. from
     * {@link AgreementDocumentService#generateAgreementPdf}) into the
     * email attachment shape. No Cloudinary fetch -- use whenever the
     * caller already holds the bytes.
     */
    private List<EmailService.Attachment> buildPdfAttachmentFromBytes(
            byte[] bytes, ConsultantApplication application) {
        if (bytes == null || bytes.length == 0) {
            log.warn("No PDF bytes available for {}; sending without attachment",
                    application.getApplicationId());
            return java.util.List.of();
        }
        String filename = AgreementDocumentService.buildPdfFilename(application);
        return java.util.List.of(
                new EmailService.Attachment(filename, "application/pdf", bytes));
    }

    private List<EmailService.Attachment> buildPdfAttachment(
            String pdfUrl, ConsultantApplication application) {
        if (pdfUrl == null || pdfUrl.isBlank()) {
            return java.util.List.of();
        }
        try {
            // Explicit timeouts. URL.openStream() defaults to no read
            // timeout, so a slow Cloudinary egress can hang the whole
            // email path indefinitely. 30s on each side is well above
            // the observed P99 for a ~1-2 MB PDF download.
            java.net.URLConnection conn = new java.net.URL(pdfUrl).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(30_000);
            byte[] bytes;
            try (java.io.InputStream in = conn.getInputStream()) {
                bytes = in.readAllBytes();
            }
            String filename = AgreementDocumentService.buildPdfFilename(application);
            return java.util.List.of(
                    new EmailService.Attachment(filename, "application/pdf", bytes));
        } catch (Exception e) {
            log.warn("Couldn't fetch PDF for attachment from {}: {}",
                    pdfUrl, e.getMessage());
            return java.util.List.of();
        }
    }

    private static String safeName(ConsultantApplication application) {
        String name = application.getConsultantName();
        if (name == null || name.isBlank()) {
            String email = application.getConsultantEmail();
            return email == null ? "the consultant" : email;
        }
        return name;
    }

    private static String firstName(ConsultantApplication application) {
        return PersonNames.firstWordForEmail(application.getConsultantName());
    }

    private java.util.Optional<String> ermEmail(ConsultantApplication application) {
        return userRepository.findById(application.getErmUserId())
                .map(User::getEmail);
    }

    // ── 4. Payment receipt ──────────────────────────────────────────
    public void sendPaymentReceiptEmail(
            User user, Course course, BigDecimal amount, String paymentId
    ) {
        String date = usDateTime(java.time.LocalDateTime.now());
        String courseUrl = appUrl + "/courses/" + course.getId();
        String body = p("Hi " + firstName(user) + ",")
                + p("Your payment has been processed successfully.")
                + receipt(
                        "Course: " + course.getTitle(),
                        "Amount: " + Money.usd(amount),
                        "Date: " + date,
                        "Payment ID: " + (paymentId == null ? "—" : paymentId)
                )
                + button("Go to Course", courseUrl)
                + muted("This email is your receipt. Save it for your records.");
        emailService.sendEmail(
                user.getEmail(),
                "Payment confirmed — " + Money.usd(amount == null ? java.math.BigDecimal.ZERO : amount),
                wrap("Payment confirmed", body)
        );
    }

    // ── 5. Enrollment confirmation ──────────────────────────────────
    public void sendEnrollmentEmail(
            User user, Course course, int lessonCount, int moduleCount, String mentorName
    ) {
        String courseUrl = appUrl + "/courses/" + course.getId();
        String mentorLine = mentorName == null || mentorName.isBlank()
                ? "Your mentor will be assigned shortly"
                : "Your mentor: " + escape(mentorName);
        String body = p("Hi " + firstName(user) + ",")
                + p("You've been enrolled in <strong>" + escape(course.getTitle()) + "</strong>.")
                + p("Here's what's waiting for you:")
                + bullet(lessonCount + " lessons across " + moduleCount + " modules")
                + bullet(mentorLine)
                + bullet("Quizzes and assessments")
                + bullet("Certificate on completion")
                + button("Start Learning", courseUrl);
        emailService.sendEmail(
                user.getEmail(),
                "You're enrolled in " + course.getTitle() + "!",
                wrap("You're in!", body)
        );
    }

    // ── 6. Certificate delivery ─────────────────────────────────────
    public void sendCertificateEmail(User user, Course course, Certificate cert) {
        String pdfUrl = cert.getCertificateUrl() == null ? appUrl
                : (cert.getCertificateUrl().startsWith("http")
                        ? cert.getCertificateUrl()
                        : appUrl.replaceAll("/$", "")
                                + "/api/certificates/" + cert.getCertificateId() + "/download");
        String verifyUrl = appUrl + "/verify/" + cert.getCertificateId();
        String linkedIn = "https://www.linkedin.com/sharing/share-offsite/?url="
                + java.net.URLEncoder.encode(verifyUrl, java.nio.charset.StandardCharsets.UTF_8);
        String body = p("You've completed <strong>" + escape(course.getTitle())
                        + "</strong> on " + brandName() + "!")
                + p("Your certificate is ready.")
                + receipt("Certificate ID: " + cert.getCertificateId())
                + button("Download Certificate", pdfUrl)
                + secondaryButton("Verify Certificate", verifyUrl)
                + p("Share your achievement: "
                        + "<a href=\"" + linkedIn + "\" style=\"color:" + brandConfig.getPrimaryColor() + "; text-decoration:none; font-weight:bold;\">Share on LinkedIn →</a>")
                + muted("Keep learning — browse more courses at " + brandName() + ".");
        emailService.sendEmail(
                user.getEmail(),
                "Certificate earned — " + course.getTitle(),
                wrap("Congratulations, " + firstName(user) + "!", body)
        );
    }

    // ── 7. Mentor assigned ──────────────────────────────────────────
    public void sendMentorAssignedEmail(User user, User mentor, Course course) {
        String courseUrl = appUrl + "/courses/" + course.getId();
        String body = p("Hi " + firstName(user) + ",")
                + p("Great news — a mentor has been assigned to help you through <strong>"
                        + escape(course.getTitle()) + "</strong>.")
                + receipt(
                        "Mentor: " + mentor.getFullName(),
                        "Email: " + mentor.getEmail()
                )
                + p("You can request 1:1 sessions with your mentor anytime from your course page.")
                + button("Go to Course", courseUrl);
        emailService.sendEmail(
                user.getEmail(),
                "Meet your mentor for " + course.getTitle(),
                wrap("You have a mentor!", body)
        );
    }

    // ── 8. Session scheduled ────────────────────────────────────────
    public void sendSessionScheduledEmail(User student, SessionRequest session) {
        if (session.getScheduledAt() == null) return;
        // Checklist 5.3: in business time (US Central), with its zone.
        java.time.ZonedDateTime at = BusinessTime.of(session.getScheduledAt(), businessZoneId);
        String date = at.format(US_DATE);
        String time = at.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a z", Locale.US));
        // SessionRequest -> MentorAssignment -> {Enrollment, mentor User}
        // Pull through both legs; mentor can be null while a pool slot
        // is still pending, but at the point we're emailing a scheduled
        // session it will always be populated.
        var assignment = session.getMentorAssignment();
        String mentorName = assignment.getMentor() != null
                ? assignment.getMentor().getFullName() : "Your mentor";
        String courseTitle = assignment.getEnrollment().getCourse().getTitle();
        String topic = session.getTopic() == null ? "—" : session.getTopic();
        String meetingUrl = session.getMeetingUrl() == null ? appUrl + "/dashboard" : session.getMeetingUrl();
        String body = p("Hi " + firstName(student) + ",")
                + p("Your session has been scheduled:")
                + receipt(
                        "Date: " + date,
                        "Time: " + time,
                        "Mentor: " + mentorName,
                        "Course: " + courseTitle,
                        "Topic: " + topic
                )
                + button("Join Meeting", meetingUrl)
                + muted("Add this to your calendar. Your mentor will be waiting.");
        emailService.sendEmail(
                student.getEmail(),
                "Session scheduled — " + date + " at " + time,
                wrap("Session confirmed", body)
        );
    }

    // ── 9. Inactive nudge (7-day) ───────────────────────────────────
    public boolean sendInactiveNudgeEmail(
            User user, String courseTitle, int progressPercent, String mentorName, String lessonUrl
    ) {
        String mentorLine = mentorName == null || mentorName.isBlank()
                ? "Your mentor is still here to help."
                : "Your mentor " + escape(mentorName) + " is still here to help.";
        String body = p("Hi " + firstName(user) + ",")
                + p("It's been a while since you visited " + brandName() + ".")
                + p("You were making great progress on <strong>" + escape(courseTitle)
                        + "</strong> — " + progressPercent + "% done!")
                + p(mentorLine)
                + button("Continue Learning", lessonUrl)
                + muted("Small steps count. Even 15 minutes today can make a difference.");
        return emailService.sendEmail(
                user.getEmail(),
                "We miss you, " + firstName(user) + "!",
                wrap("Pick up where you left off", body)
        );
    }

    // ── 10. Sales reply notification ────────────────────────────────
    public void sendSalesReplyEmail(
            User student, String instructorName, String courseTitle,
            String messagePreview, long inquiryId
    ) {
        String url = appUrl + "/messages/" + inquiryId;
        String preview = messagePreview == null ? ""
                : (messagePreview.length() > 200
                        ? messagePreview.substring(0, 200) + "…"
                        : messagePreview);
        String body = p("Hi " + firstName(student) + ",")
                + p("<strong>" + escape(instructorName) + "</strong> replied to your inquiry about <strong>"
                        + escape(courseTitle) + "</strong>:")
                + quote(escape(preview))
                + button("View Conversation", url);
        emailService.sendEmail(
                student.getEmail(),
                "You have a reply about " + courseTitle,
                wrap("New message from " + instructorName, body)
        );
    }

    // ── 13. Employment / Phase 1 completion (Phase 6 Step 17) ────────
    /**
     * Email #13 — fires when a participant accepts the Phase 1
     * completion acknowledgment. Three recipients with tailored bodies:
     *   - Participant: congratulations + payment-plan heads-up.
     *   - ERM: confirmation + reminder to action approvals.
     *   - Finance: Phase 1 complete → payment-plan scheduling can begin.
     *
     * Each send is wrapped in try / ignored — a single failed recipient
     * never stops the others.
     */
    public void sendPhase1CompletionEmails(
            com.spire.backend.entity.User user,
            com.spire.backend.entity.User erm,
            com.spire.backend.entity.EmploymentAcceptance emp,
            java.time.LocalDateTime acceptedAt) {

        String fullName = safe(user.getFullName());
        String participantId = safe(user.getParticipantId());
        String employer = emp == null ? "—" : safe(emp.getEmployerClient());
        String jobTitle = emp == null ? "—" : safe(emp.getJobTitle());
        String startDate = emp == null || emp.getStartDate() == null
                ? "—" : emp.getStartDate().toString();
        String completionDate = acceptedAt == null
                ? "" : usDateTime(acceptedAt);

        // ── Participant ─────────────────────────────────────────
        try {
            String body = p("Dear " + escape(fullName) + ",")
                    + p("Congratulations! Your Phase 1 pre-employment readiness "
                            + "program is now complete.")
                    + receipt(
                            "Employment: " + employer + " — " + jobTitle,
                            "Start date: " + startDate,
                            "Phase 1 completed: " + completionDate)
                    + p("Your payment plan will be activated shortly. You can view "
                            + "your payment schedule in your dashboard once it's live.")
                    + p("Phase 2 post-offer support is now available as per your agreement.")
                    + button("Open your dashboard", appUrl + "/dashboard")
                    + p("Regards,<br/>" + brandName() + "");
            emailService.sendEmail(user.getEmail(),
                    "Phase 1 completed — " + fullName + " — " + brandName() + "",
                    wrap("Phase 1 complete — congratulations!", body));
        } catch (Exception ignored) {}

        // ── ERM ─────────────────────────────────────────────────
        if (erm != null && erm.getEmail() != null && !erm.getEmail().isBlank()) {
            try {
                String body = p("Phase 1 completed for " + escape(fullName)
                                + " (" + escape(participantId) + ").")
                        + receipt(
                                "Employer: " + employer,
                                "Job title: " + jobTitle,
                                "Start date: " + startDate,
                                "Completion: " + completionDate)
                        + p("Payment plan activation is pending. Approve the "
                                + "Phase 1 acknowledgment from your ERM dashboard "
                                + "if you haven't already.")
                        + p("— " + brandName() + "");
                emailService.sendEmail(erm.getEmail(),
                        "Phase 1 completed: " + fullName + " (" + participantId + ")",
                        wrap("Phase 1 complete — ERM heads-up", body));
            } catch (Exception ignored) {}
        }

        // ── Finance ─────────────────────────────────────────────
        if (financeEmail != null && !financeEmail.isBlank()) {
            try {
                String body = p("Phase 1 completed for " + escape(fullName)
                                + " (" + escape(participantId) + ").")
                        + receipt(
                                "Employer: " + employer,
                                "Job title: " + jobTitle,
                                "Start date: " + startDate,
                                "Completion: " + completionDate)
                        + p("Payment plan scheduling can begin. The participant's "
                                + "signed agreement and Phase 1 record are on file.")
                        + p("— " + brandName() + "");
                emailService.sendEmail(financeEmail,
                        "Phase 1 complete — payment plan ready: "
                                + fullName + " (" + participantId + ")",
                        wrap("Phase 1 complete — finance heads-up", body));
            } catch (Exception ignored) {}
        }
    }

    // ── 14. Payment plan + invoice notices (Phase 7) ────────────────
    // Checklist 5.3: amounts in US dollars, dates and times in US Central.

    /** One schedule line per instalment: "Installment 1 of 3 — Oct 1, 2026 — $1,000.00". */
    private static String scheduleLines(java.util.List<PaymentService.ScheduleItem> schedule) {
        StringBuilder rows = new StringBuilder();
        int idx = 1;
        for (PaymentService.ScheduleItem item : schedule) {
            rows.append("Installment ").append(idx++).append(": ")
                .append(usDate(item.dueDate())).append(" — ").append(Money.usd(item.amount())).append("\n");
        }
        return "<pre style=\"background:#f9fafb;border:1px solid #e5e7eb;border-radius:6px;padding:10px;font-size:12px;line-height:1.7;\">"
                + escape(rows.toString()) + "</pre>";
    }

    /**
     * Checklist 5.1: Finance created (or changed) the participant's payment
     * plan; they review and accept it on their dashboard.
     */
    public boolean sendPaymentPlanReadyEmail(
            com.spire.backend.entity.User user,
            com.spire.backend.entity.PaymentPlan plan,
            java.util.List<PaymentService.ScheduleItem> schedule,
            boolean changed) {
        String body = p("Dear " + escape(firstName(user)) + ",")
                + p(changed
                        ? "Your payment plan has been updated. Please review the new schedule and accept it."
                        : "Your payment plan is ready. Please review the schedule and accept it.")
                + receipt(
                        "Plan ID: " + safe(plan.getPlanId()),
                        "Total amount: " + Money.usd(plan.getTotalAmount()),
                        "Installments: " + plan.getInstallments())
                + p("<strong>Schedule</strong>")
                + scheduleLines(schedule)
                + button("Review and accept your plan", appUrl + "/dashboard?tab=payments")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(user.getEmail(),
                (changed ? "Your payment plan was updated" : "Your payment plan is ready") + " — " + brandName(),
                wrap(changed ? "Payment plan updated" : "Payment plan ready", body));
    }

    /**
     * Email #14a — payment plan accepted confirmation.
     * Sent to the participant; finance CC'd via {@code financeEmail}.
     */
    public void sendPaymentPlanAcceptedEmail(
            com.spire.backend.entity.User user,
            com.spire.backend.entity.PaymentPlan plan,
            java.util.List<PaymentService.ScheduleItem> schedule) {
        String firstName = firstName(user);
        String body = p("Dear " + escape(firstName) + ",")
                + p("Your payment plan has been confirmed. Here's a summary "
                        + "of what to expect.")
                + receipt(
                        "Plan ID: " + safe(plan.getPlanId()),
                        "Total amount: " + Money.usd(plan.getTotalAmount()),
                        "Installments: " + plan.getInstallments(),
                        "Accepted: " + usDateTime(plan.getAcceptedAt()))
                + p("<strong>Schedule</strong>")
                + scheduleLines(schedule)
                + p("Invoices will be issued per the schedule above. You can view "
                        + "your payment status from your dashboard at any time.")
                + button("Open your dashboard", appUrl + "/dashboard?tab=payments")
                + p("Regards,<br/>" + brandName() + "");
        try {
            emailService.sendEmail(user.getEmail(),
                    "Payment plan confirmed — " + brandName() + "",
                    wrap("Payment plan confirmed", body));
        } catch (Exception ignored) {}
        if (financeEmail != null && !financeEmail.isBlank()) {
            try {
                emailService.sendEmail(financeEmail,
                        "Payment plan accepted: " + safe(user.getFullName())
                                + " (" + safe(user.getParticipantId()) + ")",
                        wrap("Plan accepted — finance copy", body));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Email #14b — invoice issued. Sent to the participant; CC's
     * finance if configured.
     */
    public void sendInvoiceIssuedEmail(
            com.spire.backend.entity.User user,
            com.spire.backend.entity.Invoice invoice) {
        String firstName = firstName(user);
        String amount = Money.usd(invoice.getAmount());
        String dueDate = usDate(invoice.getDueDate());
        String body = p("Dear " + escape(firstName) + ",")
                + p("A new invoice has been issued on your account.")
                + receipt(
                        "Invoice: " + safe(invoice.getInvoiceNumber()),
                        "Amount: " + amount,
                        "Issued: " + usDate(invoice.getIssueDate()),
                        "Due: " + dueDate)
                + p("You can view and download the invoice (PDF) from your dashboard.")
                + button("Open your invoices", appUrl + "/dashboard?tab=payments")
                + p("If you've already made this payment, no action is required — "
                        + "your record will update once finance confirms receipt.")
                + p("Regards,<br/>" + brandName() + "");
        try {
            emailService.sendEmail(user.getEmail(),
                    "Invoice " + safe(invoice.getInvoiceNumber())
                            + " — " + amount + " due " + dueDate,
                    wrap("Invoice issued", body));
        } catch (Exception ignored) {}
    }

    /** Email #14c — payment received confirmation. */
    public void sendPaymentReceivedEmail(
            com.spire.backend.entity.User user,
            com.spire.backend.entity.Invoice invoice,
            com.spire.backend.entity.PaymentLedger ledger) {
        String firstName = firstName(user);
        String body = p("Dear " + escape(firstName) + ",")
                + p("We've received your payment. Thank you.")
                + receipt(
                        "Invoice: " + safe(invoice.getInvoiceNumber()),
                        "Amount received: " + Money.usd(ledger.getAmountReceived()),
                        "Method: " + methodLabel(ledger.getMethod()),
                        "Receipt date: " + usDate(ledger.getReceiptDate()),
                        "Remaining balance: " + Money.usd(invoice.getBalance() == null
                                ? java.math.BigDecimal.ZERO : invoice.getBalance()))
                + p("Your dashboard reflects the updated status.")
                + button("Open your dashboard", appUrl + "/dashboard?tab=payments")
                + p("Regards,<br/>" + brandName() + "");
        try {
            emailService.sendEmail(user.getEmail(),
                    "Payment received — Invoice " + safe(invoice.getInvoiceNumber()),
                    wrap("Payment received", body));
        } catch (Exception ignored) {}
    }

    /**
     * Checklist 5.2: a payment didn't go through, or one we'd recorded was
     * reversed (e.g. a bounced check). The invoice balance is shown.
     */
    public boolean sendPaymentProblemEmail(
            com.spire.backend.entity.User user,
            com.spire.backend.entity.Invoice invoice,
            java.math.BigDecimal amount,
            String reason,
            boolean reversed) {
        String body = p("Dear " + escape(firstName(user)) + ",")
                + p(reversed
                        ? "A payment we had recorded on your invoice was reversed, so it's back on your balance."
                        : "A payment on your invoice didn't go through.")
                + receipt(
                        "Invoice: " + safe(invoice.getInvoiceNumber()),
                        "Amount: " + Money.usd(amount),
                        "Balance now: " + Money.usd(invoice.getBalance()),
                        "Due: " + usDate(invoice.getDueDate()))
                + (reason == null || reason.isBlank() ? "" : p("<strong>Reason:</strong>") + quote(escape(reason)))
                + p("Please arrange the payment again, or reply to this email if you think this is a mistake.")
                + button("Open your invoices", appUrl + "/dashboard?tab=payments")
                + p("Regards,<br/>" + brandName() + "");
        return emailService.sendEmail(user.getEmail(),
                (reversed ? "A payment was reversed" : "A payment didn't go through")
                        + " — Invoice " + safe(invoice.getInvoiceNumber()),
                wrap(reversed ? "Payment reversed" : "Payment not completed", body));
    }

    /** Email #14d — overdue payment reminder. */
    public void sendInvoiceOverdueEmail(
            com.spire.backend.entity.User user,
            com.spire.backend.entity.Invoice invoice) {
        String firstName = firstName(user);
        java.math.BigDecimal balance = invoice.getBalance() == null ? invoice.getAmount() : invoice.getBalance();
        boolean partPaid = invoice.getAmount() != null && balance != null && balance.compareTo(invoice.getAmount()) < 0;
        String body = p("Dear " + escape(firstName) + ",")
                + p("This is a reminder that the following invoice is past due.")
                + receipt(
                        "Invoice: " + safe(invoice.getInvoiceNumber()),
                        "Amount: " + Money.usd(invoice.getAmount()),
                        (partPaid ? "Still to pay: " : "Balance: ") + Money.usd(balance),
                        "Original due date: " + usDate(invoice.getDueDate()))
                + p("Please reach out to finance if there's anything we should know "
                        + "about your payment. If you've already paid, your record "
                        + "will update once we confirm receipt.")
                + button("Open your invoices", appUrl + "/dashboard?tab=payments")
                + p("Regards,<br/>" + brandName() + "");
        try {
            emailService.sendEmail(user.getEmail(),
                    "Payment reminder — Invoice " + safe(invoice.getInvoiceNumber()) + " overdue",
                    wrap("Invoice overdue", body));
        } catch (Exception ignored) {}
    }

    private static final DateTimeFormatter US_DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter US_DATE_TIME =
            DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a z", Locale.US);

    @Value("${app.business-zone:America/Chicago}")
    private String businessZoneId;

    /** Checklist 5.3: "Oct 1, 2026". */
    static String usDate(java.time.LocalDate date) {
        return date == null ? "—" : date.format(US_DATE);
    }

    /**
     * Checklist 5.3: a stored time (the server's clock, UTC in production)
     * shown in US Central time: "Sep 25, 2026, 9:12 AM CDT".
     */
    private String usDateTime(java.time.LocalDateTime time) {
        if (time == null) return "—";
        ZoneId zone = ZoneId.of(businessZoneId == null || businessZoneId.isBlank() ? "America/Chicago" : businessZoneId);
        return time.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).format(US_DATE_TIME);
    }

    private static String methodLabel(String method) {
        if (method == null) return "—";
        return switch (method) {
            case "CHEQUE" -> "Check";
            case "BANK_TRANSFER" -> "Bank transfer";
            case "CARD" -> "Card";
            case "CASH" -> "Cash";
            case "ONLINE" -> "Online payment";
            default -> method;
        };
    }

    // ───────────────────────────────────────────────────────────────
    // Markup helpers
    // ───────────────────────────────────────────────────────────────

    private String wrap(String title, String body) {
        // Brand-driven chrome: header color + name, footer copyright
        // + website link all read from BrandConfig so a re-deploy
        // under a different brand swaps these without code changes.
        String primary = brandConfig.getPrimaryColor();
        String name = brandConfig.getName();
        String website = brandConfig.getWebsite();
        String websiteHost = website
                .replaceFirst("^https?://", "")
                .replaceFirst("/.*$", "");
        int year = java.time.Year.now().getValue();
        return """
        <!DOCTYPE html>
        <html>
        <head><meta charset="UTF-8"></head>
        <body style="margin:0; padding:0; background:#f4f4f5; font-family:Arial,Helvetica,sans-serif;">
          <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f5; padding:40px 0;">
            <tr><td align="center">
              <table width="560" cellpadding="0" cellspacing="0" style="background:#ffffff; border-radius:12px; overflow:hidden; box-shadow:0 2px 8px rgba(0,0,0,0.06);">
                <tr>
                  <td style="background:%s; padding:24px 32px; text-align:center;">
                    <h1 style="margin:0; color:#ffffff; font-size:20px; font-weight:bold;">%s</h1>
                  </td>
                </tr>
                <tr>
                  <td style="padding:32px;">
                    <h2 style="margin:0 0 16px; color:#111827; font-size:22px; font-weight:bold;">%s</h2>
                    %s
                  </td>
                </tr>
                <tr>
                  <td style="padding:20px 32px; background:#f9fafb; border-top:1px solid #e5e7eb; text-align:center;">
                    <p style="margin:0; color:#9ca3af; font-size:12px;">© %d %s</p>
                    <p style="margin:4px 0 0; color:#9ca3af; font-size:12px;">
                      <a href="%s" style="color:%s; text-decoration:none;">%s</a>
                    </p>
                  </td>
                </tr>
              </table>
            </td></tr>
          </table>
        </body>
        </html>
        """.formatted(primary, escape(name),
                escape(title), body,
                year, escape(name),
                website, primary, escape(websiteHost));
    }

    private static String p(String html) {
        return "<p style=\"margin:0 0 12px; color:#374151; font-size:15px; line-height:1.7;\">"
                + html + "</p>";
    }

    private static String muted(String text) {
        return "<p style=\"margin:16px 0 0; color:#9ca3af; font-size:13px; line-height:1.6;\">"
                + escape(text) + "</p>";
    }

    private static String bullet(String text) {
        return "<p style=\"margin:0 0 6px 0; color:#374151; font-size:14px; line-height:1.6;\">"
                + "✓ " + escape(text) + "</p>";
    }

    /**
     * Primary CTA used in every transactional email. Gmail and
     * Outlook both strip <style> blocks and class= attributes, so
     * every rule has to live inline on the <a>. We also keep the
     * brand color on the <a> itself (not just the wrapping <td>) so
     * the button still renders when a client drops the table layout,
     * and use {@code color:#FFFFFF !important} so Gmail's "linkify
     * blue" pass leaves the label readable.
     */
    private String button(String label, String url) {
        String primary = brandConfig.getPrimaryColor();
        return """
        <table cellpadding="0" cellspacing="0" border="0" style="margin:24px 0;">
          <tr><td style="background-color:%s; border-radius:8px;">
            <a href="%s" target="_blank" rel="noopener" style="display:inline-block; padding:12px 28px; background-color:%s; color:#FFFFFF !important; text-decoration:none !important; font-size:14px; font-weight:bold; font-family:Arial,sans-serif; border-radius:8px; mso-padding-alt:0;">%s</a>
          </td></tr>
        </table>
        """.formatted(primary, url, primary, escape(label));
    }

    /**
     * Plaintext fallback URL block, rendered directly under a CTA
     * button so the email is still usable when the styled button
     * misrenders (Gmail dark mode, Outlook desktop rules engine,
     * accessibility tools that strip background colors, etc.).
     * Currently used for consultant-agreement emails -- the only
     * surface where a missed CTA means the consultant can't complete
     * the flow at all.
     */
    private static String ctaFallback(String url) {
        return "<p style=\"font-size:12px; color:#666666; margin:-8px 0 16px 0; font-family:Arial,sans-serif; line-height:1.5;\">"
                + "If the button doesn't work, copy this link into your browser:<br>"
                + "<span style=\"word-break:break-all; color:#1B2A5C;\">"
                + escape(url) + "</span></p>";
    }

    private String secondaryButton(String label, String url) {
        String primary = brandConfig.getPrimaryColor();
        return """
        <table cellpadding="0" cellspacing="0" border="0" style="margin:8px 0 24px;">
          <tr><td style="background-color:#ffffff; border:1px solid %s; border-radius:8px;">
            <a href="%s" target="_blank" rel="noopener" style="display:inline-block; padding:11px 28px; background-color:#ffffff; color:%s !important; text-decoration:none !important; font-size:14px; font-weight:bold; font-family:Arial,sans-serif; border-radius:8px;">%s</a>
          </td></tr>
        </table>
        """.formatted(primary, url, primary, escape(label));
    }

    /** Bordered "receipt" block — used for payment / cert / session details. */
    private static String receipt(String... lines) {
        StringBuilder sb = new StringBuilder(
                "<div style=\"background:#f9fafb; border:1px solid #e5e7eb; border-radius:8px; padding:16px; margin:16px 0;\">");
        for (int i = 0; i < lines.length; i++) {
            sb.append("<p style=\"margin:")
              .append(i == 0 ? "0" : "6px 0 0")
              .append("; color:#374151; font-size:14px; font-family:'Courier New',monospace;\">")
              .append(escape(lines[i]))
              .append("</p>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** Inline blockquote — used for the sales reply preview. */
    private String quote(String text) {
        return "<div style=\"background:#f9fafb; border-left:3px solid " + brandConfig.getPrimaryColor() + "; "
                + "padding:12px 16px; margin:16px 0; border-radius:4px;\">"
                + "<p style=\"color:#374151; font-size:14px; margin:0; line-height:1.6;\">"
                + text + "</p></div>";
    }

    /** Name characters only (no markup or links), so it is safe in HTML and subjects. */
    private static String firstName(User user) {
        return user == null ? "there" : PersonNames.firstWordForEmail(user.getFullName());
    }

    /**
     * Minimal HTML escape — guards against quotes / angle brackets in
     * user-supplied fields (course titles, mentor names, message
     * previews) breaking the email layout. Not a full sanitiser; we
     * never inline raw user HTML.
     */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
