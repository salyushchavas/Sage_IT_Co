package com.spire.backend.service;

import com.spire.backend.config.BrandConfig;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.entity.WebAgreement;
import com.spire.backend.repository.AgreementUserRepository;
import com.spire.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The "participant signed" email opens the agreement on the reviewer's own
 * dashboard: an ERM on /erm-dashboard, an Operations or System admin (the
 * owner, or standing in for an inactive one) on /operations.
 */
class WebAgreementEmailTest {

    private EmailService emailService;
    private EmailTemplateService templates;
    private final WebAgreement agreement = WebAgreement.builder().applicationId("app-1")
            .consultantName("Pat Q Lee").consultantEmail("pat.lee@x.com").build();

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        when(emailService.sendEmail(anyString(), anyString(), anyString())).thenReturn(true);
        BrandConfig brand = mock(BrandConfig.class);
        when(brand.getName()).thenReturn("Sage IT Co");
        when(brand.getPrimaryColor()).thenReturn("#123456");
        when(brand.getWebsite()).thenReturn("https://sage.test");
        templates = new EmailTemplateService(emailService, brand, mock(UserRepository.class),
                mock(AgreementUserRepository.class), mock(AgreementDocumentService.class));
        ReflectionTestUtils.setField(templates, "appUrl", "https://portal.test");
    }

    private static User reviewer(String role) {
        return User.builder().id(1L).email("reviewer@sage.test").fullName("Rae Viewer")
                .role(Role.builder().name(role).build()).isActive(true).build();
    }

    private String sentTo(User reviewer) {
        assertTrue(templates.sendWebAgreementSignedEmail(agreement, reviewer));
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("reviewer@sage.test"), anyString(), html.capture());
        return html.getValue();
    }

    @Test
    void anErmReviewsOnTheErmDashboard() {
        assertTrue(sentTo(reviewer("ERM"))
                .contains("https://portal.test/erm-dashboard?tab=agreements&agreement=app-1"));
    }

    @Test
    void anOperationsAdminReviewsOnTheOperationsPage() {
        String html = sentTo(reviewer("OPERATIONS_ADMIN"));
        assertTrue(html.contains("https://portal.test/operations?tab=agreements&agreement=app-1"));
        assertFalse(html.contains("/erm-dashboard"));
    }

    @Test
    void aSystemAdminReviewsOnTheOperationsPage() {
        assertTrue(sentTo(reviewer("system_admin"))
                .contains("https://portal.test/operations?tab=agreements&agreement=app-1"));
    }

    @Test
    void nobodyToTellSendsNothing() {
        assertFalse(templates.sendWebAgreementSignedEmail(agreement, null));
        verifyNoInteractions(emailService);
    }
}
