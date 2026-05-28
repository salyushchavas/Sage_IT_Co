package com.spire.backend.controller;

import com.spire.backend.config.BrandConfig;
import com.spire.backend.dto.ApiResponse;
import com.spire.backend.dto.BrandConfigDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint that returns the active brand identity. Used by:
 *   - The frontend at runtime when it wants to verify the build's
 *     env-var-baked brand matches what the backend is serving.
 *   - Admin pages that display the deployment's brand.
 *   - Smoke tests that confirm a re-brand actually landed.
 *
 * No sensitive data — see {@link BrandConfigDto}.
 */
@RestController
@RequiredArgsConstructor
public class BrandController {

    private final BrandConfig brandConfig;

    @GetMapping("/api/brand")
    public ResponseEntity<ApiResponse<BrandConfigDto>> getBrand() {
        return ResponseEntity.ok(ApiResponse.success(
                BrandConfigDto.from(brandConfig)));
    }
}
