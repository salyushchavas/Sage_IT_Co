package com.spire.backend.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Backend single source of truth for brand identity.
 *
 * Every value is overridable via the matching BRAND_* environment
 * variable on Railway (or in application-*.properties). Defaults
 * mirror the Sage IT Co deployment; brand swaps override via env.
 *
 * Pair with frontend/src/config/brand.ts on the Vercel side — the
 * two configs must agree on idPrefix, primaryColor, etc.
 */
@Component
@Getter
public class BrandConfig {

    @Value("${brand.name:Sage IT Co}")
    private String name;

    @Value("${brand.short-name:Sage}")
    private String shortName;

    @Value("${brand.legal-name:Sage IT Co}")
    private String legalName;

    @Value("${brand.tagline:IT Consulting and Career Development}")
    private String tagline;

    @Value("${brand.contact-email:info@sageitco.com}")
    private String contactEmail;

    @Value("${brand.support-email:support@sageitco.com}")
    private String supportEmail;

    @Value("${brand.noreply-email:noreply@sageitco.com}")
    private String noreplyEmail;

    @Value("${brand.address-line1:}")
    private String addressLine1;

    @Value("${brand.address-line2:}")
    private String addressLine2;

    @Value("${brand.city:}")
    private String city;

    @Value("${brand.state:}")
    private String state;

    @Value("${brand.postal-code:}")
    private String postalCode;

    @Value("${brand.country:India}")
    private String country;

    @Value("${brand.website:https://sageitco.com}")
    private String website;

    /**
     * Prefix used when minting participant IDs — e.g. {@code "SAGE"}
     * gives {@code SAGE-2026-00001}. Existing IDs are untouched; only
     * newly-minted ones use the current value of this property.
     */
    @Value("${brand.id-prefix:SAGE}")
    private String idPrefix;

    @Value("${brand.primary-color:#1B2A5C}")
    private String primaryColor;

    @Value("${brand.primary-color-dark:#0F1F44}")
    private String primaryColorDark;

    /**
     * Classpath path (relative to {@code src/main/resources/}) of the
     * letterhead PDF overlaid on every generated agreement. Brand
     * swaps drop in a different filename + set this env var.
     */
    @Value("${brand.letterhead-path:sage_letterhead.pdf}")
    private String letterheadPath;

    @Value("${brand.logo-url:https://sageitco.com/sage_logo.png}")
    private String logoUrl;

    /** One-line postal address for email footers + letterhead. */
    public String getFullAddress() {
        return String.format("%s, %s, %s, %s %s, %s",
                addressLine1, addressLine2, city, state, postalCode, country);
    }
}
