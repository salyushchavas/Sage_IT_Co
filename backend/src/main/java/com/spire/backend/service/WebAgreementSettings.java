package com.spire.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Switches for the website agreement. Its emails stay off while the website
 * copy runs on its own, with our test ERM accounts, beside the office's
 * agreements console; set WEB_AGREEMENT_EMAILS=true when the two are merged
 * and the real ERMs work here.
 */
@Component
public class WebAgreementSettings {

    private final boolean emailsEnabled;

    public WebAgreementSettings(@Value("${web-agreement.emails-enabled:false}") boolean emailsEnabled) {
        this.emailsEnabled = emailsEnabled;
    }

    /** Whether website-agreement emails are sent at all (participant and ERM). */
    public boolean emailsEnabled() {
        return emailsEnabled;
    }
}
