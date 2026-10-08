package com.spire.backend.security;

import com.spire.backend.controller.UserController;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.util.WebUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An agreements-console sign-in only works on the console's addresses: on
 * every website address it is a 401 (a numeric console email is never a
 * website user), while the console keeps every address it maps or calls
 * and website sign-ins of every role pass as before.
 */
class ConsoleTokenBoundaryFilterTest {

    /** The console's controllers (read here, never changed). */
    private static final Set<String> CONSOLE_CONTROLLERS = Set.of(
            "AgreementErmAuthController", "ConsultantApplicationController",
            "AgreementApproverController", "AgreementAdminController");

    /** Every address the console's pages call with the console token (src/lib/api.ts). */
    private static final List<String> CONSOLE_PAGE_CALLS = List.of(
            "/api/agreement-erm/me",
            "/api/agreement-erm/applications",
            "/api/agreement-erm/applications/app-1",
            "/api/agreement-erm/applications/app-1/send-for-approval",
            "/api/agreement-erm/applications/app-1/versions/2/pdf",
            "/api/agreement-erm/applications/app-1/workauth",
            "/api/agreement-erm/approval-board",
            "/api/agreement-approver/queue",
            "/api/agreement-approver/applications/app-1/approve",
            "/api/agreement-approver/applications/app-1/version-preview-images",
            "/api/agreements/admin/users",
            "/api/agreements/admin/users/u-1/assignments",
            "/api/agreements/admin/applications/app-1",
            "/api/agreements/admin/regenerate-completed-agreements");

    /** Website addresses, the ones the console token used to reach first. */
    private static final List<String> WEBSITE_PATHS = List.of(
            "/api/users/profile",
            "/api/participants/web-agreement",
            "/api/participants/web-agreement/fill",
            "/api/participants/agreement-request",
            "/api/web-agreements",
            "/api/web-agreements/approval-board",
            "/api/web-agreement-approvals/queue",
            "/api/web-agreement-approvals/applications/app-1/approve",
            "/api/web-agreement-admin/teams",
            "/api/auth/agreement/accept",
            "/api/agreement/signed-pdf/557/a.pdf",
            "/api/brand",
            "/error",
            // Look-alikes of the console's roots.
            "/api/agreement-ermx/me",
            "/api/agreement-approvers/queue",
            "/api/agreements/administrator",
            "/api/consultants");

    /** Every website role (the roles table), with JwtAuthFilter's authorities. */
    private static final List<String> WEBSITE_ROLES = List.of("ACCOUNTS", "ADMIN", "COACH", "ERM", "FINANCE",
            "INSTRUCTOR", "MANAGER", "OPERATIONS_ADMIN", "PARTICIPANT", "STUDENT", "SYSTEM_ADMIN",
            "TECHNICAL_ADVISOR", "TRAINER");

    /** AgreementErmAuthFilter's grants per console role. */
    private static final Map<String, List<String>> CONSOLE_GRANTS = Map.of(
            "SUPER_ADMIN", List.of("ROLE_AGREEMENT_USER", "ROLE_AGREEMENT_ERM", "ROLE_AGREEMENT_MANAGER",
                    "ROLE_AGREEMENT_ACCOUNTS"),
            "ERM", List.of("ROLE_AGREEMENT_USER", "ROLE_AGREEMENT_ERM"),
            "MANAGER", List.of("ROLE_AGREEMENT_USER", "ROLE_AGREEMENT_MANAGER"),
            "ACCOUNTS", List.of("ROLE_AGREEMENT_USER", "ROLE_AGREEMENT_ACCOUNTS"));

    private final ConsoleTokenBoundaryFilter filter = new ConsoleTokenBoundaryFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signInConsole(String email, List<String> grants) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, null,
                grants.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private static void signInWebsite(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, JwtAuthFilter.authoritiesFor(role)));
    }

    private record Outcome(boolean passed, MockHttpServletResponse response) {}

    private Outcome run(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(chain.getRequest() != null, response);
    }

    // ── The console token ────────────────────────────────────────────

    @Test
    void aConsoleSignInIsRefusedOnEveryWebsiteAddressEvenWithANumericEmail() throws Exception {
        for (String email : List.of("557", "console.admin@local.test")) {
            for (Map.Entry<String, List<String>> grants : CONSOLE_GRANTS.entrySet()) {
                signInConsole(email, grants.getValue());
                for (String path : WEBSITE_PATHS) {
                    for (String method : List.of("GET", "POST", "PUT")) {
                        Outcome out = run(method, path);
                        String what = grants.getKey() + " " + email + " " + method + " " + path;
                        assertFalse(out.passed(), what);
                        assertEquals(401, out.response().getStatus(), what);
                        String body = out.response().getContentAsString();
                        assertEquals("{\"success\":false,\"message\":\"Sign in to the website to use this.\"}", body, what);
                        assertFalse(body.contains(email), "the console email is never echoed: " + what);
                    }
                }
            }
        }
    }

    @Test
    void theConsoleKeepsEveryAddressItCallsAndMaps() throws Exception {
        for (Map.Entry<String, List<String>> grants : CONSOLE_GRANTS.entrySet()) {
            signInConsole("console.admin@local.test", grants.getValue());
            List<String> paths = new ArrayList<>(CONSOLE_PAGE_CALLS);
            consoleControllerPaths().values().forEach(paths::addAll);
            for (String path : paths) {
                Outcome out = run("GET", path.replaceAll("\\{[^}]*}", "x"));
                assertTrue(out.passed(), grants.getKey() + " " + path);
                assertEquals(200, out.response().getStatus(), "untouched: " + path);
            }
        }
    }

    @Test
    void websiteSignInsOfEveryRoleAndAnonymousRequestsPassEverywhere() throws Exception {
        List<String> paths = new ArrayList<>(WEBSITE_PATHS);
        paths.addAll(CONSOLE_PAGE_CALLS);
        for (String role : WEBSITE_ROLES) {
            signInWebsite(557L, role);
            for (String path : paths) assertTrue(run("GET", path).passed(), role + " " + path);
        }
        SecurityContextHolder.clearContext();
        for (String path : paths) assertTrue(run("GET", path).passed(), "anonymous " + path);
    }

    @Test
    void noWebsiteRoleLooksLikeAConsoleGrant() {
        for (String role : WEBSITE_ROLES) {
            signInWebsite(1L, role);
            assertFalse(ConsoleTokenBoundaryFilter.isConsoleSignIn(SecurityContextHolder.getContext().getAuthentication()),
                    role);
        }
        assertFalse(ConsoleTokenBoundaryFilter.isConsoleSignIn(null));
    }

    @Test
    void theErrorPageOfAConsoleRequestIsLeftAlone() throws Exception {
        // A console endpoint's error forwards to /error; that dispatch keeps the console's real status.
        signInConsole("console.admin@local.test", CONSOLE_GRANTS.get("ERM"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, "/api/agreement-erm/applications/app-1");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
    }

    @Test
    void itRunsAfterSpringSecuritysChain() {
        Order order = ConsoleTokenBoundaryFilter.class.getAnnotation(Order.class);
        assertNotNull(order);
        assertTrue(order.value() > SecurityProperties.DEFAULT_FILTER_ORDER, "after the chain resolves the sign-in");
    }

    // ── The roots match the controllers ──────────────────────────────

    @Test
    void everyConsoleMappingIsUnderARootAndNoWebsiteMappingIs() throws Exception {
        Map<String, Set<String>> console = consoleControllerPaths();
        assertEquals(CONSOLE_CONTROLLERS, console.keySet(), "the console's controllers are all found");
        int consoleCount = 0;
        for (Map.Entry<String, Set<String>> c : console.entrySet()) {
            assertFalse(c.getValue().isEmpty(), c.getKey());
            for (String path : c.getValue()) {
                consoleCount++;
                assertTrue(ConsoleTokenBoundaryFilter.isConsolePath(path), c.getKey() + " " + path);
            }
        }
        assertTrue(consoleCount > 60, "every console mapping is read: " + consoleCount);

        int websiteCount = 0;
        for (Map.Entry<String, Set<String>> c : controllerPaths().entrySet()) {
            if (CONSOLE_CONTROLLERS.contains(c.getKey())) continue;
            for (String path : c.getValue()) {
                websiteCount++;
                assertFalse(ConsoleTokenBoundaryFilter.isConsolePath(path), c.getKey() + " " + path);
            }
        }
        assertTrue(websiteCount > 250, "every website mapping is read: " + websiteCount);
        for (String path : CONSOLE_PAGE_CALLS) assertTrue(ConsoleTokenBoundaryFilter.isConsolePath(path), path);
        for (String path : WEBSITE_PATHS) assertFalse(ConsoleTokenBoundaryFilter.isConsolePath(path), path);
    }

    private static Map<String, Set<String>> consoleControllerPaths() throws Exception {
        Map<String, Set<String>> out = new TreeMap<>(controllerPaths());
        out.keySet().retainAll(CONSOLE_CONTROLLERS);
        return out;
    }

    /** Every controller in the controller package with every path it maps (class prefix + method). */
    private static Map<String, Set<String>> controllerPaths() throws Exception {
        Path dir = Paths.get(UserController.class.getResource("UserController.class").toURI()).getParent();
        Map<String, Set<String>> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".class") || n.contains("$")) continue;
                Class<?> c = Class.forName(UserController.class.getPackageName() + "." + n.replace(".class", ""));
                if (!AnnotatedElementUtils.hasAnnotation(c, Controller.class)) continue;
                RequestMapping top = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
                List<String> prefixes = top == null || top.path().length == 0 ? List.of("") : List.of(top.path());
                Set<String> paths = new TreeSet<>();
                for (Method m : c.getDeclaredMethods()) {
                    RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                    if (rm == null) continue;
                    List<String> ends = rm.path().length == 0 ? List.of("") : List.of(rm.path());
                    for (String prefix : prefixes) for (String end : ends) paths.add(prefix + end);
                }
                out.put(c.getSimpleName(), paths);
            }
        }
        return out;
    }
}
