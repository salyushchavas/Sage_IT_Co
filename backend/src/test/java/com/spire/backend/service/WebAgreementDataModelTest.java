package com.spire.backend.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.entity.WebAgreementApproval;
import com.spire.backend.entity.WebAgreementErmAssignment;
import com.spire.backend.entity.WebAgreementEvent;
import com.spire.backend.entity.WebAgreementStaffTitle;
import com.spire.backend.entity.WebAgreementVersion;
import com.spire.backend.repository.WebAgreementApprovalRepository;
import com.spire.backend.repository.WebAgreementErmAssignmentRepository;
import com.spire.backend.repository.WebAgreementEventRepository;
import com.spire.backend.repository.WebAgreementRepository;
import com.spire.backend.repository.WebAgreementStaffTitleRepository;
import com.spire.backend.repository.WebAgreementVersionRepository;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The four approval-chain tables are additive and safe on both databases:
 * explicit index and constraint names that are new to the schema (Postgres
 * names are schema-wide, so a copied console name would fail on live), no
 * column definition but TEXT, and DDL that Hibernate generates for MySQL
 * and Postgres alike. Every repository finder parses against the entities.
 * All of it runs offline (no database).
 */
class WebAgreementDataModelTest {

    private static final List<Class<?>> NEW_ENTITIES = List.of(
            WebAgreementApproval.class,
            WebAgreementVersion.class,
            WebAgreementErmAssignment.class,
            WebAgreementStaffTitle.class);

    /** The ten names the spec lists (§K.1). */
    private static final Set<String> EXPECTED_NAMES = Set.of(
            "uq_web_agreement_team_link",
            "idx_web_agreement_team_erm",
            "idx_web_agreement_team_approver",
            "uq_web_agreement_version_number",
            "idx_web_agreement_version_agreement",
            "idx_web_agreement_approval_agreement",
            "idx_web_agreement_approval_round",
            "idx_web_agreement_approval_status",
            "idx_web_agreement_approval_approver",
            "uq_web_agreement_staff_title_user");

    /** Names the console's tables already own on live Postgres. */
    private static final List<String> CONSOLE_PREFIXES = List.of(
            "uq_erm_assignment", "idx_erm_assignment", "idx_cav_", "idx_agr_approval");

    @Test
    void tablesUseOnlyTheirOwnExplicitNames() {
        Set<String> names = new TreeSet<>();
        Set<String> tables = new TreeSet<>();
        for (Class<?> c : NEW_ENTITIES) {
            Table t = c.getAnnotation(Table.class);
            assertNotNull(t, c.getSimpleName());
            tables.add(t.name());
            for (Index i : t.indexes()) {
                assertFalse(i.name().isBlank(), c.getSimpleName() + " has an unnamed index");
                names.add(i.name());
            }
            for (UniqueConstraint u : t.uniqueConstraints()) {
                assertFalse(u.name().isBlank(), c.getSimpleName() + " has an unnamed unique constraint");
                names.add(u.name());
            }
        }
        assertEquals(Set.of("web_agreement_approvals", "web_agreement_versions",
                "web_agreement_erm_assignments", "web_agreement_staff_titles"), tables);
        assertEquals(EXPECTED_NAMES, names);
        for (String n : names) {
            assertTrue(n.length() <= 63, n + " is longer than Postgres allows");
            for (String office : CONSOLE_PREFIXES) {
                assertFalse(n.startsWith(office), n + " reuses a console name");
            }
        }
    }

    @Test
    void newNamesAreUnusedByEveryOtherEntity() throws Exception {
        Path pkg = Path.of(WebAgreement.class.getResource("").toURI());
        Set<String> others = new TreeSet<>();
        try (Stream<Path> files = Files.list(pkg)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String simple = p.getFileName().toString().replace(".class", "");
                if (simple.contains("$")) continue;
                Class<?> c = Class.forName(WebAgreement.class.getPackageName() + "." + simple);
                if (NEW_ENTITIES.contains(c)) continue;
                Table t = c.getAnnotation(Table.class);
                if (t == null) continue;
                for (Index i : t.indexes()) others.add(i.name());
                for (UniqueConstraint u : t.uniqueConstraints()) others.add(u.name());
            }
        }
        // Sanity: the scan saw the existing tables, the console's included.
        assertTrue(others.contains("idx_web_agreement_status"), others.toString());
        assertTrue(others.contains("uq_erm_assignment"), others.toString());
        for (String n : EXPECTED_NAMES) {
            assertFalse(others.contains(n), n + " is already used by another table");
        }
    }

    @Test
    void onlyTextColumnDefinitionsAndNoInlineUniqueKeys() {
        for (Class<?> c : NEW_ENTITIES) {
            for (Field f : c.getDeclaredFields()) {
                Column col = f.getAnnotation(Column.class);
                if (col == null) continue;
                assertTrue(col.columnDefinition().isEmpty() || col.columnDefinition().equals("TEXT"),
                        c.getSimpleName() + "." + f.getName() + " uses columnDefinition " + col.columnDefinition());
                // An inline unique=true gets a generated name; every key is named on @Table.
                assertFalse(col.unique(), c.getSimpleName() + "." + f.getName() + " has an unnamed unique key");
            }
        }
    }

    @Test
    void storageKeysAndUnroutingHistoryNeverReachTheBrowser() throws Exception {
        assertNotNull(WebAgreementVersion.class.getDeclaredField("s3Key").getAnnotation(JsonIgnore.class));
        for (String f : List.of("unroutedFromUserId", "unroutedFromName", "unroutedAt")) {
            assertNotNull(WebAgreementApproval.class.getDeclaredField(f).getAnnotation(JsonIgnore.class), f);
        }
        // The routing itself (approverUserId, approverName) is shown on badges and the board.
        assertNull(WebAgreementApproval.class.getDeclaredField("approverName").getAnnotation(JsonIgnore.class));
    }

    @Test
    void approvalDefaultsMatchTheConsole() {
        WebAgreementApproval g = WebAgreementApproval.builder()
                .agreementId(1L).role(WebAgreementApproval.ApproverRole.MANAGER.name()).build();
        assertEquals("PENDING", g.getStatus());
        assertEquals(1, g.getPhase());
        assertEquals(1, g.getRound());
    }

    @Test
    void listSummaryFieldsAreNeverPersisted() throws Exception {
        for (String f : List.of("managerStatus", "accountsStatus", "sentForApprovalAt")) {
            assertNotNull(WebAgreement.class.getDeclaredField(f).getAnnotation(Transient.class), f);
        }
    }

    @Test
    void eventTypesCoverTheApprovalChain() {
        Set<String> names = new TreeSet<>();
        for (WebAgreementEvent.EventType t : WebAgreementEvent.EventType.values()) names.add(t.name());
        assertTrue(names.containsAll(List.of(
                "SENT_FOR_APPROVAL", "APPROVAL_APPROVED", "APPROVAL_REVISION_REQUESTED",
                "APPROVED_AND_SIGNED", "PDF_GENERATED", "ADVANCED_TO_PHASE_2",
                "ERM_SIGNATURE_REVOKED", "APPLICATION_ARCHIVED")));
        // The existing names keep their places (they are stored as strings, but nothing is renamed).
        assertTrue(names.containsAll(List.of("VERIFIED", "INVITE_RESENT", "CANCELLED")));
    }

    /**
     * Spring Data parses every derived finder and validates every @Query
     * when it builds a repository; that normally happens only at boot. Built
     * here against a SessionFactory that never connects.
     */
    @Test
    void everyRepositoryMethodParses() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            NEW_ENTITIES.forEach(sources::addAnnotatedClass);
            sources.addAnnotatedClass(WebAgreement.class);
            sources.addAnnotatedClass(WebAgreementEvent.class);
            try (SessionFactory sf = sources.buildMetadata().buildSessionFactory();
                 EntityManager em = sf.createEntityManager()) {
                JpaRepositoryFactory factory = new JpaRepositoryFactory(em);
                for (Class<?> repo : List.of(
                        WebAgreementApprovalRepository.class,
                        WebAgreementVersionRepository.class,
                        WebAgreementErmAssignmentRepository.class,
                        WebAgreementStaffTitleRepository.class,
                        WebAgreementRepository.class,
                        WebAgreementEventRepository.class)) {
                    assertNotNull(factory.getRepository(repo), repo.getSimpleName());
                }
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void postgresDdlIsValidAndCarriesEveryName(@TempDir Path dir) throws Exception {
        String ddl = ddl(dir, "org.hibernate.dialect.PostgreSQLDialect");
        assertCommon(ddl);
        assertFalse(ddl.contains("longtext"), ddl);
        assertFalse(ddl.contains("datetime"), ddl);
    }

    @Test
    void mysqlDdlIsValidAndCarriesEveryName(@TempDir Path dir) throws Exception {
        String ddl = ddl(dir, "org.hibernate.dialect.MySQLDialect");
        assertCommon(ddl);
        assertFalse(ddl.contains("longtext"), ddl);
    }

    private static void assertCommon(String ddl) {
        for (String table : List.of("web_agreement_approvals", "web_agreement_versions",
                "web_agreement_erm_assignments", "web_agreement_staff_titles")) {
            assertTrue(ddl.contains("create table " + table), table + " missing:\n" + ddl);
        }
        for (String n : EXPECTED_NAMES) {
            assertTrue(ddl.contains(n), n + " missing:\n" + ddl);
        }
        // The round column is round_no (ROUND is an SQL function name).
        assertTrue(ddl.contains("round_no integer not null"), ddl);
        assertFalse(ddl.matches("(?s).*[ ,(]round [a-z].*"), ddl);
        // Plain strings, not enum columns or CHECK lists ddl-auto=update could never widen.
        assertFalse(ddl.contains("enum("), ddl);
        assertFalse(ddl.contains(" check "), ddl);
        // The version key and notes are TEXT.
        assertTrue(ddl.contains("s3_key text not null"), ddl);
        assertTrue(ddl.contains("note text"), ddl);
    }

    /** The CREATE script Hibernate would run for the four tables, lower-cased, without connecting. */
    private static String ddl(Path dir, String dialect) throws Exception {
        Path out = dir.resolve("create.sql");
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", dialect)
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target", out.toString())
                .applySetting("hibernate.hbm2ddl.delimiter", ";")
                .applySetting("hibernate.format_sql", "false")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            NEW_ENTITIES.forEach(sources::addAnnotatedClass);
            Metadata metadata = sources.buildMetadata();
            Map<String, Object> settings = new HashMap<>(
                    registry.requireService(ConfigurationService.class).getSettings());
            SchemaManagementToolCoordinator.process(metadata, registry, settings, null);
            String sql = Files.readString(out).toLowerCase(Locale.ROOT);
            assertFalse(sql.isBlank());
            return sql;
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
