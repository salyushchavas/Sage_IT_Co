package com.spire.backend.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Encrypts a column on write and decrypts it on read, so the rest of the code
 * (screens, the agreement PDF, emails) keeps seeing the plain value. Used on
 * the consultant console's SSN, licence / State-ID and bank numbers.
 */
@Converter
public class SensitiveTextConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return FieldEncryptor.current().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return FieldEncryptor.current().decrypt(dbData);
    }
}
