package com.spire.backend.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

/**
 * Phase 1 — S3 document storage. Uploads go to a private bucket
 * (Block Public Access ON, versioning + SSE-S3 default encryption);
 * downloads are served via short-lived presigned GET URLs.
 *
 * <p>The {@link S3Client}/{@link S3Presigner} beans are {@code @Lazy} and
 * injected through {@link ObjectProvider}, so they are only materialised on
 * first S3 use — the app still boots in environments without AWS configured.
 * Failures surface as the SDK's own exceptions (no silent fallback to
 * Cloudinary/disk for new documents).
 */
@Service
public class S3StorageService implements DocumentStorage {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private final ObjectProvider<S3Client> s3Provider;
    private final ObjectProvider<S3Presigner> presignerProvider;
    private final String bucket;

    public S3StorageService(ObjectProvider<S3Client> s3Provider,
                            ObjectProvider<S3Presigner> presignerProvider,
                            @Value("${aws.s3.bucket:}") String bucket) {
        this.s3Provider = s3Provider;
        this.presignerProvider = presignerProvider;
        this.bucket = bucket;
    }

    @Override
    public String store(byte[] bytes, String key, String contentType) {
        s3Provider.getObject().putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(bytes));
        return key;
    }

    @Override
    public byte[] get(String key) {
        return s3Provider.getObject().getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build())
                .asByteArray();
    }

    @Override
    public String presignedGetUrl(String key, Duration ttl, String filename, boolean inline) {
        String disposition = inline
                ? "inline"
                : "attachment" + (filename != null && !filename.isBlank()
                        ? "; filename=\"" + filename + "\"" : "");
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentType("application/pdf")
                .responseContentDisposition(disposition)
                .build();
        GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                .signatureDuration(ttl == null ? DEFAULT_TTL : ttl)
                .getObjectRequest(get)
                .build();
        return presignerProvider.getObject().presignGetObject(presign).url().toString();
    }
}
