package com.spire.backend.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Checklist 2.1: a restart must never mark anyone as having accepted the
 * agreement. The only change the seeder makes is to clear the flag for
 * participants with no signed agreement on file.
 */
class DataSeederAgreementTest {

    @Test
    void restartNeverFabricatesAgreementAcceptance() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        DataSeeder seeder = new DataSeeder();
        ReflectionTestUtils.setField(seeder, "jdbcTemplate", jdbc);

        ReflectionTestUtils.invokeMethod(seeder, "addUserEmailColumnsIfMissing");

        ArgumentCaptor<String> updates = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(updates.capture());
        List<String> sql = updates.getAllValues();
        assertTrue(sql.stream().noneMatch(s -> s.matches("(?s).*SET\\s+agreement_accepted\\s*=\\s*TRUE.*")),
                "no statement may set agreement_accepted to TRUE: " + sql);
        String cleared = sql.stream().filter(s -> s.contains("SET agreement_accepted = FALSE")).findFirst().orElseThrow();
        assertTrue(cleared.contains("participant_id IS NOT NULL") && cleared.contains("status = 'VERIFIED'"), cleared);
    }
}
