package com.spire.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Phase 1 — AWS S3 document storage. Exposes the {@link S3Client} (uploads)
 * and {@link S3Presigner} (short-lived download URLs) beans.
 *
 * <p>Region comes from {@code aws.region} (env {@code AWS_REGION}); the
 * bucket from {@code aws.s3.bucket} (env {@code S3_BUCKET}) — neither is
 * hardcoded. Credentials are resolved by the SDK's default credentials
 * provider chain ({@code DefaultCredentialsProvider}, used automatically
 * when no provider is set on the builder) straight from the environment
 * ({@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}), so nothing
 * secret is referenced here or logged.
 *
 * <p>Both beans are {@code @Lazy}: they are only built on first S3 use.
 * That keeps the app booting in environments without AWS configured (e.g.
 * local dev) — the Cloudinary path and every non-S3 flow are unaffected.
 */
@Configuration
public class AwsS3Config {

    @Value("${aws.region:}")
    private String region;

    /**
     * Optional: an S3-compatible endpoint (e.g. a local test server). Empty
     * in production, where the SDK uses Amazon's own endpoint.
     */
    @Value("${aws.s3.endpoint:}")
    private String endpoint;

    private boolean customEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }

    @Bean
    @Lazy
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(region))
                // Use the JDK URLConnection HTTP client, NOT the SDK default
                // (Apache HttpClient 5). Spring Boot 3.2.5 pins httpclient5 to
                // 5.2.x, which lacks TlsSocketStrategy (added in 5.4) that the
                // AWS apache-client references — that mismatch otherwise makes
                // this bean fail to instantiate (NoClassDefFoundError). The
                // URLConnection client has no httpclient5 dependency.
                .httpClient(UrlConnectionHttpClient.create())
                .applyMutation(b -> {
                    if (customEndpoint()) {
                        b.endpointOverride(java.net.URI.create(endpoint)).forcePathStyle(true);
                    }
                })
                // No explicit credentialsProvider → the SDK uses
                // DefaultCredentialsProvider (env / instance / profile chain).
                .build();
    }

    @Bean
    @Lazy
    public S3Presigner s3Presigner() {
        S3Presigner.Builder builder = S3Presigner.builder();
        if (customEndpoint()) {
            builder.endpointOverride(java.net.URI.create(endpoint))
                    .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                            .pathStyleAccessEnabled(true).build());
        }
        return builder
                .region(Region.of(region))
                // No explicit credentialsProvider → the SDK uses
                // DefaultCredentialsProvider (env / instance / profile chain).
                .build();
    }
}
