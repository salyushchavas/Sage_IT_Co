package com.spire.backend.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checklist 0.6: SSNs, licence / State-ID and bank numbers are encrypted at
 * rest and read back unchanged; the app refuses a missing or malformed key.
 */
class FieldEncryptionTest {

    private static String key(int fill) {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) (i * 7 + fill);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void valuesRoundTripAndAreNeverStoredInPlainText() {
        FieldEncryptor enc = new FieldEncryptor(key(1));
        String ssn = "123-45-6789";
        String stored = enc.encrypt(ssn);
        assertTrue(stored.startsWith(FieldEncryptor.PREFIX));
        assertFalse(stored.contains("6789"));
        assertEquals(ssn, enc.decrypt(stored));
        assertNotEquals(stored, enc.encrypt(ssn), "a fresh IV each time");
        assertNull(enc.encrypt(null));
        assertEquals("", enc.encrypt(""));
    }

    @Test
    void valuesWrittenBeforeEncryptionStillRead() {
        FieldEncryptor enc = new FieldEncryptor(key(1));
        assertEquals("987654321", enc.decrypt("987654321"));
        assertNull(enc.decrypt(null));
    }

    @Test
    void aWrongKeyOrATamperedValueFailsInsteadOfReturningGarbage() {
        String stored = new FieldEncryptor(key(1)).encrypt("DL-99887766");
        FieldEncryptor other = new FieldEncryptor(key(2));
        assertThrows(IllegalStateException.class, () -> other.decrypt(stored));

        FieldEncryptor enc = new FieldEncryptor(key(1));
        char[] chars = stored.toCharArray();
        int i = chars.length - 5;
        chars[i] = chars[i] == 'A' ? 'B' : 'A';
        assertThrows(IllegalStateException.class, () -> enc.decrypt(new String(chars)));
    }

    @Test
    void theAppRefusesAMissingOrMalformedKey() {
        assertThrows(IllegalStateException.class, () -> new FieldEncryptor(null));
        assertThrows(IllegalStateException.class, () -> new FieldEncryptor(" "));
        assertThrows(IllegalStateException.class, () -> new FieldEncryptor("not base64 !!"));
        assertThrows(IllegalStateException.class,
                () -> new FieldEncryptor(Base64.getEncoder().encodeToString(new byte[16])), "16 bytes is too short");
    }

    @Test
    void theColumnConverterEncryptsOnWriteAndDecryptsOnRead() {
        new FieldEncryptor(key(3));   // registers the validated instance
        SensitiveTextConverter converter = new SensitiveTextConverter();
        String column = converter.convertToDatabaseColumn("021000021");
        assertTrue(column.startsWith(FieldEncryptor.PREFIX));
        assertEquals("021000021", converter.convertToEntityAttribute(column));
        assertEquals("plain-legacy", converter.convertToEntityAttribute("plain-legacy"));
        assertNull(converter.convertToDatabaseColumn(null));
    }
}
