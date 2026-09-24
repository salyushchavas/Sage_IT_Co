package com.spire.backend.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * The boot-time "grandfather pre-OTP accounts" rule marks users verified
 * when they have no code. Codes now live only as a hash, so the rule must
 * also require an empty hash — or every restart would verify every pending
 * signup without its code.
 */
class DataSeederVerificationTest {

    @Test
    void restartNeverVerifiesASignupThatIsWaitingForItsCode() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSeeder seeder = new DataSeeder();
        ReflectionTestUtils.setField(seeder, "jdbcTemplate", jdbc);

        ReflectionTestUtils.invokeMethod(seeder, "addUserEmailColumnsIfMissing");

        ArgumentCaptor<String> updates = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(updates.capture());
        List<String> grandfather = updates.getAllValues().stream()
                .filter(sql -> sql.contains("SET email_verified = TRUE"))
                .toList();
        assertEquals(1, grandfather.size());
        assertTrue(grandfather.get(0).contains("verification_code_hash IS NULL"), grandfather.get(0));

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).execute(ddl.capture());
        assertTrue(ddl.getAllValues().stream().anyMatch(s -> s.contains("verification_code_hash VARCHAR(64)")),
                "the new hash column must be in the explicit column list");
    }
}
