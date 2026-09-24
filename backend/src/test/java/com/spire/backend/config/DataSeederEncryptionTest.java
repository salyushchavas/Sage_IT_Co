package com.spire.backend.config;

import com.spire.backend.security.FieldEncryptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 0.6: on boot, sensitive consultant values still in plain text
 * are encrypted in place, once; already-encrypted values are left alone.
 */
class DataSeederEncryptionTest {

    @Test
    void plainValuesAreEncryptedInPlaceAndOnlyIfUnchanged() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FieldEncryptor enc = new FieldEncryptor(Base64.getEncoder().encodeToString(new byte[32]));
        DataSeeder seeder = new DataSeeder();
        ReflectionTestUtils.setField(seeder, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(seeder, "fieldEncryptor", enc);

        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        when(jdbc.queryForList(contains("bg_full_ssn AS v"))).thenReturn(List.of(Map.of("id", 5L, "v", "123-45-6789")));
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        int encrypted = (int) ReflectionTestUtils.invokeMethod(seeder, "encryptSensitiveConsultantFields");

        assertEquals(1, encrypted);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(sql.capture(), value.capture(), eq(5L), eq("123-45-6789"));
        assertTrue(sql.getValue().startsWith("UPDATE consultant_applications SET bg_full_ssn = ?"));
        assertTrue(sql.getValue().endsWith("AND bg_full_ssn = ?"), "only if the value hasn't changed");
        assertEquals("123-45-6789", enc.decrypt((String) value.getValue()));

        ArgumentCaptor<String> selects = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(5)).queryForList(selects.capture());
        assertTrue(selects.getAllValues().stream().allMatch(s -> s.contains("NOT LIKE 'enc:v1:%'")),
                "already-encrypted values are never read for re-encryption");
    }
}
