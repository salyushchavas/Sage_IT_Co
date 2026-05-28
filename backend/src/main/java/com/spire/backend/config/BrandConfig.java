package com.spire.backend.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Backend single source of truth for brand identity.
 *
 * Every value is overridable via the matching BRAND_* environment
 * variable on Railway (or in application-*.properties). Defaults
 * mirror the original Spire Info Tech deployment, so a build with
 * no overrides is byte-equivalent to the pre-refactor backend.
 *
 * Pair with frontend/src/config/brand.ts on the Vercel side — the
 * two configs must agree on idPrefix, primaryColor, etc.
 */
@Component
@Getter
public class BrandConfig {

    @Value("${brand.name:Spire Info Tech}")
    private String name;

    @Value("${brand.short-name:Spire}")
    private String shortName;

    @Value("${brand.legal-name:Spire Info Tech}")
    private String legalName;

    @Value("${brand.tagline:Advance your career with expert coaching and personalized guidance}")
    private String tagline;

    @Value("${brand.contact-email:info@spireitco.com}")
    private String contactEmail;

    @Value("${brand.support-email:support@spireitco.com}")
    private String supportEmail;

    @Value("${brand.noreply-email:noreply@spireitco.com}")
    private String noreplyEmail;

    @Value("${brand.address-line1:H No. 2-91/5, Flat No. 1605}")
    private String addressLine1;

    @Value("${brand.address-line2:Trendset Jayabheri Enclave, Hitech City}")
    private String addressLine2;

    @Value("${brand.city:Hyderabad}")
    private String city;

    @Value("${brand.state:Telangana}")
    private String state;

    @Value("${brand.postal-code:500084}")
    private String postalCode;

    @Value("${brand.country:India}")
    private String country;

    @Value("${brand.website:https://spireinfotech.vercel.app}")
    private String website;

    /**
     * Prefix used when minting participant IDs — e.g. {@code "SIT"}
     * gives {@code SIT-2026-00001}. Existing IDs are untouched; only
     * newly-minted ones use the current value of this property.
     */
    @Value("${brand.id-prefix:SIT}")
    private String idPrefix;

    @Value("${brand.primary-color:#0F766E}")
    private String primaryColor;

    @Value("${brand.primary-color-dark:#115E59}")
    private String primaryColorDark;

    /**
     * Classpath path (relative to {@code src/main/resources/}) of the
     * letterhead PDF overlaid on every generated agreement. The
     * default is the existing Spire letterhead; brand swaps drop in
     * a different filename + set this env var.
     */
    @Value("${brand.letterhead-path:templates/letterhead.pdf}")
    private String letterheadPath;

    @Value("${brand.logo-url:https://spireinfotech.vercel.app/logo.png}")
    private String logoUrl;

    /** One-line postal address for email footers + letterhead. */
    public String getFullAddress() {
        return String.format("%s, %s, %s, %s %s, %s",
                addressLine1, addressLine2, city, state, postalCode, country);
    }
}
