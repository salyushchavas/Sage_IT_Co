package com.spire.backend.service;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import com.spire.backend.repository.WebAgreementStaffTitleRepository;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.repository.Repository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The isolation guard (spec rule 2): the website's agreement code never
 * holds, names or queries the console's code, tables or mail.
 *
 * Guarded: every class named WebAgreement*, every class (any name, any
 * package) whose compiled code names a WebAgreement* type, and the changed
 * shared classes in {@link #SHARED}. The console's code is found by name
 * (Consultant* / Agreement* controllers, filters, entities, repositories,
 * services and DTOs, less the website's own look-alikes), mail is Spring's
 * and Jakarta's mail APIs plus the two email services.
 *
 * Four checks: no field or constructor parameter reaches console or mail
 * code, directly or through the Spring beans it holds; no compiled code
 * (nested, anonymous and lambda code included) names it; no string in the
 * compiled code (queries included) names a console table or setting or
 * queries a console entity; and the new MANAGER / ACCOUNTS accounts never
 * reach the email service. {@link #ALLOWED}, {@link #ALLOWED_MEMBERS} and
 * {@link #WEBSITE_FEATURES} are the whole allowlist.
 */
class WebAgreementIsolationGuardTest {

    private static final String BASE = "com.spire.backend.";

    /** Changed shared classes, guarded whether or not they name a WebAgreement* type. */
    private static final List<String> SHARED = List.of(
            "service.AdminService",
            "controller.AdminController",
            "service.StaffOnboardingService",
            "service.MasterAgreementService",
            "service.WebAgreementParticipantService",
            "security.ConsoleTokenBoundaryFilter");

    /** Where the console's code lives. */
    private static final Set<String> CONSOLE_PACKAGES = Set.of(
            "controller", "security", "entity", "repository", "service", "dto");

    /**
     * The website's own classes whose names look like the console's: the
     * Terms of Service flow and the agreement requests (website tables
     * agreement_records and agreement_requests).
     */
    private static final Set<String> WEBSITE_LOOKALIKES = Set.of(
            "controller.AgreementController",
            "service.AgreementService",
            "service.AgreementPdfService",
            "service.AgreementQueueService",
            "security.AgreementGateFilter",
            "entity.AgreementAcceptance",
            "repository.AgreementAcceptanceRepository",
            "entity.AgreementRequest",
            "repository.AgreementRequestRepository");

    /** Mail: these packages and the website's two email services. */
    private static final List<String> MAIL_PACKAGES = List.of("org.springframework.mail.", "jakarta.mail.");
    private static final Set<String> MAIL_CLASSES = Set.of("service.EmailTemplateService", "service.EmailService");

    /**
     * The only console or mail code a guarded class may hold or name, and
     * which classes may. No console class at all: the engine draws the
     * WebAgreement itself and the clauses are the website's own
     * WebAgreementContent.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            // The website staff login email (never for MANAGER / ACCOUNTS, see the last test).
            "service.EmailTemplateService", Set.of("service.StaffOnboardingService"));

    /**
     * Website features AdminController held before the agreement work (the
     * admin pages' applications, documents, onboarding, coaches and
     * operations exceptions): their mail is the website's own, so the walk
     * does not go through them from that class. From any other class it does.
     */
    private static final Map<String, Set<String>> WEBSITE_FEATURES = Map.of(
            "controller.AdminController", Set.of(
                    "service.ParticipantApplicationService",
                    "service.OperationsExceptionService",
                    "service.DocumentService",
                    "service.ProfileCompletionService",
                    "service.OnboardingService",
                    "service.CoachAssignmentService"));

    /** An allowed class that may only be called through these members. */
    private static final Map<String, Set<String>> ALLOWED_MEMBERS = Map.of(
            "service.EmailTemplateService", Set.of("sendStaffLoginEmail"));

    /** The console's own settings (application.properties), e.g. its agreement-erm.email address. */
    private static final List<String> CONSOLE_SETTINGS = List.of("agreement-erm.", "agreement.super-admin.");

    /** A string that reads as SQL or JPQL. */
    private static final Pattern QUERY = Pattern.compile("(?i)\\b(select|update|delete|insert)\\b");

    // ── What is guarded, what is the console's ───────────────────────

    @Test
    void theGuardedClassesAreFoundByNameAndByReference() throws Exception {
        Set<String> all = allClasses();
        Set<String> guarded = guarded();
        assertTrue(all.containsAll(SHARED), "every shared class is compiled");
        Set<String> byName = new TreeSet<>();
        Set<String> byReference = new TreeSet<>();
        for (String name : all) {
            if (simpleName(name).startsWith("WebAgreement")) byName.add(name);
            else if (namesAWebAgreementType(name)) byReference.add(name);
        }
        assertTrue(guarded.containsAll(byName) && guarded.containsAll(byReference));
        assertTrue(byName.size() > 30, "every WebAgreement* class: " + byName);
        // Found by what they name, whatever their name (SHARED only backs them up).
        assertTrue(byReference.containsAll(Set.of("service.AdminService", "service.StaffOnboardingService",
                "service.MasterAgreementService")), byReference.toString());

        Set<String> both = new TreeSet<>(guarded);
        both.retainAll(office());
        assertTrue(both.isEmpty(), "console code names the website agreement, or is guarded: " + both);
    }

    @Test
    void theConsolesCodeAndTablesAreFoundAndTheWebsitesLookAlikesAreNot() throws Exception {
        Set<String> office = office();
        assertTrue(office.containsAll(Set.of(
                "controller.AgreementAdminController", "controller.AgreementApproverController",
                "controller.AgreementErmAuthController", "controller.ConsultantApplicationController",
                "security.AgreementErmAuthFilter", "security.ConsultantRateLimiter", "security.AgreementAuthz",
                "security.AgreementOwnership",
                "entity.ConsultantApplication", "entity.ConsultantApplicationEvent", "entity.AgreementUser",
                "entity.AgreementApproval", "entity.AgreementErmAssignment", "entity.ConsultantAgreementVersion",
                "entity.ConsultantVerification",
                "repository.AgreementUserRepository", "repository.AgreementApprovalRepository",
                "repository.ConsultantApplicationRepository",
                "service.ConsultantApplicationService", "service.ConsultantVersionService",
                "service.AgreementDocumentService", "service.AgreementContentService",
                "service.AgreementAssignmentService", "service.ConsultantPdfService",
                "dto.AgreementContent")), office.toString());
        assertFalse(office.contains("dto.WebAgreementContent"), "the website's own clause shape");
        Set<String> all = allClasses();
        for (String lookalike : WEBSITE_LOOKALIKES) {
            assertTrue(all.contains(lookalike), "still a real class: " + lookalike);
            assertFalse(office.contains(lookalike), lookalike);
        }
        WEBSITE_FEATURES.forEach((holder, features) -> {
            assertTrue(all.contains(holder), holder);
            assertTrue(all.containsAll(features), features.toString());
        });
        for (String allowed : ALLOWED.keySet()) {
            assertTrue(office.contains(allowed) || MAIL_CLASSES.contains(allowed), "allowlisted, so denied otherwise: " + allowed);
        }

        Set<String> tables = officeTables(office);
        assertEquals(Set.of("consultant_applications", "consultant_application_events",
                "consultant_application_revisions", "consultant_agreement_version", "consultant_verification",
                "agreement_user", "agreement_approvals", "agreement_erm_assignments"), tables);
    }

    // ── Fields and constructors, through the beans they hold ─────────

    @Test
    void noGuardedClassHoldsConsoleOrMailCodeEvenThroughTheBeansItHolds() throws Exception {
        Set<String> office = office();
        List<String> violations = new ArrayList<>();
        Set<Class<?>> walked = new HashSet<>();
        for (String name : guarded()) {
            Set<Class<?>> visited = new HashSet<>();
            for (Class<?> k : withNested(load(name))) walk(k, k.getSimpleName(), office, visited, violations);
            walked.addAll(visited);
        }
        assertTrue(violations.isEmpty(), "console or mail code held:\n" + String.join("\n", violations));
        assertTrue(walked.size() > 40, "the beans behind the guarded classes are walked too: " + walked.size());
        assertTrue(dependencies(WebAgreementRenderer.class).values().stream()
                .anyMatch(types -> types.contains(WebAgreementDocumentEngine.class)), "the renderer holds our engine");

        // Not blind: the console's controller, and a website class that sends mail one hop down.
        List<String> console = new ArrayList<>();
        walk(load("controller.AgreementAdminController"), "AgreementAdminController", office, new HashSet<>(), console);
        assertTrue(console.stream().anyMatch(v -> v.contains("ConsultantApplicationService")), console.toString());
        List<String> mailOneHop = new ArrayList<>();
        walk(load("controller.AgreementController"), "AgreementController", office, new HashSet<>(), mailOneHop);
        assertTrue(mailOneHop.stream().anyMatch(v -> v.contains("AgreementService → ")
                && v.contains("EmailTemplateService")), mailOneHop.toString());
    }

    /**
     * Each dependency of {@code k}: console or mail code is a violation
     * unless allowlisted for k's class (then the walk stops there); any
     * other bean of this codebase is walked in turn, except k's class's
     * {@link #WEBSITE_FEATURES}.
     */
    private static void walk(Class<?> k, String path, Set<String> office, Set<Class<?>> visited, List<String> out)
            throws Exception {
        String holder = shortName(topLevel(k));
        for (Map.Entry<String, Set<Class<?>>> dep : dependencies(k).entrySet()) {
            for (Class<?> t : dep.getValue()) {
                String denied = deniedOwner(t.getName().replace('.', '/'), office);
                if (denied != null) {
                    if (!ALLOWED.getOrDefault(denied, Set.of()).contains(holder)) {
                        out.add(path + " (" + dep.getKey() + ") → " + t.getName());
                    }
                    continue;
                }
                if (!t.getName().startsWith(BASE)) continue;
                for (Class<?> bean : beansFor(t)) {
                    if (WEBSITE_FEATURES.getOrDefault(holder, Set.of()).contains(shortName(bean))) continue;
                    if (visited.add(bean)) walk(bean, path + " → " + bean.getSimpleName(), office, visited, out);
                }
            }
        }
    }

    /** A Spring bean type itself, or every bean class of this codebase behind an interface; else nothing. */
    private static List<Class<?>> beansFor(Class<?> t) throws Exception {
        if (isBean(t)) return List.of(t);
        if (!t.isInterface()) return List.of();
        List<Class<?>> out = new ArrayList<>();
        for (String name : allClasses()) {
            Class<?> c = load(name);
            if (c != t && t.isAssignableFrom(c) && isBean(c)) out.add(c);
        }
        return out;
    }

    private static boolean isBean(Class<?> c) {
        return AnnotatedElementUtils.hasAnnotation(c, Component.class) || Repository.class.isAssignableFrom(c);
    }

    // ── The compiled code ────────────────────────────────────────────

    @Test
    void noGuardedClassNamesConsoleOrMailCodeAnywhereInItsCompiledCode() throws Exception {
        Set<String> office = office();
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        Set<String> guarded = guarded();
        for (String name : guarded) {
            for (Map.Entry<String, ClassFileRefs> file : compiledFiles(name).entrySet()) {
                scanned++;
                violations.addAll(namedCode(name, file.getKey(), file.getValue(), office));
            }
        }
        assertTrue(scanned > guarded.size(), "nested and lambda code is scanned too");
        assertTrue(violations.isEmpty(), "console or mail code named:\n" + String.join("\n", violations));

        // Not blind: the console's controllers (read only) name and call the console's renderer and mail.
        Set<String> console = new TreeSet<>();
        for (String name : List.of("controller.ConsultantApplicationController", "controller.AgreementAdminController")) {
            for (Map.Entry<String, ClassFileRefs> file : compiledFiles(name).entrySet()) {
                console.addAll(namedCode(name, file.getKey(), file.getValue(), office));
            }
        }
        assertTrue(console.stream().anyMatch(v -> v.endsWith("AgreementDocumentService.renderPdfBytes")), console.toString());
        assertTrue(console.stream().anyMatch(v -> v.endsWith("service.EmailTemplateService")), console.toString());
        // A console entity or DTO named from our engine or clause code would be caught.
        ClassFileRefs leak = new ClassFileRefs(Set.of("com/spire/backend/entity/ConsultantApplication",
                "com/spire/backend/dto/AgreementContent$Block"), List.of(), List.of());
        assertEquals(2, namedCode("service.WebAgreementDocumentEngine", "leak", leak, office).size());
        assertEquals(2, namedCode("service.WebAgreementContentService", "leak", leak, office).size());
        // And it sees our own engine's use.
        Set<String> rendererUse = new TreeSet<>();
        String engine = WebAgreementDocumentEngine.class.getName().replace('.', '/');
        for (ClassFileRefs refs : compiledFiles("service.WebAgreementRenderer").values()) {
            for (String[] member : refs.members()) if (member[0].equals(engine)) rendererUse.add(member[1]);
        }
        assertTrue(rendererUse.contains("renderPdfBytes"), rendererUse.toString());
    }

    @Test
    void theStaffLoginEmailIsTheOnlyMailAnyGuardedClassCalls() throws Exception {
        Set<String> calls = new TreeSet<>();
        String mail = (BASE + "service.EmailTemplateService").replace('.', '/');
        for (String name : guarded()) {
            for (ClassFileRefs refs : compiledFiles(name).values()) {
                for (String[] member : refs.members()) if (member[0].equals(mail)) calls.add(name + "." + member[1]);
            }
        }
        assertEquals(Set.of("service.StaffOnboardingService.sendStaffLoginEmail"), calls);
    }

    /** The console or mail code one compiled file names, as "file → type" / "file → type.member". */
    private static List<String> namedCode(String holder, String file, ClassFileRefs refs, Set<String> office) {
        List<String> out = new ArrayList<>();
        for (String type : refs.types()) {
            String denied = deniedOwner(type, office);
            if (denied != null && !ALLOWED.getOrDefault(denied, Set.of()).contains(holder)) {
                out.add(file + " → " + type.replace('/', '.'));
            }
        }
        for (String[] member : refs.members()) {
            String denied = deniedOwner(member[0], office);
            if (denied == null) continue;
            Set<String> members = ALLOWED_MEMBERS.get(denied);
            boolean allowed = ALLOWED.getOrDefault(denied, Set.of()).contains(holder)
                    && (members == null || members.contains(member[1]));
            if (!allowed) out.add(file + " → " + member[0].replace('/', '.') + "." + member[1]);
        }
        return out;
    }

    // ── Tables and queries ───────────────────────────────────────────

    @Test
    void noGuardedClassNamesAConsoleTableOrSettingOrQueriesAConsoleEntity() throws Exception {
        Set<String> office = office();
        Set<String> tables = officeTables(office);
        Set<String> entities = new TreeSet<>();
        for (String name : office) if (load(name).isAnnotationPresent(Entity.class)) entities.add(simpleName(name));
        List<String> violations = new ArrayList<>();
        for (String name : guarded()) {
            for (Map.Entry<String, ClassFileRefs> file : compiledFiles(name).entrySet()) {
                violations.addAll(namedTables(file.getKey(), file.getValue(), tables, entities));
            }
        }
        assertTrue(violations.isEmpty(), "console tables or settings in website code:\n" + String.join("\n", violations));

        // Not blind: the console's repository (read only) queries its own entity, and the
        // website's own agreement tables never count.
        List<String> console = new ArrayList<>();
        for (Map.Entry<String, ClassFileRefs> file : compiledFiles("repository.AgreementApprovalRepository").entrySet()) {
            console.addAll(namedTables(file.getKey(), file.getValue(), tables, entities));
        }
        assertFalse(console.isEmpty());
        ClassFileRefs website = new ClassFileRefs(Set.of(), List.of(), List.of(
                "select count(*) from agreement_requests", "select * from agreement_records",
                "select * from agreement_acceptances", "select * from web_agreement_approvals"));
        assertTrue(namedTables("website", website, tables, entities).isEmpty());
        ClassFileRefs leak = new ClassFileRefs(Set.of(), List.of(), List.of("select count(*) from `agreement_user`"));
        assertFalse(namedTables("leak", leak, tables, entities).isEmpty());
        ClassFileRefs setting = new ClassFileRefs(Set.of(), List.of(), List.of("${agreement-erm.email}"));
        assertFalse(namedTables("setting", setting, tables, entities).isEmpty());
    }

    private static List<String> namedTables(String file, ClassFileRefs refs, Set<String> tables, Set<String> entities) {
        List<String> out = new ArrayList<>();
        for (String s : refs.strings()) {
            for (String t : tables) if (hasToken(s, t)) out.add(file + " → \"" + s + "\" names " + t);
            for (String key : CONSOLE_SETTINGS) if (s.contains(key)) out.add(file + " → \"" + s + "\" reads " + key);
            if (QUERY.matcher(s).find()) {
                for (String e : entities) if (hasToken(s, e)) out.add(file + " → \"" + s + "\" queries " + e);
            }
        }
        return out;
    }

    private static boolean hasToken(String s, String token) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(token) + "(?![A-Za-z0-9_])").matcher(s).find();
    }

    /** The console entities' tables, from their @Table names. */
    private static Set<String> officeTables(Set<String> office) throws Exception {
        Set<String> out = new TreeSet<>();
        for (String name : office) {
            Class<?> c = load(name);
            if (!c.isAnnotationPresent(Entity.class)) continue;
            Table table = c.getAnnotation(Table.class);
            assertNotNull(table, "every console entity names its table: " + name);
            out.add(table.name());
        }
        return out;
    }

    // ── Mail ─────────────────────────────────────────────────────────

    @Test
    void managersAndAccountsAreCreatedAndResetWithoutTheEmailService() {
        Map<Long, User> users = new HashMap<>();
        users.put(1L, User.builder().id(1L).fullName("Sys Admin").email("sys@sageitco.com")
                .role(Role.builder().name("SYSTEM_ADMIN").build()).isActive(true).build());
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(users.get((Long) inv.getArgument(0))));
        when(userRepo.existsByEmailIgnoreCase(anyString())).thenAnswer(inv -> users.values().stream()
                .anyMatch(u -> u.getEmail().equalsIgnoreCase(inv.getArgument(0))));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId((long) (100 + users.size()));
            users.put(u.getId(), u);
            return u;
        });
        RoleRepository roles = mock(RoleRepository.class);
        when(roles.findByName(anyString())).thenAnswer(inv -> Optional.of(Role.builder().name(inv.getArgument(0)).build()));
        WebAgreementStaffTitleRepository titleRepo = mock(WebAgreementStaffTitleRepository.class);
        when(titleRepo.findByUserId(anyLong())).thenReturn(Optional.empty());
        EmailTemplateService emails = mock(EmailTemplateService.class);
        StaffOnboardingService onboarding = new StaffOnboardingService(userRepo, roles, new BCryptPasswordEncoder(4),
                mock(RecordService.class), emails, new WebAgreementStaffTitleService(titleRepo));

        for (String role : List.of("MANAGER", "ACCOUNTS")) {
            StaffOnboardingService.Result created = onboarding.createStaff(1L, "Gate Keeper",
                    role.toLowerCase() + ".gate@sageitco.com", null, role, "Approver");
            assertFalse(created.emailSent(), role);
            assertNotNull(created.temporaryPassword(), role);
            StaffOnboardingService.Result reset = onboarding.sendNewLoginDetails(1L, created.user().getId());
            assertFalse(reset.emailSent(), role);
            assertNotNull(reset.temporaryPassword(), role);
        }
        verifyNoInteractions(emails);
    }

    // ── Discovery helpers ────────────────────────────────────────────

    private static Set<String> allClasses;

    /** Every compiled top-level class under com.spire.backend, as "package.Name". */
    private static synchronized Set<String> allClasses() throws Exception {
        if (allClasses != null) return allClasses;
        Path root = classesRoot();
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(n -> n.endsWith(".class") && !n.contains("$"))
                    .forEach(n -> found.add(n.substring(0, n.length() - ".class".length()).replace('/', '.')));
        }
        allClasses = Collections.unmodifiableSet(found);
        return allClasses;
    }

    /** Every WebAgreement* class, every class naming a WebAgreement* type, and {@link #SHARED}. */
    private static Set<String> guarded() throws Exception {
        Set<String> out = new TreeSet<>(SHARED);
        for (String name : allClasses()) {
            if (simpleName(name).startsWith("WebAgreement") || namesAWebAgreementType(name)) out.add(name);
        }
        return out;
    }

    private static boolean namesAWebAgreementType(String name) throws Exception {
        for (ClassFileRefs refs : compiledFiles(name).values()) {
            for (String type : refs.types()) {
                if (type.startsWith("com/spire/backend/")
                        && type.substring(type.lastIndexOf('/') + 1).startsWith("WebAgreement")) return true;
            }
        }
        return false;
    }

    /** The console's controllers, filters, entities, repositories, services and DTOs. */
    private static Set<String> office() throws Exception {
        Set<String> out = new TreeSet<>();
        for (String name : allClasses()) {
            int dot = name.lastIndexOf('.');
            String simple = name.substring(dot + 1);
            if (dot > 0 && CONSOLE_PACKAGES.contains(name.substring(0, dot))
                    && (simple.startsWith("Consultant") || simple.startsWith("Agreement"))
                    && !WEBSITE_LOOKALIKES.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * The console or mail class an internal type name ("a/b/C$D") belongs
     * to, as "package.Name" (a mail API type as its full name), or null.
     */
    private static String deniedOwner(String internal, Set<String> office) {
        String dotted = internal.replace('/', '.');
        for (String p : MAIL_PACKAGES) if (dotted.startsWith(p)) return dotted;
        if (!dotted.startsWith(BASE)) return null;
        String top = dotted.substring(BASE.length());
        int nested = top.indexOf('$');
        if (nested >= 0) top = top.substring(0, nested);
        return office.contains(top) || MAIL_CLASSES.contains(top) ? top : null;
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(BASE + name, false, WebAgreementIsolationGuardTest.class.getClassLoader());
    }

    private static Class<?> topLevel(Class<?> c) {
        while (c.getEnclosingClass() != null) c = c.getEnclosingClass();
        return c;
    }

    private static String shortName(Class<?> c) {
        return c.getName().startsWith(BASE) ? c.getName().substring(BASE.length()) : c.getName();
    }

    private static String simpleName(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('/')) + 1);
    }

    // ── Reflection helpers ───────────────────────────────────────────

    /** The class and every class declared inside it, at any depth. */
    private static List<Class<?>> withNested(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        out.add(c);
        for (Class<?> n : c.getDeclaredClasses()) out.addAll(withNested(n));
        return out;
    }

    /**
     * Each field and constructor parameter (up the superclass chain, within
     * this codebase) with every class its declared type mentions, generics
     * and arrays included.
     */
    private static Map<String, Set<Class<?>>> dependencies(Class<?> c) {
        Map<String, Set<Class<?>>> out = new TreeMap<>();
        for (Class<?> k = c; k != null && k.getName().startsWith(BASE); k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                Set<Class<?>> types = new LinkedHashSet<>();
                collect(f.getGenericType(), types);
                out.put("field " + k.getSimpleName() + "." + f.getName(), types);
            }
            for (Constructor<?> ctor : k.getDeclaredConstructors()) {
                Type[] params = ctor.getGenericParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    Set<Class<?>> types = new LinkedHashSet<>();
                    collect(params[i], types);
                    out.put("constructor " + k.getSimpleName() + " parameter " + i, types);
                }
            }
        }
        return out;
    }

    private static void collect(Type t, Set<Class<?>> out) {
        if (t instanceof Class<?> cls) {
            if (cls.isArray()) collect(cls.getComponentType(), out);
            else out.add(cls);
        } else if (t instanceof ParameterizedType p) {
            collect(p.getRawType(), out);
            for (Type a : p.getActualTypeArguments()) collect(a, out);
        } else if (t instanceof GenericArrayType g) {
            collect(g.getGenericComponentType(), out);
        } else if (t instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) collect(b, out);
            for (Type b : w.getLowerBounds()) collect(b, out);
        } else if (t instanceof TypeVariable<?> v) {
            for (Type b : v.getBounds()) collect(b, out);
        }
    }

    // ── Class-file helpers ───────────────────────────────────────────

    /**
     * What one compiled class file names: every type, every field / method
     * it touches (owner, name) and every string in its constant pool
     * (constants, annotation values such as @Query, names).
     */
    private record ClassFileRefs(Set<String> types, List<String[]> members, List<String> strings) {}

    private static final Pattern DESCRIPTOR_TYPE =
            Pattern.compile("L((?:com/spire/backend|org/springframework/mail|jakarta/mail)/[A-Za-z0-9_/$]+)[;<]");

    /** target/classes/com/spire/backend, where the compiled main code lives. */
    private static Path classesRoot() throws Exception {
        URL url = WebAgreementAccess.class.getResource("WebAgreementAccess.class");
        assertNotNull(url);
        assertEquals("file", url.getProtocol(), "the guard reads the compiled classes from the build directory");
        return Paths.get(url.toURI()).getParent().getParent();
    }

    /**
     * The class's own .class file plus every Outer$… file next to it (nested,
     * anonymous and local classes), each parsed. Lambda bodies compile into
     * these files.
     */
    private static Map<String, ClassFileRefs> compiledFiles(String name) throws Exception {
        Path file = classesRoot().resolve(name.replace('.', '/') + ".class");
        String simple = simpleName(name);
        Map<String, ClassFileRefs> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(file.getParent())) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.equals(simple + ".class") || (n.startsWith(simple + "$") && n.endsWith(".class"))) {
                    try (InputStream in = Files.newInputStream(p)) {
                        out.put(n, parse(in));
                    }
                }
            }
        }
        assertFalse(out.isEmpty(), name);
        return out;
    }

    /** Reads a class file's constant pool: the class names, descriptors, member references and strings in it. */
    private static ClassFileRefs parse(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        assertEquals(0xCAFEBABE, in.readInt(), "a class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        int[][] memberRef = new int[count][];
        int[][] nameAndType = new int[count][];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++; // takes two slots
                }
                case 7 -> classNameIndex[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 9, 10, 11 -> memberRef[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 12 -> nameAndType[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 17, 18 -> {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                }
                default -> fail("unknown constant pool tag " + tag);
            }
        }
        Set<String> types = new TreeSet<>();
        List<String> strings = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            if (classNameIndex[i] != 0) {
                String name = utf8[classNameIndex[i]];
                if (name.startsWith("[")) {
                    Matcher m = DESCRIPTOR_TYPE.matcher(name);
                    while (m.find()) types.add(m.group(1));
                } else {
                    types.add(name);
                }
            }
            if (utf8[i] != null) {
                strings.add(utf8[i]);
                Matcher m = DESCRIPTOR_TYPE.matcher(utf8[i]);
                while (m.find()) types.add(m.group(1));
            }
        }
        List<String[]> members = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            if (memberRef[i] == null) continue;
            String owner = utf8[classNameIndex[memberRef[i][0]]];
            String name = utf8[nameAndType[memberRef[i][1]][0]];
            members.add(new String[] {owner, name});
        }
        return new ClassFileRefs(types, members, strings);
    }
}
