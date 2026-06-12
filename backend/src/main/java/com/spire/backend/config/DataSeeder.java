package com.spire.backend.config;

import com.spire.backend.entity.*;
import com.spire.backend.entity.Module;
import com.spire.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private LessonRepository lessonRepository;

    @Autowired
    private ModuleRepository moduleRepository;

    @Autowired
    private AchievementRepository achievementRepository;

    @Autowired
    private EnrollmentRepository enrollmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgreementUserRepository agreementUserRepository;

    // Super-admin bootstrap for the consultant-agreement console. The
    // single SUPER_ADMIN is provisioned from these env vars, never
    // through the UI. Blank defaults => bootstrap is skipped (logged).
    @Value("${agreement.super-admin.email:}")
    private String superAdminEmail;

    @Value("${agreement.super-admin.password:}")
    private String superAdminPassword;

    @Value("${agreement.super-admin.name:}")
    private String superAdminName;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional
    public void run(String... args) {
        // Seed roles first (idempotent)
        Role studentRole = roleRepository.findByName("STUDENT")
                .orElseGet(() -> roleRepository.save(Role.builder().name("STUDENT").build()));
        Role instructorRole = roleRepository.findByName("INSTRUCTOR")
                .orElseGet(() -> roleRepository.save(Role.builder().name("INSTRUCTOR").build()));
        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ADMIN").build()));
        Role trainerRole = roleRepository.findByName("TRAINER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("TRAINER").build()));
        log.info("Roles ready: STUDENT({}), INSTRUCTOR({}), TRAINER({}), ADMIN({})",
                studentRole.getId(), instructorRole.getId(), trainerRole.getId(), adminRole.getId());

        // Phase 1A roles — added alongside the legacy LMS roles, not
        // replacing them. Legacy STUDENT and ADMIN rows continue to
        // work; the new code paths treat STUDENT as PARTICIPANT and
        // ADMIN as OPERATIONS_ADMIN via PermissionService's role
        // alias set. Idempotent, like the block above.
        String[] phase1aRoles = {
                "PARTICIPANT", "ERM", "COACH", "TECHNICAL_ADVISOR",
                "OPERATIONS_ADMIN", "FINANCE", "SYSTEM_ADMIN",
        };
        for (String name : phase1aRoles) {
            roleRepository.findByName(name).orElseGet(() ->
                    roleRepository.save(Role.builder().name(name).build()));
        }
        log.info("Phase 1A roles ensured: {}", String.join(", ", phase1aRoles));

        // ── Audit cleanup migrations (idempotent) ───────────────────
        // Rename legacy workflow status strings to match the PRD
        // vocabulary, and drop the never-used docusign_envelopes
        // shadow table. All statements are no-ops if the rows / table
        // don't exist, so re-running the seeder is safe.
        try {
            int u1 = jdbcTemplate.update(
                    "UPDATE users SET current_status = 'AGREEMENT_SENT' "
                    + "WHERE current_status = 'DOCUSIGN_SENT'");
            int u2 = jdbcTemplate.update(
                    "UPDATE users SET current_status = 'AGREEMENT_COMPLETED' "
                    + "WHERE current_status = 'DOCUSIGN_COMPLETED'");
            int w1 = jdbcTemplate.update(
                    "UPDATE workflow_states SET from_status = 'AGREEMENT_SENT' "
                    + "WHERE from_status = 'DOCUSIGN_SENT'");
            int w2 = jdbcTemplate.update(
                    "UPDATE workflow_states SET to_status = 'AGREEMENT_SENT' "
                    + "WHERE to_status = 'DOCUSIGN_SENT'");
            int w3 = jdbcTemplate.update(
                    "UPDATE workflow_states SET from_status = 'AGREEMENT_COMPLETED' "
                    + "WHERE from_status = 'DOCUSIGN_COMPLETED'");
            int w4 = jdbcTemplate.update(
                    "UPDATE workflow_states SET to_status = 'AGREEMENT_COMPLETED' "
                    + "WHERE to_status = 'DOCUSIGN_COMPLETED'");
            log.info("Workflow status rename: users updated = {} + {}, "
                    + "workflow_states updated = {} + {} + {} + {}",
                    u1, u2, w1, w2, w3, w4);
        } catch (Exception e) {
            log.warn("Status rename migration skipped: {}", e.getMessage());
        }

        try {
            // Postgres + MySQL both accept this form. The IF EXISTS
            // keeps the call no-op-safe across fresh databases that
            // never had the table.
            jdbcTemplate.execute("DROP TABLE IF EXISTS docusign_envelopes");
            log.info("Dropped legacy docusign_envelopes table (if it existed).");
        } catch (Exception e) {
            log.warn("docusign_envelopes drop skipped: {}", e.getMessage());
        }

        try {
            // agreement_acceptances → agreement_records to match the
            // PRD vocabulary. The entity now maps to agreement_records.
            // We have to reason about FOUR possible DB states:
            //   1. Only old table exists (pre-rename DB) → rename it.
            //   2. Only new table exists (fresh deploy, Hibernate auto-
            //      created from @Table) → nothing to do.
            //   3. Both exist (rolled-back deploy re-created the new
            //      table while the old was still around) → drop the
            //      empty legacy table; if it has rows, leave it and
            //      log for manual reconciliation rather than fail the
            //      whole startup.
            //   4. Neither exists → nothing to do.
            //
            // Previous code only checked case 1. Hitting case 3 in
            // production raised "relation agreement_records already
            // exists", which Postgres treats as a fatal error inside
            // the seeder's @Transactional, aborting the whole
            // transaction and failing every later query (roles, etc.).
            Integer oldExists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_name = 'agreement_acceptances'",
                    Integer.class);
            Integer newExists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_name = 'agreement_records'",
                    Integer.class);
            boolean haveOld = oldExists != null && oldExists > 0;
            boolean haveNew = newExists != null && newExists > 0;
            if (haveOld && !haveNew) {
                jdbcTemplate.execute(
                        "ALTER TABLE agreement_acceptances RENAME TO agreement_records");
                log.info("Renamed agreement_acceptances → agreement_records.");
            } else if (haveOld && haveNew) {
                Integer rows = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM agreement_acceptances", Integer.class);
                if (rows == null || rows == 0) {
                    jdbcTemplate.execute("DROP TABLE IF EXISTS agreement_acceptances");
                    log.info("Dropped empty legacy agreement_acceptances "
                            + "(agreement_records is canonical).");
                } else {
                    log.warn("Both agreement_acceptances ({} rows) and "
                            + "agreement_records exist. Skipping rename; "
                            + "manual reconciliation needed.", rows);
                }
            }
        } catch (Exception e) {
            log.warn("agreement_acceptances rename/cleanup skipped: {}", e.getMessage());
        }

        // Phase 4 — seed a starter ERM + coach team so dev / first
        // production deploys have a non-empty assignment pool. The
        // OnboardingService chain picks from these candidates when
        // a participant finishes their agreement; without at least
        // one ERM the participant stays at SIGNED_AGREEMENT_SENT_TO_ERM
        // forever. Idempotent — only creates each user when the
        // email isn't already taken.
        seedPhase4Team();

        // Drop the legacy unique constraint on quiz_attempts(quiz_id,
        // user_id) before any other migration runs — without this,
        // the new multi-attempt quiz flow would fail the second time
        // a student retries a quiz. ddl-auto=update never drops
        // constraints, so we have to do it ourselves.
        dropLegacyQuizAttemptUniqueConstraint();

        // Drop the legacy NOT NULL on questions(option_a/b/c/d,
        // correct_answer). The old fixed-4-option quiz schema marked
        // these columns NOT NULL; the new normalized model leaves
        // them nullable but ddl-auto=update never relaxes a NOT NULL
        // — so on already-seeded DBs every new question save throws
        // a NOT NULL violation that surfaces as a generic 409.
        relaxLegacyQuestionColumns();

        // Add the email-verification + password-reset + nudge columns
        // before Hibernate's ddl-auto=update kicks in. Hibernate would
        // otherwise refuse to add `email_verified BOOLEAN NOT NULL` to
        // an already-populated users table because existing rows have
        // no value to satisfy the NOT NULL constraint — the failed
        // ALTER cascades and leaves the rest of the migration batch in
        // a partial state. Doing it here with an explicit DEFAULT FALSE
        // gives every existing row the right default, so Hibernate's
        // subsequent run is a clean no-op.
        addUserEmailColumnsIfMissing();

        // Two-stage consultant-agreement fill workflow: ~48 nullable
        // columns added to consultant_applications for the ERM rate
        // card, consultant personal block, Exhibit A scope, Appendix
        // 1 (employment) plus optional Appendices 2-5 (ACH,
        // background check, portal access, security check), ERM
        // countersignature, and revision tracking. Runs before
        // Hibernate's ddl-auto=update so existing rows have the new
        // columns when JPA boots; idempotent on reruns.
        addConsultantApplicationColumnsIfMissing();

        // Portal phase: consultant_verification was keyed by
        // application_id; the portal keys it by email instead. Add the
        // email column + relax the legacy NOT NULL on application_id so
        // portal-phase rows can leave it null. Idempotent on reruns.
        addConsultantVerificationPortalColumnsIfMissing();

        // Consultant-agreement console multi-user bootstrap. The
        // agreement_user table is auto-created by ddl-auto=update before
        // this CommandLineRunner runs, so the repository is safe to use
        // here. ensureSuperAdmin() is idempotent; the owner backfill
        // assigns pre-existing applications to the super-admin so they
        // stay visible (and only touches NULLs, so reruns are no-ops).
        AgreementUser superAdmin = ensureSuperAdmin();
        backfillConsultantApplicationOwner(superAdmin);

        // Catalog repair -- if a previous deploy ran the full seed block
        // before /enroll signups existed (so users count was already > 0
        // when DataSeeder ran), the early-return below skipped courses
        // and the participant dashboard's Browse view ended up empty.
        // This runs independently of user count so the catalog can be
        // backfilled on any already-deployed DB. Idempotent --
        // short-circuits if courses already exist.
        seedSampleCoursesIfMissing();

        if (userRepository.count() > 0) {
            log.info("Database already seeded. Skipping initial users/courses block.");
            // Backfill on already-seeded dev DBs that predate the services
            // feature. Idempotent — does nothing if the trainer + 4 services
            // already exist.
            seedServicesAndTrainer(trainerRole);
            // Bring legacy course/service prices up to the new realistic
            // values. Only touches courses that still hold the OLD seed
            // price so any admin-edited price is preserved.
            backfillCoursePrices();
            // Wipe the fabricated rating + enrolled-count seeds from
            // already-deployed DBs. We never had a rating system, so the
            // 4.7/4.8/4.9 stars and 1k+ ratingsCount values were always
            // theatre. Real enrollment counts get rebuilt from the
            // enrollments table by EnrollmentService on next save.
            backfillFakeStats();
            return;
        }

        log.info("Seeding database...");

        // --- Users ---
        log.info("Seeding users...");

        User admin = userRepository.save(User.builder()
                .email("admin@sageitco.com")
                .passwordHash(passwordEncoder.encode("admin123"))
                .fullName("Sage Admin")
                .role(adminRole)
                .build());

        User student = userRepository.save(User.builder()
                .email("student@sageitco.com")
                .passwordHash(passwordEncoder.encode("student123"))
                .fullName("Abhishek Student")
                .role(studentRole)
                .bio("A passionate learner exploring new skills on Sage.")
                .build());

        User arjun = userRepository.save(User.builder()
                .email("arjun@sageitco.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .fullName("Arjun Mehta")
                .role(instructorRole)
                .bio("Senior full-stack engineer with 10+ years building scalable web applications. Ex-Flipkart, ex-Razorpay.")
                .build());

        User priya = userRepository.save(User.builder()
                .email("priya@sageitco.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .fullName("Priya Sharma")
                .role(instructorRole)
                .bio("Data scientist and ML engineer. Passionate about making complex topics accessible.")
                .build());

        User rahul = userRepository.save(User.builder()
                .email("rahul@sageitco.com")
                .passwordHash(passwordEncoder.encode("password123"))
                .fullName("Rahul Kapoor")
                .role(instructorRole)
                .bio("Mobile and cloud architect. Google Developer Expert.")
                .build());

        log.info("Seeded 5 users.");

        // --- Courses ---
        log.info("Seeding courses...");

        Course fullStack = courseRepository.save(Course.builder()
                .title("Full-Stack Web Development")
                .slug("full-stack-web-development")
                .description("Master modern web development from front to back. Learn HTML, CSS, JavaScript, React, Node.js, databases, and deployment in one comprehensive course.")
                .shortDescription("Build complete web applications from scratch")
                .level(Course.Level.INTERMEDIATE)
                .price(new BigDecimal("4999.00"))
                .isFree(false)
                .durationHours(42.5)
                .instructor(arjun)
                .lessonsCount(5)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Web Development")
                .tags("html,css,javascript,react,nodejs,mongodb")
                .isPublished(true)
                .build());

        Course react = courseRepository.save(Course.builder()
                .title("React Mastery")
                .slug("react-mastery")
                .description("Take your React skills to the next level. Advanced patterns, performance optimization, state management, and real-world project architecture.")
                .shortDescription("Advanced React patterns and best practices")
                .level(Course.Level.ADVANCED)
                .price(new BigDecimal("3499.00"))
                .isFree(false)
                .durationHours(28.0)
                .instructor(arjun)
                .lessonsCount(5)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Frontend")
                .tags("react,hooks,redux,nextjs,typescript")
                .isPublished(true)
                .build());

        Course python = courseRepository.save(Course.builder()
                .title("Python for Data Science")
                .slug("python-for-data-science")
                .description("Learn Python programming and data science from scratch. Covers NumPy, Pandas, Matplotlib, Scikit-learn, and real-world data analysis projects.")
                .shortDescription("Data analysis and ML with Python")
                .level(Course.Level.BEGINNER)
                .price(new BigDecimal("3999.00"))
                .isFree(false)
                .durationHours(35.0)
                .instructor(priya)
                .lessonsCount(5)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Data Science")
                .tags("python,numpy,pandas,matplotlib,scikit-learn,ml")
                .isPublished(true)
                .build());

        Course aws = courseRepository.save(Course.builder()
                .title("Cloud Architecture with AWS")
                .slug("cloud-architecture-with-aws")
                .description("Design and deploy scalable cloud solutions on AWS. Covers EC2, S3, Lambda, DynamoDB, CloudFormation, and architecture best practices.")
                .shortDescription("Build scalable cloud solutions on AWS")
                .level(Course.Level.ADVANCED)
                .price(new BigDecimal("5499.00"))
                .isFree(false)
                .durationHours(32.0)
                .instructor(rahul)
                .lessonsCount(4)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Cloud")
                .tags("aws,ec2,s3,lambda,dynamodb,cloudformation")
                .isPublished(true)
                .build());

        Course uiux = courseRepository.save(Course.builder()
                .title("UI/UX Design Fundamentals")
                .slug("ui-ux-design-fundamentals")
                .description("Learn the principles of great user interface and user experience design. Covers Figma, design systems, wireframing, prototyping, and usability testing.")
                .shortDescription("Design beautiful and usable interfaces")
                .level(Course.Level.BEGINNER)
                .price(new BigDecimal("2999.00"))
                .isFree(false)
                .durationHours(20.0)
                .instructor(priya)
                .lessonsCount(4)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Design")
                .tags("figma,ui,ux,wireframing,prototyping,design-systems")
                .isPublished(true)
                .build());

        Course mobile = courseRepository.save(Course.builder()
                .title("Mobile App Development with React Native")
                .slug("mobile-app-development-with-react-native")
                .description("Build cross-platform mobile apps with React Native. Covers navigation, state management, native modules, animations, and app store deployment.")
                .shortDescription("Cross-platform mobile apps with React Native")
                .level(Course.Level.INTERMEDIATE)
                .price(new BigDecimal("3999.00"))
                .isFree(false)
                .durationHours(30.0)
                .instructor(rahul)
                .lessonsCount(5)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Mobile")
                .tags("react-native,mobile,ios,android,javascript")
                .isPublished(true)
                .build());

        log.info("Seeded 6 courses.");

        // --- Lessons ---
        log.info("Seeding lessons...");

        // Full-Stack Web Development
        seedLessons(fullStack, List.of(
                new String[]{"Introduction to Web Development", "Overview of the web stack and setting up your development environment", "45"},
                new String[]{"HTML & CSS Deep Dive", "Semantic HTML, CSS Grid, Flexbox, and responsive design", "90"},
                new String[]{"JavaScript Fundamentals", "Variables, functions, DOM manipulation, and async programming", "80"},
                new String[]{"Backend with Node.js & Express", "Building REST APIs, middleware, and database integration", "95"},
                new String[]{"Full-Stack Project: E-Commerce App", "Putting it all together with a capstone project", "120"}
        ));

        // React Mastery
        seedLessons(react, List.of(
                new String[]{"Advanced Component Patterns", "Compound components, render props, and higher-order components", "65"},
                new String[]{"React Performance Optimization", "Memoization, code splitting, and profiling", "70"},
                new String[]{"State Management Deep Dive", "Context API, Redux Toolkit, and Zustand", "75"},
                new String[]{"Server-Side Rendering with Next.js", "SSR, SSG, ISR, and API routes", "80"},
                new String[]{"Testing React Applications", "Unit testing, integration testing, and E2E with Cypress", "55"}
        ));

        // Python for Data Science
        seedLessons(python, List.of(
                new String[]{"Python Basics for Data Science", "Variables, data types, loops, and functions", "60"},
                new String[]{"Data Manipulation with Pandas", "DataFrames, filtering, grouping, and merging", "85"},
                new String[]{"Data Visualization with Matplotlib", "Charts, plots, and customizing visualizations", "70"},
                new String[]{"Introduction to Machine Learning", "Scikit-learn, classification, and regression", "90"},
                new String[]{"Capstone: Predictive Analytics Project", "End-to-end ML project with real-world data", "100"}
        ));

        // Cloud Architecture with AWS
        seedLessons(aws, List.of(
                new String[]{"AWS Fundamentals & IAM", "Account setup, IAM roles, and security best practices", "55"},
                new String[]{"Compute & Storage Services", "EC2, S3, EBS, and auto-scaling groups", "80"},
                new String[]{"Serverless Architecture with Lambda", "Lambda functions, API Gateway, and event-driven design", "75"},
                new String[]{"Infrastructure as Code", "CloudFormation, Terraform basics, and CI/CD pipelines", "90"}
        ));

        // UI/UX Design Fundamentals
        seedLessons(uiux, List.of(
                new String[]{"Design Thinking & UX Principles", "User-centered design process and research methods", "50"},
                new String[]{"Wireframing & Prototyping", "Low-fi to high-fi prototypes with Figma", "65"},
                new String[]{"Visual Design & Design Systems", "Typography, color theory, and component libraries", "70"},
                new String[]{"Usability Testing & Iteration", "Conducting user tests and iterating on feedback", "55"}
        ));

        // Mobile App Development with React Native
        seedLessons(mobile, List.of(
                new String[]{"React Native Setup & Core Components", "Environment setup, View, Text, Image, and styling", "60"},
                new String[]{"Navigation & Routing", "React Navigation, stack, tab, and drawer navigators", "70"},
                new String[]{"State Management & API Integration", "Redux, AsyncStorage, and REST API consumption", "75"},
                new String[]{"Animations & Native Modules", "Animated API, Reanimated, and bridging native code", "80"},
                new String[]{"App Store Deployment", "Building, signing, and publishing to iOS and Android stores", "65"}
        ));

        log.info("Seeded lessons for all courses.");

        // --- Modules (group lessons by topic) ---
        log.info("Seeding modules and assigning lessons to them...");

        seedModules(fullStack, List.of(
                new ModuleSpec("Frontend Foundations",
                        "Get comfortable with HTML, CSS, and JavaScript before moving to the back end.",
                        List.of(1, 2, 3)),
                new ModuleSpec("Backend Development",
                        "Build a REST API with Node.js and Express.",
                        List.of(4)),
                new ModuleSpec("Capstone Project",
                        "Bring everything together by building a full-stack e-commerce app.",
                        List.of(5))
        ));

        seedModules(react, List.of(
                new ModuleSpec("Components & Performance",
                        "Advanced component patterns and how to keep React fast.",
                        List.of(1, 2)),
                new ModuleSpec("State & Architecture",
                        "Pick the right state-management strategy and explore Next.js rendering modes.",
                        List.of(3, 4)),
                new ModuleSpec("Quality & Testing",
                        "Test your React applications across unit, integration, and E2E layers.",
                        List.of(5))
        ));

        seedModules(python, List.of(
                new ModuleSpec("Python & Data Foundations",
                        "Core Python plus the data-manipulation library you'll use every day.",
                        List.of(1, 2)),
                new ModuleSpec("Visualization & Insights",
                        "Communicate findings with charts using Matplotlib.",
                        List.of(3)),
                new ModuleSpec("Machine Learning",
                        "Move from your first scikit-learn model to an end-to-end capstone.",
                        List.of(4, 5))
        ));

        seedModules(aws, List.of(
                new ModuleSpec("AWS Foundations",
                        "Get oriented in the AWS console and lock down access with IAM.",
                        List.of(1)),
                new ModuleSpec("Core Services",
                        "Compute, storage, and serverless: the AWS workhorses.",
                        List.of(2, 3)),
                new ModuleSpec("Operations & IaC",
                        "Codify your infrastructure with CloudFormation and CI/CD pipelines.",
                        List.of(4))
        ));

        seedModules(uiux, List.of(
                new ModuleSpec("UX Foundations",
                        "Design thinking and turning ideas into low- and high-fidelity prototypes.",
                        List.of(1, 2)),
                new ModuleSpec("Visual Design",
                        "Visual systems, typography, and reusable component libraries.",
                        List.of(3)),
                new ModuleSpec("Validation",
                        "Run usability tests and iterate based on what you learn.",
                        List.of(4))
        ));

        seedModules(mobile, List.of(
                new ModuleSpec("React Native Foundations",
                        "Project setup, core components, and navigation.",
                        List.of(1, 2)),
                new ModuleSpec("Building the App",
                        "Connect APIs, manage state, and animate native interactions.",
                        List.of(3, 4)),
                new ModuleSpec("Shipping",
                        "Build, sign, and publish to the App Store and Google Play.",
                        List.of(5))
        ));

        log.info("Seeded modules for all courses.");

        // --- Achievements ---
        log.info("Seeding achievements...");

        achievementRepository.save(Achievement.builder()
                .user(admin)
                .badgeName("First Login")
                .badgeIcon("trophy")
                .build());

        achievementRepository.save(Achievement.builder()
                .user(admin)
                .badgeName("7-Day Streak")
                .badgeIcon("flame")
                .build());

        log.info("Seeded 2 achievements.");

        // --- Enrollments ---
        log.info("Seeding enrollments...");

        seedEnrollment(student, fullStack);
        seedEnrollment(student, react);
        seedEnrollment(student, python);
        seedEnrollment(admin, fullStack);

        log.info("Seeded enrollments.");

        // Services + trainer (idempotent: also called from the early-return
        // branch above so both fresh and previously-seeded DBs get them).
        seedServicesAndTrainer(trainerRole);

        log.info("Database seeding complete!");
    }

    // ─── Services seed ─────────────────────────────────────────────
    // Services share the courses table (type=SERVICE). Idempotent on email
    // and slug — re-running this method is safe.
    //
    // NOTE on instructor_id: Course.instructor is JPA-mapped @ManyToOne
    // nullable=false, so the schema requires it. For services we set both
    // instructor and trainer to the trainer user (Meera). The CourseDTO and
    // frontend already prefer trainer over instructor when type=SERVICE
    // (see ServiceCard, /services/[id], cart). The task spec asked for
    // instructor_id=NULL; honoring that would need a schema + entity change
    // (loosen nullable=false), which is out of scope for a seed-data task.
    /**
     * Phase 4 — seed at least one ERM and one of each coach role so
     * the OnboardingService can actually assign team members on a
     * fresh deploy. All idempotent (existsByEmail short-circuits).
     * Default password "sage-team-2026" — change in any production
     * deploy via the admin panel.
     */
    private void seedPhase4Team() {
        Role ermRole = roleRepository.findByName("ERM").orElse(null);
        Role coachRole = roleRepository.findByName("COACH").orElse(null);
        Role techAdvisorRole = roleRepository.findByName("TECHNICAL_ADVISOR").orElse(null);
        Role financeRole = roleRepository.findByName("FINANCE").orElse(null);
        Role opsAdminRole = roleRepository.findByName("OPERATIONS_ADMIN").orElse(null);
        Role sysAdminRole = roleRepository.findByName("SYSTEM_ADMIN").orElse(null);
        if (ermRole == null || coachRole == null || techAdvisorRole == null) {
            log.warn("Phase 4 roles missing — skipping seed");
            return;
        }
        String defaultPassword = passwordEncoder.encode("sage-team-2026");

        // Legacy dev seeds — kept for parity with the original team
        // layout. Default password is the shared "sage-team-2026";
        // production should rotate through the admin panel.
        seedTeamUser("deepthi.erm@sageitco.com", "Deepthi R", ermRole, defaultPassword,
                "Program coordinator and Employee Relationship Manager.");
        seedTeamUser("arjun.coach@sageitco.com", "Arjun Menon", coachRole, defaultPassword,
                "Career coach — resume reviews, profile administration, job-market navigation.");
        seedTeamUser("priya.tech@sageitco.com", "Priya Sharma", techAdvisorRole, defaultPassword,
                "Technical advisor — Java Full Stack, Python Full Stack, Cloud & DevOps.");
        seedTeamUser("rahul.interview@sageitco.com", "Rahul Kapoor", coachRole, defaultPassword,
                "Interview coach — mock interviews, communication training.");

        // Production-style staff accounts — one per role, each with
        // a distinct password so credentials can be handed out
        // individually rather than sharing one password across the
        // whole team. Operations should rotate these on first login.
        seedTeamUser("erm@sageitco.com", "Deepthi R", ermRole,
                passwordEncoder.encode("SageERM@2026"),
                "Employee Relationship Manager — primary participant point of contact.");
        seedTeamUser("coach@sageitco.com", "Arjun Mehta", coachRole,
                passwordEncoder.encode("SageCoach@2026"),
                "Career coach — resume, profile, interview prep.");
        seedTeamUser("advisor@sageitco.com", "Priya Sharma", techAdvisorRole,
                passwordEncoder.encode("SageAdvisor@2026"),
                "Technical advisor — Java, Python, Cloud & DevOps mentorship.");
        if (financeRole != null) {
            seedTeamUser("finance@sageitco.com", "Rahul Kumar", financeRole,
                    passwordEncoder.encode("SageFinance@2026"),
                    "Finance — payment plans, invoices, check tracking.");
        } else {
            log.warn("FINANCE role missing — finance@sageitco.com not seeded");
        }
        if (opsAdminRole != null) {
            seedTeamUser("admin.ops@sageitco.com", "Admin User", opsAdminRole,
                    passwordEncoder.encode("SageAdmin@2026"),
                    "Operations admin — enrollment queue, document review, assignments.");
        } else {
            log.warn("OPERATIONS_ADMIN role missing — admin.ops@sageitco.com not seeded");
        }
        if (sysAdminRole != null) {
            seedTeamUser("superadmin@sageitco.com", "Super Admin", sysAdminRole,
                    passwordEncoder.encode("SageSuper@2026"),
                    "System administrator — full user CRUD, role assignment, audit access.");
        } else {
            log.warn("SYSTEM_ADMIN role missing — superadmin@sageitco.com not seeded");
        }
    }

    private void seedTeamUser(String email, String fullName, Role role,
                              String passwordHash, String bio) {
        if (userRepository.existsByEmail(email)) return;
        userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordHash)
                .fullName(fullName)
                .role(role)
                .bio(bio)
                .isActive(true)
                .emailVerified(true)
                .agreementAccepted(true)
                .currentStatus("DASHBOARD_ENABLED")
                .build());
        log.info("Seeded team user {} ({}) with role {}", email, fullName, role.getName());
    }

    private void seedServicesAndTrainer(Role trainerRole) {
        User meera = userRepository.findByEmail("meera@sageitco.com")
                .orElseGet(() -> {
                    log.info("Seeding trainer user: meera@sageitco.com");
                    return userRepository.save(User.builder()
                            .email("meera@sageitco.com")
                            .passwordHash(passwordEncoder.encode("password123"))
                            .fullName("Meera Iyer")
                            .role(trainerRole)
                            .bio("Career coach with 12+ years guiding professionals through resumes, interviews, and placement.")
                            .build());
                });

        seedService(meera,
                "professional-resume-preparation",
                "Professional Resume Preparation",
                "Build a resume that gets you interviews. Learn formatting, content strategy, and ATS optimization.",
                "A practical, video-based walk-through of how to write a resume that survives applicant tracking systems and reads well to a human recruiter. Covers formats, content strategy, and the small details that separate good resumes from great ones.",
                1999,
                List.of(
                        new SvcModule("Resume Fundamentals",
                                "Start with what recruiters actually look at and the formats that work today.",
                                List.of(
                                        new SvcLesson("What Recruiters Look For", 15),
                                        new SvcLesson("Resume Formats: Chronological vs Functional", 12),
                                        new SvcLesson("ATS-Friendly Formatting", 10)
                                )),
                        new SvcModule("Content Strategy",
                                "Turn responsibilities into impact statements that convince.",
                                List.of(
                                        new SvcLesson("Writing Impact Statements", 18),
                                        new SvcLesson("Quantifying Your Achievements", 14),
                                        new SvcLesson("Tailoring for Each Application", 12)
                                )),
                        new SvcModule("Final Polish",
                                "Avoid the common mistakes and build an iteration habit.",
                                List.of(
                                        new SvcLesson("Common Mistakes to Avoid", 10),
                                        new SvcLesson("Review and Iteration Process", 15)
                                ))
                ));

        seedService(meera,
                "interview-training-masterclass",
                "Interview Training Masterclass",
                "Prepare for technical and behavioral interviews with proven frameworks and real practice scenarios.",
                "End-to-end interview preparation: behavioral storytelling with STAR, technical whiteboarding, system design framing, and the soft-skill moments that decide offers.",
                2999,
                List.of(
                        new SvcModule("Interview Fundamentals",
                                "Map the interview landscape and prepare a research-backed plan.",
                                List.of(
                                        new SvcLesson("Understanding Interview Types", 12),
                                        new SvcLesson("The STAR Method for Behavioral Questions", 18),
                                        new SvcLesson("Research and Preparation Checklist", 10)
                                )),
                        new SvcModule("Technical Interviews",
                                "Whiteboard, system design, and the patterns behind coding rounds.",
                                List.of(
                                        new SvcLesson("Whiteboard Problem-Solving Approach", 20),
                                        new SvcLesson("System Design Interview Framework", 25),
                                        new SvcLesson("Coding Interview Patterns", 22)
                                )),
                        new SvcModule("Soft Skills & Negotiation",
                                "Handle pressure, negotiate well, and follow up with intent.",
                                List.of(
                                        new SvcLesson("Handling Tough Questions", 15),
                                        new SvcLesson("Salary Negotiation Strategies", 18),
                                        new SvcLesson("Post-Interview Follow-Up", 10)
                                ))
                ));

        seedService(meera,
                "linkedin-profile-optimization",
                "LinkedIn Profile Optimization",
                "Transform your LinkedIn profile into a powerful career tool that attracts recruiters and opportunities.",
                "Make your LinkedIn profile work for you. Headline, summary, experience, content, and a connection strategy that gets you noticed by the right people.",
                1499,
                List.of(
                        new SvcModule("Profile Essentials",
                                "Get the basics right — the parts recruiters scan first.",
                                List.of(
                                        new SvcLesson("Headline and Summary That Stand Out", 15),
                                        new SvcLesson("Experience Section Best Practices", 12),
                                        new SvcLesson("Skills, Endorsements, and Recommendations", 10)
                                )),
                        new SvcModule("Advanced LinkedIn Strategy",
                                "Move beyond the static profile — content, networks, and search.",
                                List.of(
                                        new SvcLesson("Content Creation for Visibility", 18),
                                        new SvcLesson("Networking and Connection Strategy", 14),
                                        new SvcLesson("Using LinkedIn for Job Search", 16)
                                ))
                ));

        seedService(meera,
                "placement-assistance-program",
                "Placement Assistance Program",
                "End-to-end job placement support including job search strategy, application tracking, and interview prep.",
                "A complete placement playbook: identify targets, build a pipeline, write strong cover letters, and close offers without leaving money on the table.",
                4999,
                List.of(
                        new SvcModule("Job Search Strategy",
                                "Pick the right targets and build a pipeline you can manage.",
                                List.of(
                                        new SvcLesson("Identifying Target Companies", 15),
                                        new SvcLesson("Job Board and Referral Strategy", 18),
                                        new SvcLesson("Building Your Application Pipeline", 12)
                                )),
                        new SvcModule("Application Process",
                                "From cover letter to portfolio to follow-up.",
                                List.of(
                                        new SvcLesson("Cover Letter Writing", 14),
                                        new SvcLesson("Portfolio and GitHub Presentation", 16),
                                        new SvcLesson("Following Up Effectively", 10)
                                )),
                        new SvcModule("Closing the Deal",
                                "Evaluate, negotiate, and ramp up.",
                                List.of(
                                        new SvcLesson("Evaluating Job Offers", 15),
                                        new SvcLesson("Negotiation and Acceptance", 12),
                                        new SvcLesson("First 90 Days at Your New Job", 18)
                                ))
                ));
    }

    /**
     * Backfills the email-verification + password-reset + inactivity
     * nudge columns on the {@code users} table. Each ALTER uses
     * {@code ADD COLUMN IF NOT EXISTS} so reruns are idempotent — the
     * statement no-ops once the column exists.
     *
     * The {@code email_verified} column carries an explicit
     * {@code DEFAULT FALSE} so Hibernate's later ddl-auto=update
     * doesn't have to grapple with a NOT NULL ALTER on a populated
     * table. Without this, the deploy that introduced these fields
     * silently left the columns missing and every welcome / verify
     * email path failed at write time.
     *
     * Postgres-first; falls back to MySQL syntax (which also
     * supports IF NOT EXISTS on 8.0.16+). Each ALTER is wrapped so
     * an unsupported dialect / already-applied state is a silent
     * no-op rather than crashing app startup.
     */
    private void addUserEmailColumnsIfMissing() {
        // Tuples of (column DDL, log label). Order matches the
        // declarations in User.java for readability.
        String[][] columns = {
                {"email_verified BOOLEAN NOT NULL DEFAULT FALSE", "email_verified"},
                {"verification_token VARCHAR(64)", "verification_token"},
                {"verification_expires_at TIMESTAMP", "verification_expires_at"},
                {"verification_code VARCHAR(6)", "verification_code"},
                {"verification_code_expires_at TIMESTAMP", "verification_code_expires_at"},
                {"verification_failed_attempts INT NOT NULL DEFAULT 0", "verification_failed_attempts"},
                {"verification_locked_until TIMESTAMP", "verification_locked_until"},
                {"last_verification_resend_at TIMESTAMP", "last_verification_resend_at"},
                {"reset_token VARCHAR(64)", "reset_token"},
                {"reset_token_expires_at TIMESTAMP", "reset_token_expires_at"},
                {"last_nudge_sent_at TIMESTAMP", "last_nudge_sent_at"},
                {"agreement_accepted BOOLEAN NOT NULL DEFAULT FALSE", "agreement_accepted"},
                {"deactivated_at TIMESTAMP", "deactivated_at"},
                // Phase 1A — participant lifecycle columns.
                {"participant_id VARCHAR(20)", "participant_id"},
                {"participant_id_created_at TIMESTAMP", "participant_id_created_at"},
                {"availability VARCHAR(100)", "availability"},
                {"selected_technology VARCHAR(255)", "selected_technology"},
                {"target_experience_level VARCHAR(50)", "target_experience_level"},
                {"current_status VARCHAR(50) DEFAULT 'DRAFT_STARTED'", "current_status"},
        };
        for (String[] col : columns) {
            try {
                jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS " + col[0]);
                log.info("Ensured users.{} exists", col[1]);
            } catch (Exception e) {
                log.debug("Couldn't add users.{} (likely already present or unsupported dialect): {}",
                        col[1], e.getMessage());
            }
        }

        // Grandfather pre-OTP accounts. Any existing user that was
        // unverified under the old token-based flow (no verification_code
        // populated) is marked verified so the OTP gate doesn't lock
        // them out on the day this ships. New post-OTP signups always
        // have a verification_code set, so they're not touched.
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE users SET email_verified = TRUE " +
                    "WHERE email_verified = FALSE AND verification_code IS NULL");
            if (updated > 0) {
                log.info("Grandfathered {} pre-OTP users to email_verified=true", updated);
            }
        } catch (Exception e) {
            log.debug("Couldn't grandfather pre-OTP users: {}", e.getMessage());
        }

        // Grandfather pre-agreement accounts. Email-verified users
        // who existed before the agreement gate shipped have no
        // acceptance row to satisfy the new requirement; rather
        // than retroactively kick them to /agreement on next login,
        // mark them accepted. New signups always start with
        // agreement_accepted=false and have to walk through the
        // /agreement flow.
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE users SET agreement_accepted = TRUE " +
                    "WHERE agreement_accepted = FALSE AND email_verified = TRUE");
            if (updated > 0) {
                log.info("Grandfathered {} pre-agreement users to agreement_accepted=true", updated);
            }
        } catch (Exception e) {
            log.debug("Couldn't grandfather pre-agreement users: {}", e.getMessage());
        }

        // Backfill the signed-PDF URL column on agreement_records.
        // Set when the post-OTP PDF generator successfully writes the
        // personalized signed agreement; nullable so historic rows
        // (accepted before this flow shipped) stay valid.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE agreement_records ADD COLUMN IF NOT EXISTS "
                            + "signed_agreement_pdf_url VARCHAR(512)");
            log.info("Ensured agreement_records.signed_agreement_pdf_url exists");
        } catch (Exception e) {
            log.debug("Couldn't add agreement_records.signed_agreement_pdf_url "
                    + "(likely already present, or table not yet created): {}", e.getMessage());
        }

        // Backfill the signature columns. signature_image holds the
        // base64-encoded PNG (drawn on canvas or uploaded by the user);
        // signature_method records 'draw' or 'upload' for audit. Both
        // nullable so historic rows that pre-date the signature flow
        // keep validating.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE agreement_records ADD COLUMN IF NOT EXISTS "
                            + "signature_image TEXT");
            log.info("Ensured agreement_records.signature_image exists");
        } catch (Exception e) {
            log.debug("Couldn't add agreement_records.signature_image: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE agreement_records ADD COLUMN IF NOT EXISTS "
                            + "signature_method VARCHAR(20)");
            log.info("Ensured agreement_records.signature_method exists");
        } catch (Exception e) {
            log.debug("Couldn't add agreement_records.signature_method: {}", e.getMessage());
        }

        // Phase 1A — make participant_id unique. The entity carries
        // @Column(unique = true) but Hibernate ddl-auto=update won't
        // retro-add a unique constraint to a column that was created
        // via raw ALTER above, so spell it out.
        try {
            jdbcTemplate.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_participant_id "
                            + "ON users(participant_id)");
            log.info("Ensured unique idx_users_participant_id");
        } catch (Exception e) {
            log.debug("Couldn't create unique index on users.participant_id: {}",
                    e.getMessage());
        }

        // Backfill current_status on rows that pre-date the column.
        // The column-add above carries DEFAULT 'DRAFT_STARTED' but
        // DEFAULT only fires on new INSERTs; existing rows stay NULL
        // until we explicitly seed them. Treat any pre-existing user
        // as "already through onboarding" — they were verified and
        // (potentially) had accepted an agreement under the legacy
        // flow, so dropping them at DRAFT_STARTED would visually
        // un-enroll them. Pin them at DASHBOARD_ENABLED so they
        // bypass the new participant-lifecycle onboarding pages
        // entirely — they're legacy LMS users who already have a
        // dashboard. (Phase 4: WELCOME_SENT now means "post-agreement,
        // awaiting team assembly" which would loop them on the
        // welcome page; DASHBOARD_ENABLED is the actual end-state.)
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE users SET current_status = 'DASHBOARD_ENABLED' "
                            + "WHERE current_status IS NULL "
                            + "AND email_verified = TRUE");
            if (updated > 0) {
                log.info("Grandfathered {} pre-Phase-1A users to current_status=DASHBOARD_ENABLED", updated);
            }
            // Also catch already-grandfathered rows that landed at
            // WELCOME_SENT under the earlier rule — bump them
            // forward so the routing guard doesn't drop them on
            // /welcome.
            int reGrandfathered = jdbcTemplate.update(
                    "UPDATE users SET current_status = 'DASHBOARD_ENABLED' "
                            + "WHERE current_status = 'WELCOME_SENT' "
                            + "AND agreement_accepted = TRUE "
                            + "AND participant_id IS NULL");
            if (reGrandfathered > 0) {
                log.info("Re-grandfathered {} legacy WELCOME_SENT users to DASHBOARD_ENABLED", reGrandfathered);
            }
            int draftUpdated = jdbcTemplate.update(
                    "UPDATE users SET current_status = 'DRAFT_STARTED' "
                            + "WHERE current_status IS NULL");
            if (draftUpdated > 0) {
                log.info("Defaulted {} unverified users to current_status=DRAFT_STARTED", draftUpdated);
            }
        } catch (Exception e) {
            log.debug("Couldn't backfill users.current_status: {}", e.getMessage());
        }

        // Phase 2A: signature columns on the acknowledgments table.
        // Mirrors the AgreementAcceptance pattern — base64 PNG +
        // method label. Both nullable so rows from earlier (none
        // exist yet, but future flows might write without a
        // signature) keep validating.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE acknowledgments ADD COLUMN IF NOT EXISTS "
                            + "signature_image TEXT");
            log.info("Ensured acknowledgments.signature_image exists");
        } catch (Exception e) {
            log.debug("Couldn't add acknowledgments.signature_image: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE acknowledgments ADD COLUMN IF NOT EXISTS "
                            + "signature_method VARCHAR(20)");
            log.info("Ensured acknowledgments.signature_method exists");
        } catch (Exception e) {
            log.debug("Couldn't add acknowledgments.signature_method: {}", e.getMessage());
        }

        // Phase 2B: not_applicable flag on the documents vault row.
        // Lets the participant mark a document type (e.g. Work
        // Authorization for a domestic candidate) as N/A so the
        // completeness gate can be satisfied without a file. Default
        // false; existing rows are real uploads.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE documents ADD COLUMN IF NOT EXISTS "
                            + "not_applicable BOOLEAN NOT NULL DEFAULT FALSE");
            log.info("Ensured documents.not_applicable exists");
        } catch (Exception e) {
            log.debug("Couldn't add documents.not_applicable: {}", e.getMessage());
        }

        // Phase 3B: agreement_records gets erm_notified flag
        // so the operations dashboard can filter pending-routing
        // rows. Default false; flipped true once the post-OTP
        // post-processing routes the signed agreement to ERM.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE agreement_records ADD COLUMN IF NOT EXISTS "
                            + "erm_notified BOOLEAN NOT NULL DEFAULT FALSE");
            log.info("Ensured agreement_records.erm_notified exists");
        } catch (Exception e) {
            log.debug("Couldn't add agreement_records.erm_notified: {}", e.getMessage());
        }

        // Phase 3A: program_selections gets the version of the
        // service-summary text the participant reviewed plus
        // free-form notes. Both nullable for backward compat.
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE program_selections ADD COLUMN IF NOT EXISTS "
                            + "service_summary_version VARCHAR(20)");
            log.info("Ensured program_selections.service_summary_version exists");
        } catch (Exception e) {
            log.debug("Couldn't add program_selections.service_summary_version: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE program_selections ADD COLUMN IF NOT EXISTS "
                            + "notes TEXT");
            log.info("Ensured program_selections.notes exists");
        } catch (Exception e) {
            log.debug("Couldn't add program_selections.notes: {}", e.getMessage());
        }
    }

    /**
     * Backfills the two-stage agreement-fill columns onto
     * {@code consultant_applications}. All nullable / additive --
     * the existing state machine, controllers, and services don't
     * reference any of these yet, so this is purely a schema-prep
     * migration that lets the upcoming multi-stage form persist
     * its data without each PR having to re-run a DDL change.
     *
     * Idempotent: every column uses {@code ADD COLUMN IF NOT EXISTS}
     * and each statement is wrapped so an unsupported dialect /
     * already-applied state logs at debug and continues with the
     * next column rather than crashing app startup. Mirrors the
     * pattern from {@link #addUserEmailColumnsIfMissing()}.
     *
     * Order roughly mirrors the appendix layout in the field spec:
     * ERM rate card, consultant personal block, Exhibit A scope,
     * Appendix 1 (employment), Appendix 2 (ACH), Appendix 3
     * (background check), Appendix 4 (portal access), Appendix 5
     * (security check), ERM countersignature, revision tracking,
     * and the final post-ERM-signature PDF URL.
     */
    private void addConsultantApplicationColumnsIfMissing() {
        String[][] columns = {
                // ERM-filled rate card.
                {"rate_period_1 VARCHAR(255)", "rate_period_1"},
                {"rate_amount_1 VARCHAR(255)", "rate_amount_1"},
                {"rate_period_2 VARCHAR(255)", "rate_period_2"},
                {"rate_amount_2 VARCHAR(255)", "rate_amount_2"},
                // Consultant personal (required at fill-in time).
                {"primary_phone VARCHAR(255)", "primary_phone"},
                {"work_authorization_category VARCHAR(255)", "work_authorization_category"},
                {"residence_address TEXT", "residence_address"},
                {"effective_date DATE", "effective_date"},
                // Exhibit A.
                {"technology_track VARCHAR(255)", "technology_track"},
                {"custom_scope_notes TEXT", "custom_scope_notes"},
                // Appendix 1 -- Phase 2 employment.
                {"employer_payroll_entity VARCHAR(255)", "employer_payroll_entity"},
                {"implementation_partner VARCHAR(255)", "implementation_partner"},
                {"end_client VARCHAR(255)", "end_client"},
                {"role_title VARCHAR(255)", "role_title"},
                {"verified_start_date DATE", "verified_start_date"},
                {"payroll_cycle VARCHAR(255)", "payroll_cycle"},
                // Appendix 2 -- ACH (all optional).
                {"ach_account_type VARCHAR(255)", "ach_account_type"},
                {"ach_bank_name VARCHAR(255)", "ach_bank_name"},
                {"ach_account_holder_name VARCHAR(255)", "ach_account_holder_name"},
                {"ach_routing_number VARCHAR(255)", "ach_routing_number"},
                {"ach_account_number VARCHAR(255)", "ach_account_number"},
                {"ach_notice_email VARCHAR(255)", "ach_notice_email"},
                {"ach_debit_dates VARCHAR(255)", "ach_debit_dates"},
                {"ach_debit_amounts VARCHAR(255)", "ach_debit_amounts"},
                // Appendix 3 -- background check (sensitive PII).
                {"bg_full_legal_name VARCHAR(255)", "bg_full_legal_name"},
                {"bg_other_names_used VARCHAR(255)", "bg_other_names_used"},
                {"bg_current_address TEXT", "bg_current_address"},
                {"bg_date_of_birth DATE", "bg_date_of_birth"},
                {"bg_full_ssn TEXT", "bg_full_ssn"},
                {"bg_driver_license TEXT", "bg_driver_license"},
                // Appendix 4 -- portal access (optional).
                {"portal_platform VARCHAR(255)", "portal_platform"},
                {"portal_username VARCHAR(255)", "portal_username"},
                {"portal_authorized_actions TEXT", "portal_authorized_actions"},
                {"portal_effective_date DATE", "portal_effective_date"},
                {"portal_revocation_contact VARCHAR(255)", "portal_revocation_contact"},
                // Appendix 5 -- security check (optional).
                {"security_check_count VARCHAR(255)", "security_check_count"},
                {"security_check_numbers VARCHAR(255)", "security_check_numbers"},
                {"security_check_bank VARCHAR(255)", "security_check_bank"},
                {"security_check_holder_name VARCHAR(255)", "security_check_holder_name"},
                {"security_check_amount VARCHAR(255)", "security_check_amount"},
                {"security_check_dates VARCHAR(255)", "security_check_dates"},
                // ERM countersignature.
                {"erm_name VARCHAR(255)", "erm_name"},
                {"erm_title VARCHAR(255)", "erm_title"},
                {"erm_signature_url TEXT", "erm_signature_url"},
                {"signature_date TIMESTAMP", "signature_date"},
                // Revision tracking.
                {"current_revision_remarks TEXT", "current_revision_remarks"},
                {"revision_count INTEGER DEFAULT 0", "revision_count"},
                // Multi-user phase: owner (creating AgreementUser).
                // VARCHAR(36) matches the agreement_user UUID PK.
                // Populated at create time + backfilled below; NOT yet
                // used to filter reads (next phase).
                {"owner_erm_id VARCHAR(36)", "owner_erm_id"},
                // Final post-countersignature PDF.
                {"final_pdf_url TEXT", "final_pdf_url"},
                // Cloudinary public_id for the final PDF. Required so
                // the signed-URL helper can re-mint short-lived URLs
                // for download/view-inline when delivery switches to
                // type=authenticated.
                {"final_pdf_public_id VARCHAR(255)", "final_pdf_public_id"},
                // Phase C — soft-delete (archive) of cancelled apps.
                // super-admin-only; recoverable at DB level.
                {"deleted BOOLEAN DEFAULT FALSE", "deleted"},
                {"deleted_at TIMESTAMP", "deleted_at"},
                {"deleted_by VARCHAR(36)", "deleted_by"},
                // Consultant access record (email-OTP gate). Real client
                // IP (X-Forwarded-For first hop) at OTP verify + at sign.
                {"access_ip VARCHAR(64)", "access_ip"},
                {"access_at TIMESTAMP", "access_at"},
                {"signing_ip VARCHAR(64)", "signing_ip"},
                {"signing_at TIMESTAMP", "signing_at"},
                // F-1 (guided signing foundation) -- one affirmation
                // flag per signing section. DEFAULT FALSE so existing
                // rows backfill to "not yet affirmed" automatically;
                // the entity field defaults match. Submit-time
                // validation in ConsultantApplicationService requires
                // all eight to be true before the SUBMITTED ->
                // VERIFIED transition lands.
                {"affirmed_main_agreement BOOLEAN NOT NULL DEFAULT FALSE",
                        "affirmed_main_agreement"},
                {"affirmed_exhibit_a BOOLEAN NOT NULL DEFAULT FALSE",
                        "affirmed_exhibit_a"},
                {"affirmed_exhibit_b BOOLEAN NOT NULL DEFAULT FALSE",
                        "affirmed_exhibit_b"},
                {"affirmed_appendix1 BOOLEAN NOT NULL DEFAULT FALSE",
                        "affirmed_appendix1"},
                {"affirmed_appendix2 BOOLEAN NOT NULL DEFAULT FALSE",
                        "affirmed_appendix2"},
                {"affirmed_appendix3 BOOLEAN NOT NULL DEFAULT FALSE",
                        "affirmed_appendix3"},
                {"affirmed_appendix4 BOOLEAN NOT NULL DEFAULT FALSE",
                        "affirmed_appendix4"},
                {"affirmed_appendix5 BOOLEAN NOT NULL DEFAULT FALSE",
                        "affirmed_appendix5"},
                // F-4 (configurable per-agreement requirements). The ERM
                // marks each appendix Required/Not Required when sending
                // and decides whether SSN inside Appendix 3 is required.
                // DEFAULT FALSE so existing rows backfill as "optional"
                // -- safe for the rollover, since the old all-required
                // gate is being replaced in the same release.
                {"require_appendix1 BOOLEAN NOT NULL DEFAULT FALSE",
                        "require_appendix1"},
                {"require_appendix2 BOOLEAN NOT NULL DEFAULT FALSE",
                        "require_appendix2"},
                {"require_appendix3 BOOLEAN NOT NULL DEFAULT FALSE",
                        "require_appendix3"},
                {"require_appendix4 BOOLEAN NOT NULL DEFAULT FALSE",
                        "require_appendix4"},
                {"require_appendix5 BOOLEAN NOT NULL DEFAULT FALSE",
                        "require_appendix5"},
                {"require_ssn BOOLEAN NOT NULL DEFAULT FALSE",
                        "require_ssn"},
                // F-4 closing/execution signature -- captured at the
                // review step; stamped on the agreement's last
                // consultant signature block.
                {"final_signature_image TEXT", "final_signature_image"},
                {"final_signed_at TIMESTAMP", "final_signed_at"},
                {"final_signing_ip VARCHAR(64)", "final_signing_ip"},
                // Build G — Appendix 3 ID type toggle (DL | STATE_ID)
                // alongside the relabeled bg_driver_license column
                // (now "ID number"). Plain VARCHAR (no enum mapping)
                // so the ALTER stays additive.
                {"id_type VARCHAR(16)", "id_type"},
                // Build G — Appendix 5 security cheque upload. The
                // bytes live in Cloudinary; only the public_id +
                // timestamp + original content type are persisted.
                // No NOT NULL constraint: the cheque is required
                // only when Appendix 5 applies, gated in the submit
                // validator.
                {"cheque_public_id VARCHAR(255)", "cheque_public_id"},
                {"cheque_uploaded_at TIMESTAMP", "cheque_uploaded_at"},
                {"cheque_content_type VARCHAR(64)", "cheque_content_type"},
                // Build L — 15-day invite expiry. Set at create + every
                // resend-invite; the sweep flips SUBMITTED rows whose
                // invite is >15 days old to EXPIRED.
                {"invite_sent_at TIMESTAMP", "invite_sent_at"},
                // Build M — two-phase coaching. 1 = pre-employment
                // (current default), 2 = post-offer (advanced via the
                // ERM "Advance to Phase 2" action). NOT NULL DEFAULT 1
                // so existing rows backfill cleanly.
                {"phase SMALLINT NOT NULL DEFAULT 1", "phase"},
                // Build T — ERM "Approve consultant version" releases a
                // consultant-version PDF (consultant signatures only, no
                // ERM signature) with an appended Certificate of
                // Completion. The consultant downloads it OTP-gated.
                // Distinct from the COMPLETED ERM-signed copy, which is
                // not consultant-downloadable in this build.
                {"consultant_copy_released BOOLEAN NOT NULL DEFAULT FALSE",
                        "consultant_copy_released"},
                {"consultant_copy_released_at TIMESTAMP",
                        "consultant_copy_released_at"},
                {"consultant_copy_released_by VARCHAR(36)",
                        "consultant_copy_released_by"},
                {"consultant_pdf_public_id VARCHAR(255)",
                        "consultant_pdf_public_id"},
                // Build T — SHA-256 of the released consultant-version
                // PDF bytes. Printed on the Certificate of Completion
                // so the recipient can verify integrity off-line.
                {"document_hash VARCHAR(128)", "document_hash"},
                // Build T — E-sign consent capture (E-SIGN Act +
                // UETA equivalent). Recorded at the consent gate
                // BEFORE the consultant fills/signs; surfaced on the
                // certificate.
                {"consent_given_at TIMESTAMP", "consent_given_at"},
                {"consent_ip VARCHAR(64)", "consent_ip"},
                {"consent_version VARCHAR(32)", "consent_version"},
                // Build U — multi-cheque support. Stored as JSON-in-TEXT
                // for cross-DB portability (same convention as payload).
                // Legacy single cheque_public_id stays as a fallback.
                {"cheques TEXT", "cheques"},
                // Build U — ERM-visible count of times the consultant
                // has downloaded their released consultant-version PDF.
                // Incremented on every successful POST .../download.
                {"consultant_download_count INTEGER NOT NULL DEFAULT 0",
                        "consultant_download_count"},
                {"consultant_last_downloaded_at TIMESTAMP",
                        "consultant_last_downloaded_at"},
                // Build W — structured name (First/Middle/Last). The
                // service composes consultant_name from these on save;
                // legacy rows keep their single consultant_name value.
                {"first_name VARCHAR(120)", "first_name"},
                {"middle_name VARCHAR(120)", "middle_name"},
                {"last_name VARCHAR(120)", "last_name"},
                // Build W — work-authorization custom value (used only
                // when work_authorization_category = 'Others').
                {"work_authorization_other VARCHAR(255)",
                        "work_authorization_other"},
                // Build W — structured US billing address. The render
                // layer assembles ${residenceAddress} from these;
                // residence_address stays as a legacy fallback.
                {"address_line1 VARCHAR(255)", "address_line1"},
                {"address_line2 VARCHAR(255)", "address_line2"},
                {"address_city VARCHAR(120)", "address_city"},
                {"address_state VARCHAR(8)", "address_state"},
                {"address_zip VARCHAR(16)", "address_zip"},
                // Build W — Appendix 1 work-authorization document
                // upload. Bytes in Cloudinary; only the public_id +
                // timestamp + content type persisted. Required when
                // Appendix 1 applies (gated in the submit validator).
                {"work_auth_doc_public_id VARCHAR(255)",
                        "work_auth_doc_public_id"},
                {"work_auth_doc_uploaded_at TIMESTAMP",
                        "work_auth_doc_uploaded_at"},
                {"work_auth_doc_content_type VARCHAR(64)",
                        "work_auth_doc_content_type"},
                // Build W — ERM countersign date, distinct from the
                // consultant signature_date. Null until the ERM signs so
                // no date renders under the unsigned ERM block.
                {"erm_signature_date TIMESTAMP", "erm_signature_date"},
                // Build Y — ERM-filled ACH debit schedule (JSON rows).
                {"ach_debit_schedule TEXT", "ach_debit_schedule"},
                // Build Y — ERM section-picker revision scope (JSON).
                {"revision_sections TEXT", "revision_sections"},
                // Build I — Phase 2 Employment "Offer Letter" upload.
                {"offer_letter_public_id VARCHAR(255)", "offer_letter_public_id"},
                {"offer_letter_uploaded_at TIMESTAMP", "offer_letter_uploaded_at"},
                {"offer_letter_content_type VARCHAR(64)", "offer_letter_content_type"},
                // Build J — Background Check structured current address.
                {"bg_current_address_line1 VARCHAR(255)", "bg_current_address_line1"},
                {"bg_current_address_line2 VARCHAR(255)", "bg_current_address_line2"},
                {"bg_current_address_city VARCHAR(120)", "bg_current_address_city"},
                {"bg_current_address_state VARCHAR(8)", "bg_current_address_state"},
                {"bg_current_address_zip VARCHAR(16)", "bg_current_address_zip"},
                {"bg_current_same_as_residence BOOLEAN", "bg_current_same_as_residence"},
                // Build J — Background Check document uploads (DL/State-ID + SSN).
                {"dl_doc_public_id VARCHAR(255)", "dl_doc_public_id"},
                {"dl_doc_uploaded_at TIMESTAMP", "dl_doc_uploaded_at"},
                {"dl_doc_content_type VARCHAR(64)", "dl_doc_content_type"},
                {"ssn_doc_public_id VARCHAR(255)", "ssn_doc_public_id"},
                {"ssn_doc_uploaded_at TIMESTAMP", "ssn_doc_uploaded_at"},
                {"ssn_doc_content_type VARCHAR(64)", "ssn_doc_content_type"},
                // Build J — repeatable Portal Access platform+username entries.
                {"portal_entries TEXT", "portal_entries"},
                // Build O — optional ERM-authored invitation email pre-text.
                {"email_pretext TEXT", "email_pretext"},
                // Build P — Phase 2 reopened-section scope (JSON array).
                {"phase2_reopened_sections TEXT", "phase2_reopened_sections"},
                // Phase 1 (S3) — storage keys for the S3-backed final +
                // consultant-version PDFs (Cloudinary columns stay null for
                // new records; downloads dual-read on these).
                {"s3_key VARCHAR(512)", "s3_key"},
                {"consultant_pdf_s3_key VARCHAR(512)", "consultant_pdf_s3_key"},
        };
        // Build Q — agreements no longer expire (the 7-day TTL applies to
        // the consultant LINK only, as a derived indicator). Restore every
        // previously-EXPIRED, non-deleted agreement to its TRUE prior
        // status so it returns to the dashboards in the right lifecycle
        // state. EXPIRED was historically produced from several statuses
        // (the first cron flipped DRAFT/SUBMITTED/REVISION_REQUESTED/
        // UPDATED/VERIFIED), so a blanket →SUBMITTED would corrupt e.g. a
        // VERIFIED (already-signed, awaiting-countersignature) row by
        // re-opening it for consultant editing. Each EXPIRED transition
        // recorded metadata.previousStatus on an EXPIRED audit event, so we
        // recover that per row; fall back to SUBMITTED only when no prior
        // status is recoverable (visible + resendable). COMPLETED was never
        // EXPIRED, so executed agreements are untouched. Idempotent: re-runs
        // find no EXPIRED rows.
        try {
            java.util.Set<String> validStatuses = new java.util.HashSet<>();
            for (com.spire.backend.entity.ConsultantApplication.Status v
                    : com.spire.backend.entity.ConsultantApplication.Status.values()) {
                validStatuses.add(v.name());
            }
            java.util.regex.Pattern prevPat = java.util.regex.Pattern.compile(
                    "\"previousStatus\"\\s*:\\s*\"([A-Z_]+)\"");
            java.util.List<Long> expiredIds = jdbcTemplate.queryForList(
                    "SELECT id FROM consultant_applications "
                            + "WHERE status = 'EXPIRED' AND deleted = FALSE",
                    Long.class);
            int restored = 0;
            for (Long id : expiredIds) {
                String target = "SUBMITTED"; // fallback when unrecoverable
                try {
                    java.util.List<String> metas = jdbcTemplate.queryForList(
                            "SELECT metadata FROM consultant_application_events "
                                    + "WHERE application_id = ? AND event_type = 'EXPIRED' "
                                    + "ORDER BY created_at DESC",
                            String.class, id);
                    for (String meta : metas) {
                        if (meta == null) continue;
                        java.util.regex.Matcher m = prevPat.matcher(meta);
                        if (m.find()) {
                            String prev = m.group(1);
                            if (validStatuses.contains(prev) && !"EXPIRED".equals(prev)) {
                                target = prev;
                            }
                            break;
                        }
                    }
                } catch (Exception ignore) { /* fall back to SUBMITTED */ }
                jdbcTemplate.update(
                        "UPDATE consultant_applications SET status = ? WHERE id = ?",
                        target, id);
                restored++;
            }
            if (restored > 0) {
                log.info("Build Q — restored {} previously-EXPIRED agreement(s) to "
                        + "their true prior status (link-only expiry)", restored);
            }
        } catch (Exception e) {
            log.warn("Build Q EXPIRED restore skipped: {}", e.getMessage());
        }
        for (String[] col : columns) {
            try {
                jdbcTemplate.execute(
                        "ALTER TABLE consultant_applications ADD COLUMN IF NOT EXISTS " + col[0]);
                log.info("Ensured consultant_applications.{} exists", col[1]);
            } catch (Exception e) {
                log.debug("Couldn't add consultant_applications.{} "
                        + "(likely already present or unsupported dialect): {}",
                        col[1], e.getMessage());
            }
        }

        // Phase 1 (S3) — storage key for the S3-backed course-completion
        // certificate PDF (older rows stay on disk; null = on-disk file).
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE certificates ADD COLUMN IF NOT EXISTS s3_key VARCHAR(512)");
            log.info("Ensured certificates.s3_key exists");
        } catch (Exception e) {
            log.debug("Couldn't add certificates.s3_key "
                    + "(likely already present or unsupported dialect): {}", e.getMessage());
        }

        // Backfill: Hibernate's ddl-auto=update may have already added
        // `deleted` as a nullable column (no default) before this runs,
        // leaving existing rows NULL. The list filter is `deleted = false`,
        // which would silently drop NULL rows, so normalize them. Idempotent.
        try {
            int normalized = jdbcTemplate.update(
                    "UPDATE consultant_applications SET deleted = FALSE WHERE deleted IS NULL");
            if (normalized > 0) {
                log.info("Backfilled consultant_applications.deleted=false on {} row(s)", normalized);
            }
        } catch (Exception e) {
            log.debug("Couldn't backfill consultant_applications.deleted: {}", e.getMessage());
        }

        // Build L — backfill invite_sent_at = created_at for legacy rows
        // so the 15-day expiry sweep can reason about them. Idempotent.
        try {
            int backfilled = jdbcTemplate.update(
                    "UPDATE consultant_applications SET invite_sent_at = created_at "
                            + "WHERE invite_sent_at IS NULL");
            if (backfilled > 0) {
                log.info("Backfilled consultant_applications.invite_sent_at=created_at on {} row(s)",
                        backfilled);
            }
        } catch (Exception e) {
            log.debug("Couldn't backfill consultant_applications.invite_sent_at: {}", e.getMessage());
        }

        // Build M — backfill phase=1 for any legacy row where Hibernate
        // added the column before the DataSeeder DEFAULT could land.
        // Idempotent.
        try {
            int backfilled = jdbcTemplate.update(
                    "UPDATE consultant_applications SET phase = 1 WHERE phase IS NULL");
            if (backfilled > 0) {
                log.info("Backfilled consultant_applications.phase=1 on {} row(s)", backfilled);
            }
        } catch (Exception e) {
            log.debug("Couldn't backfill consultant_applications.phase: {}", e.getMessage());
        }

        // Indexes backing the Phase B/C list filters (owner-scoped +
        // soft-delete). Postgres-first; CREATE INDEX IF NOT EXISTS is a
        // no-op on reruns. Wrapped so an unsupported dialect (MySQL dev)
        // logs at debug rather than crashing startup. Mirrors the
        // idx_users_participant_id pattern above.
        String[][] indexes = {
                {"idx_consultant_owner_deleted",
                        "consultant_applications(owner_erm_id, deleted)"},
                {"idx_consultant_deleted",
                        "consultant_applications(deleted)"},
        };
        for (String[] idx : indexes) {
            try {
                jdbcTemplate.execute(
                        "CREATE INDEX IF NOT EXISTS " + idx[0] + " ON " + idx[1]);
                log.info("Ensured index {}", idx[0]);
            } catch (Exception e) {
                log.debug("Couldn't create index {} (likely already present or "
                        + "unsupported dialect): {}", idx[0], e.getMessage());
            }
        }

        // Build K3 — drop stale Hibernate-generated enum CHECK constraints.
        // When an @Enumerated(STRING) column's table is first created,
        // Hibernate bakes a CHECK (col IN (<values-at-creation>)). ddl-auto=
        // update NEVER updates it, so once an enum gains values, inserts with
        // the new values are rejected (e.g. agreement_user.role only allowed
        // {SUPER_ADMIN, ERM} until MANAGER + ACCOUNTS were added, breaking
        // Manager/Accounts user creation). The columns are plain VARCHARs;
        // valid values are enforced in the application layer. Idempotent.
        String[][] staleEnumChecks = {
                {"agreement_user", "agreement_user_role_check"},
        };
        for (String[] c : staleEnumChecks) {
            try {
                jdbcTemplate.execute(
                        "ALTER TABLE " + c[0] + " DROP CONSTRAINT IF EXISTS " + c[1]);
                log.info("Ensured stale enum check {} is dropped", c[1]);
            } catch (Exception e) {
                log.debug("Couldn't drop constraint {} (likely already gone or "
                        + "unsupported dialect): {}", c[1], e.getMessage());
            }
        }
    }

    /**
     * Portal-phase migration for {@code consultant_verification}. Adds
     * the {@code email} column (the new identity key) idempotently and
     * relaxes the legacy {@code application_id NOT NULL} constraint so
     * portal rows can leave it null. Both wrapped so an already-applied
     * state or unsupported dialect no-ops at debug rather than crashing
     * boot. Mirrors the same idempotent ALTER pattern used elsewhere
     * in this file.
     */
    private void addConsultantVerificationPortalColumnsIfMissing() {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE consultant_verification "
                            + "ADD COLUMN IF NOT EXISTS email VARCHAR(255)");
            log.info("Ensured consultant_verification.email exists");
        } catch (Exception e) {
            log.debug("Couldn't add consultant_verification.email "
                    + "(likely already present or unsupported dialect): {}",
                    e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE consultant_verification "
                            + "ALTER COLUMN application_id DROP NOT NULL");
            log.info("Relaxed NOT NULL on consultant_verification.application_id");
        } catch (Exception e) {
            log.debug("Couldn't relax consultant_verification.application_id NOT NULL "
                    + "(likely already nullable or unsupported dialect): {}",
                    e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_consultant_verif_email "
                            + "ON consultant_verification(email)");
            log.info("Ensured index idx_consultant_verif_email");
        } catch (Exception e) {
            log.debug("Couldn't create idx_consultant_verif_email: {}", e.getMessage());
        }
    }

    /**
     * Bootstraps the single consultant-agreement-console SUPER_ADMIN
     * from {@code AGREEMENT_SUPER_ADMIN_EMAIL / _PASSWORD / _NAME}.
     *
     * Idempotent: if a user with that email already exists it is left
     * untouched (password is NOT re-hashed / overwritten) and returned.
     * Skips with a warning if the email/password env vars are blank.
     * Never logs the password.
     *
     * @return the super-admin row (existing or freshly created), or null
     *         if the bootstrap was skipped.
     */
    private AgreementUser ensureSuperAdmin() {
        if (superAdminEmail == null || superAdminEmail.isBlank()
                || superAdminPassword == null || superAdminPassword.isBlank()) {
            log.warn("Super-admin bootstrap skipped: AGREEMENT_SUPER_ADMIN_EMAIL / "
                    + "_PASSWORD not set.");
            return null;
        }

        String email = superAdminEmail.trim();
        AgreementUser existing = agreementUserRepository.findByEmailIgnoreCase(email).orElse(null);
        if (existing != null) {
            log.info("Super-admin ensured: {}", existing.getEmail());
            return existing;
        }

        String name = (superAdminName == null || superAdminName.isBlank())
                ? "Super Admin"
                : superAdminName.trim();
        AgreementUser created = agreementUserRepository.save(AgreementUser.builder()
                .email(email.toLowerCase())
                .passwordHash(passwordEncoder.encode(superAdminPassword))
                .fullName(name)
                .title("Administrator")
                .role(AgreementUserRole.SUPER_ADMIN)
                .active(true)
                .build());
        log.info("Super-admin ensured: {}", created.getEmail());
        return created;
    }

    /**
     * Assigns pre-existing consultant applications (those with a NULL
     * owner) to the super-admin so they remain visible after ownership
     * stamping ships. Idempotent -- only touches NULLs, so reruns and
     * applications created post-deploy (already stamped) are no-ops.
     * Runs only when the super-admin exists.
     */
    private void backfillConsultantApplicationOwner(AgreementUser superAdmin) {
        if (superAdmin == null || superAdmin.getId() == null) {
            log.debug("Owner backfill skipped: no super-admin to assign to.");
            return;
        }
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE consultant_applications SET owner_erm_id = ? "
                            + "WHERE owner_erm_id IS NULL",
                    superAdmin.getId());
            if (updated > 0) {
                log.info("Backfilled owner_erm_id on {} pre-existing application(s) "
                        + "to the super-admin.", updated);
            }
        } catch (Exception e) {
            log.debug("Couldn't backfill consultant_applications.owner_erm_id "
                    + "(likely column not yet present): {}", e.getMessage());
        }
    }

    /**
     * Relaxes legacy NOT NULL constraints on the {@code questions}
     * table's old fixed-4-option columns ({@code option_a..d},
     * {@code correct_answer}). The new normalized {@link
     * com.spire.backend.entity.QuizOption} model leaves these
     * columns out of new inserts, but on databases seeded under
     * the old schema each insert throws a NOT NULL violation that
     * Spring surfaces as a generic 409 "Data conflict" — blocking
     * instructors from adding new questions.
     *
     * Probes Postgres first (prod), falls back to MySQL (dev).
     * Each ALTER is wrapped so a missing column / already-relaxed
     * column is a silent no-op rather than crashing app startup.
     */
    private void relaxLegacyQuestionColumns() {
        List<String> columns = List.of("option_a", "option_b", "option_c", "option_d", "correct_answer");

        // Postgres: ALTER COLUMN ... DROP NOT NULL is idempotent if the
        // column is already nullable (it errors only if the column is
        // missing — which the catch handles).
        boolean isPg = false;
        for (String col : columns) {
            try {
                jdbcTemplate.execute("ALTER TABLE questions ALTER COLUMN " + col + " DROP NOT NULL");
                isPg = true;
                log.info("Relaxed legacy NOT NULL on questions.{} (Postgres)", col);
            } catch (Exception ignored) {
                // Column missing, already nullable, or wrong dialect.
            }
        }
        if (isPg) return;

        // MySQL: re-declare the column as NULLable. Using TEXT for the
        // option_a..d columns and CHAR(1) for correct_answer matches
        // the original schema; MODIFY rewrites the column definition.
        for (String col : columns) {
            try {
                String def = "correct_answer".equals(col) ? "CHAR(1) NULL" : "TEXT NULL";
                jdbcTemplate.execute("ALTER TABLE questions MODIFY COLUMN " + col + " " + def);
                log.info("Relaxed legacy NOT NULL on questions.{} (MySQL)", col);
            } catch (Exception ignored) {
                // Column missing or already nullable.
            }
        }
    }

    /**
     * Drops any unique constraint on quiz_attempts that locks the
     * (quiz_id, user_id) pair. Pre-migration the entity declared
     * `@UniqueConstraint(columnNames = {"quiz_id", "user_id"})`,
     * which forbade retries — incompatible with the new multi-attempt
     * quiz model. ddl-auto=update doesn't drop constraints
     * automatically.
     *
     * Probes Postgres first (prod), falls back to MySQL (dev). Each
     * branch is wrapped so a "wrong DB" / "no constraint" path is a
     * silent no-op rather than crashing app startup.
     */
    private void dropLegacyQuizAttemptUniqueConstraint() {
        // Postgres path — pg_constraint stores the auto-generated name.
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT conname FROM pg_constraint c " +
                            "JOIN pg_class t ON c.conrelid = t.oid " +
                            "WHERE t.relname = 'quiz_attempts' AND c.contype = 'u'",
                    String.class);
            for (String name : names) {
                jdbcTemplate.execute("ALTER TABLE quiz_attempts DROP CONSTRAINT \"" + name + "\"");
                log.info("Dropped legacy quiz_attempts unique constraint: {}", name);
            }
            return;
        } catch (Exception ignoredPg) {
            // Not Postgres or the catalog query is unavailable — try MySQL.
        }
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS " +
                            "WHERE TABLE_NAME = 'quiz_attempts' AND NON_UNIQUE = 0 " +
                            "AND INDEX_NAME != 'PRIMARY' " +
                            "GROUP BY INDEX_NAME",
                    String.class);
            for (String name : names) {
                jdbcTemplate.execute("ALTER TABLE quiz_attempts DROP INDEX `" + name + "`");
                log.info("Dropped legacy quiz_attempts unique index: {}", name);
            }
        } catch (Exception ignoredMysql) {
            log.debug("Couldn't probe quiz_attempts unique constraints — likely fresh DB or unsupported dialect.");
        }
    }

    /**
     * Bumps legacy course/service prices to the new realistic values on
     * already-seeded DBs (dev MySQL re-runs, Railway Postgres deploys).
     *
     * Only updates a row if its current price is still the OLD seed
     * value (₹0 for the formerly-free courses, ₹499 for the others,
     * ₹999 for LinkedIn). That preserves any price an admin has set
     * manually since the original seed.
     *
     * Map<slug, expectedOldPrice → newPrice>. Idempotent — a second run
     * is a no-op because the price will already match the new value.
     */
    /**
     * Backfill the six sample published courses on any DB that has
     * users but no courses. Runs independently of the main "first
     * seed" path so it repairs the historical bug where the user-count
     * guard at the top of {@link #run} skipped course seeding once a
     * single /enroll signup had landed.
     *
     * Instructors are looked up by email (arjun/priya/rahul@sageitco.com).
     * If any one is missing -- e.g. the seed user step never ran on
     * this deployment -- we log a warning and bail out rather than
     * leave the catalog with NULL instructor refs.
     */
    private void seedSampleCoursesIfMissing() {
        if (courseRepository.count() > 0) return;
        User arjun = userRepository.findByEmail("arjun@sageitco.com").orElse(null);
        User priya = userRepository.findByEmail("priya@sageitco.com").orElse(null);
        User rahul = userRepository.findByEmail("rahul@sageitco.com").orElse(null);
        if (arjun == null || priya == null || rahul == null) {
            log.warn("seedSampleCoursesIfMissing: instructor users missing -- "
                    + "skipping catalog seed. (arjun={}, priya={}, rahul={})",
                    arjun != null, priya != null, rahul != null);
            return;
        }

        courseRepository.save(Course.builder()
                .title("Full-Stack Web Development")
                .slug("full-stack-web-development")
                .description("Master modern web development from front to back. Learn HTML, CSS, JavaScript, React, Node.js, databases, and deployment in one comprehensive course.")
                .shortDescription("Build complete web applications from scratch")
                .level(Course.Level.INTERMEDIATE)
                .price(new BigDecimal("4999.00"))
                .isFree(false)
                .durationHours(42.5)
                .instructor(arjun)
                .lessonsCount(5)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Web Development")
                .tags("html,css,javascript,react,nodejs,mongodb")
                .isPublished(true)
                .build());

        courseRepository.save(Course.builder()
                .title("React Mastery")
                .slug("react-mastery")
                .description("Take your React skills to the next level. Advanced patterns, performance optimization, state management, and real-world project architecture.")
                .shortDescription("Advanced React patterns and best practices")
                .level(Course.Level.ADVANCED)
                .price(new BigDecimal("3499.00"))
                .isFree(false)
                .durationHours(28.0)
                .instructor(arjun)
                .lessonsCount(5)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Frontend")
                .tags("react,hooks,redux,nextjs,typescript")
                .isPublished(true)
                .build());

        courseRepository.save(Course.builder()
                .title("Python for Data Science")
                .slug("python-for-data-science")
                .description("Learn Python programming and data science from scratch. Covers NumPy, Pandas, Matplotlib, Scikit-learn, and real-world data analysis projects.")
                .shortDescription("Data analysis and ML with Python")
                .level(Course.Level.BEGINNER)
                .price(new BigDecimal("3999.00"))
                .isFree(false)
                .durationHours(35.0)
                .instructor(priya)
                .lessonsCount(5)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Data Science")
                .tags("python,numpy,pandas,matplotlib,scikit-learn,ml")
                .isPublished(true)
                .build());

        courseRepository.save(Course.builder()
                .title("Cloud Architecture with AWS")
                .slug("cloud-architecture-with-aws")
                .description("Design and deploy scalable cloud solutions on AWS. Covers EC2, S3, Lambda, DynamoDB, CloudFormation, and architecture best practices.")
                .shortDescription("Build scalable cloud solutions on AWS")
                .level(Course.Level.ADVANCED)
                .price(new BigDecimal("5499.00"))
                .isFree(false)
                .durationHours(32.0)
                .instructor(rahul)
                .lessonsCount(4)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Cloud")
                .tags("aws,ec2,s3,lambda,dynamodb,cloudformation")
                .isPublished(true)
                .build());

        courseRepository.save(Course.builder()
                .title("UI/UX Design Fundamentals")
                .slug("ui-ux-design-fundamentals")
                .description("Learn the principles of great user interface and user experience design. Covers Figma, design systems, wireframing, prototyping, and usability testing.")
                .shortDescription("Design beautiful and usable interfaces")
                .level(Course.Level.BEGINNER)
                .price(new BigDecimal("2999.00"))
                .isFree(false)
                .durationHours(20.0)
                .instructor(priya)
                .lessonsCount(4)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Design")
                .tags("figma,ui,ux,wireframing,prototyping,design-systems")
                .isPublished(true)
                .build());

        courseRepository.save(Course.builder()
                .title("Mobile App Development with React Native")
                .slug("mobile-app-development-with-react-native")
                .description("Build cross-platform mobile apps with React Native. Covers navigation, state management, native modules, animations, and app store deployment.")
                .shortDescription("Cross-platform mobile apps with React Native")
                .level(Course.Level.INTERMEDIATE)
                .price(new BigDecimal("3999.00"))
                .isFree(false)
                .durationHours(30.0)
                .instructor(rahul)
                .lessonsCount(5)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Mobile")
                .tags("react-native,mobile,ios,android,javascript")
                .isPublished(true)
                .build());

        log.info("seedSampleCoursesIfMissing: seeded 6 published courses.");
    }

    private void backfillCoursePrices() {
        record PriceMigration(String slug, BigDecimal expectedOld, BigDecimal newPrice, boolean wasFree) {}
        List<PriceMigration> migrations = List.of(
                new PriceMigration("full-stack-web-development",         BigDecimal.ZERO,                new BigDecimal("4999.00"), true),
                new PriceMigration("react-mastery",                      new BigDecimal("499.00"),       new BigDecimal("3499.00"), false),
                new PriceMigration("python-for-data-science",            BigDecimal.ZERO,                new BigDecimal("3999.00"), true),
                new PriceMigration("cloud-architecture-with-aws",        new BigDecimal("499.00"),       new BigDecimal("5499.00"), false),
                new PriceMigration("ui-ux-design-fundamentals",          new BigDecimal("499.00"),       new BigDecimal("2999.00"), false),
                new PriceMigration("mobile-app-development-with-react-native", new BigDecimal("499.00"), new BigDecimal("3999.00"), false),
                new PriceMigration("linkedin-profile-optimization",      new BigDecimal("999.00"),       new BigDecimal("1499.00"), false)
        );

        int updated = 0;
        for (PriceMigration m : migrations) {
            var opt = courseRepository.findBySlug(m.slug());
            if (opt.isEmpty()) continue;
            Course c = opt.get();
            BigDecimal current = c.getPrice() != null ? c.getPrice() : BigDecimal.ZERO;
            // compareTo (not equals) — same numeric value, different scale.
            if (current.compareTo(m.expectedOld()) == 0) {
                c.setPrice(m.newPrice());
                if (m.wasFree()) c.setIsFree(false);
                courseRepository.save(c);
                updated++;
                log.info("Backfilled price for {}: ₹{} → ₹{}", m.slug(), current, m.newPrice());
            }
        }
        if (updated > 0) log.info("Course price backfill complete — {} row(s) updated.", updated);
    }

    /**
     * One-shot wipe of the fabricated rating + enrolled-count seed data.
     *
     * Strategy: for any course whose ratingsCount > 0 (we never created
     * a real review system, so any value > 0 must be seed theatre), and
     * for any course whose enrolledCount is bigger than the actual row
     * count in the enrollments table, reset the display values to the
     * truth. This preserves any *real* enrollment that has happened
     * since deploy and fixes the 12,450-style fake numbers in one pass.
     *
     * Idempotent — a second run is a no-op because conditions no longer
     * match once the row has been corrected.
     */
    private void backfillFakeStats() {
        int updated = 0;
        for (Course c : courseRepository.findAll()) {
            boolean changed = false;

            int actualEnrollments = (int) enrollmentRepository.countByCourseId(c.getId());
            int displayed = c.getEnrolledCount() != null ? c.getEnrolledCount() : 0;
            // If the displayed count is bigger than actual rows, it's
            // padded — reset to the real count. (Equal or less is fine.)
            if (displayed > actualEnrollments) {
                c.setEnrolledCount(actualEnrollments);
                changed = true;
            }

            // No real review system → any non-zero rating/ratingsCount
            // is fake. Wipe both to zero.
            if (c.getRatingsCount() != null && c.getRatingsCount() > 0) {
                c.setRating(0.0);
                c.setRatingsCount(0);
                changed = true;
            } else if (c.getRating() != null && c.getRating() > 0.0) {
                c.setRating(0.0);
                changed = true;
            }

            if (changed) {
                courseRepository.save(c);
                updated++;
                log.info("Wiped fake stats on course {}", c.getSlug());
            }
        }
        if (updated > 0) log.info("Fake-stats wipe complete — {} row(s) updated.", updated);
    }

    private void seedService(User trainer, String slug, String title, String shortDescription,
                             String description, int priceRupees, List<SvcModule> modules) {
        if (courseRepository.findBySlug(slug).isPresent()) {
            return; // already seeded
        }

        int totalLessons = modules.stream().mapToInt(m -> m.lessons().size()).sum();
        int totalMinutes = modules.stream()
                .flatMap(m -> m.lessons().stream())
                .mapToInt(SvcLesson::durationMinutes)
                .sum();

        Course service = courseRepository.save(Course.builder()
                .title(title)
                .slug(slug)
                .description(description)
                .shortDescription(shortDescription)
                .level(Course.Level.BEGINNER)
                .price(BigDecimal.valueOf(priceRupees))
                .isFree(priceRupees <= 0)
                .durationHours(Math.round(totalMinutes / 60.0 * 10.0) / 10.0)
                .type("SERVICE")
                .trainer(trainer)
                .instructor(trainer)   // see note in seedServicesAndTrainer
                .lessonsCount(totalLessons)
                .enrolledCount(0)
                .rating(0.0)
                .ratingsCount(0)
                .category("Career Services")
                .isPublished(true)
                .build());

        int lessonOrder = 1;
        for (int mIdx = 0; mIdx < modules.size(); mIdx++) {
            SvcModule mod = modules.get(mIdx);
            Module module = moduleRepository.save(Module.builder()
                    .course(service)
                    .title(mod.title())
                    .description(mod.description())
                    .orderIndex(mIdx)
                    .build());
            for (SvcLesson l : mod.lessons()) {
                boolean isFirstLesson = (lessonOrder == 1);
                lessonRepository.save(Lesson.builder()
                        .course(service)
                        .module(module)
                        .title(l.title())
                        .orderIndex(lessonOrder)
                        .durationMinutes(l.durationMinutes())
                        .isFree(isFirstLesson) // first lesson is a free preview
                        .build());
                lessonOrder++;
            }
        }

        log.info("Seeded service: {} ({} lessons, ~{}h)", slug, totalLessons,
                Math.round(totalMinutes / 60.0 * 10.0) / 10.0);
    }

    private record SvcLesson(String title, int durationMinutes) {}
    private record SvcModule(String title, String description, List<SvcLesson> lessons) {}

    private void seedEnrollment(User user, Course course) {
        if (!enrollmentRepository.existsByUserIdAndCourseId(user.getId(), course.getId())) {
            enrollmentRepository.save(Enrollment.builder()
                    .user(user)
                    .course(course)
                    .build());
            course.setEnrolledCount(course.getEnrolledCount() + 1);
            courseRepository.save(course);
        }
    }

    private void seedLessons(Course course, List<String[]> lessonData) {
        for (int i = 0; i < lessonData.size(); i++) {
            String[] data = lessonData.get(i);
            lessonRepository.save(Lesson.builder()
                    .course(course)
                    .title(data[0])
                    .description(data[1])
                    .orderIndex(i + 1)
                    .durationMinutes(Integer.parseInt(data[2]))
                    .isFree(i == 0)
                    .build());
        }
    }

    private void seedModules(Course course, List<ModuleSpec> specs) {
        List<Lesson> courseLessons = lessonRepository.findByCourseIdOrderByOrderIndex(course.getId());
        for (int mIdx = 0; mIdx < specs.size(); mIdx++) {
            ModuleSpec spec = specs.get(mIdx);
            Module module = moduleRepository.save(Module.builder()
                    .course(course)
                    .title(spec.title())
                    .description(spec.description())
                    .orderIndex(mIdx)
                    .build());
            for (int lessonOrderIndex : spec.lessonOrderIndices()) {
                for (Lesson lesson : courseLessons) {
                    if (lesson.getOrderIndex() == lessonOrderIndex) {
                        lesson.setModule(module);
                        lessonRepository.save(lesson);
                        break;
                    }
                }
            }
        }
    }

    private record ModuleSpec(String title, String description, List<Integer> lessonOrderIndices) {}
}
