package com.spire.backend.dto;

import com.spire.backend.config.BrandConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public-safe snapshot of the active {@link BrandConfig}. Returned
 * by {@code GET /api/brand} so admin tooling, the frontend, and
 * status pages can read the current brand identity without each
 * one re-implementing env-var lookup.
 *
 * Mirrors the keys exposed in {@code frontend/src/config/brand.ts}.
 * Anything sensitive (DB creds, JWT secret, cron secret) is excluded
 * by construction — only fields explicitly on this DTO leak out.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandConfigDto {

    private String name;
    private String shortName;
    private String legalName;
    private String tagline;
    private String contactEmail;
    private String supportEmail;
    private String noreplyEmail;
    private String fullAddress;
    private String website;
    private String idPrefix;
    private String primaryColor;
    private String primaryColorDark;
    private String logoUrl;

    public static BrandConfigDto from(BrandConfig brand) {
        return BrandConfigDto.builder()
                .name(brand.getName())
                .shortName(brand.getShortName())
                .legalName(brand.getLegalName())
                .tagline(brand.getTagline())
                .contactEmail(brand.getContactEmail())
                .supportEmail(brand.getSupportEmail())
                .noreplyEmail(brand.getNoreplyEmail())
                .fullAddress(brand.getFullAddress())
                .website(brand.getWebsite())
                .idPrefix(brand.getIdPrefix())
                .primaryColor(brand.getPrimaryColor())
                .primaryColorDark(brand.getPrimaryColorDark())
                .logoUrl(brand.getLogoUrl())
                .build();
    }
}
