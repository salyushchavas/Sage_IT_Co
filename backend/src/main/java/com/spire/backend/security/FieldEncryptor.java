package com.spire.backend.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts the most sensitive values at rest (roadmap §13.1): SSNs, driver's
 * licence and State-ID numbers, bank routing and account numbers in the
 * consultant-agreement console. AES-256-GCM with a random 96-bit IV per value;
 * stored as {@code enc:v1:<base64(iv | ciphertext | tag)>}.
 *
 * <p>The key is FIELD_ENCRYPTION_KEY (base64 of 32 random bytes). The app
 * refuses to start without a valid key, so nothing is ever written in plain
 * text by mistake. Losing or changing the key makes existing values
 * unreadable, so it must be kept safe and never rotated without a re-encrypt.
 *
 * <p>Values written before encryption existed have no prefix and are returned
 * unchanged, which lets the app run while they are migrated.
 */
@Component
public class FieldEncryptor {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Set once the key is validated; used by {@link SensitiveTextConverter}. */
    private static volatile FieldEncryptor current;

    @Value("${app.field-encryption-key:}")
    private String base64Key;

    private SecretKeySpec key;

    public FieldEncryptor() {}

    /** For tests. */
    public FieldEncryptor(String base64Key) {
        this.base64Key = base64Key;
        init();
    }

    @PostConstruct
    void init() {
        key = parseKey(base64Key);
        current = this;
    }

    static SecretKeySpec parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("FIELD_ENCRYPTION_KEY is not set: set it to 32 random bytes in base64 "
                    + "(e.g. openssl rand -base64 32)");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("FIELD_ENCRYPTION_KEY is not valid base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("FIELD_ENCRYPTION_KEY must decode to 32 bytes (it decodes to "
                    + raw.length + ")");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /** The validated instance; fails loudly rather than letting a value go unencrypted. */
    static FieldEncryptor current() {
        FieldEncryptor instance = current;
        if (instance == null) {
            throw new IllegalStateException("Field encryption is not initialised (FIELD_ENCRYPTION_KEY)");
        }
        return instance;
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** Encrypts a value; null and empty strings are stored as they are. */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt a sensitive field", e);
        }
    }

    /** Decrypts an {@code enc:v1:} value; anything else (legacy plain text, null) is returned as is. */
    public String decrypt(String stored) {
        if (!isEncrypted(stored)) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Wrong key or tampered value: never return the ciphertext as if it were data.
            throw new IllegalStateException("Could not decrypt a sensitive field (wrong FIELD_ENCRYPTION_KEY?)", e);
        }
    }
}
