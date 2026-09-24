package com.spire.backend.config;

import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.RoleRepository;
import com.spire.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Checklist 0.2: seeded accounts whose passwords were public get private
 * replacements, and staff / trainer accounts are no longer (re)created on
 * boot unless SEED_DEMO_ACCOUNTS is on.
 */
class DataSeederSeedAccountsTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static DataSeeder seeder(UserRepository users, boolean demoAccounts) {
        DataSeeder seeder = new DataSeeder();
        ReflectionTestUtils.setField(seeder, "userRepository", users);
        ReflectionTestUtils.setField(seeder, "roleRepository", mock(RoleRepository.class));
        ReflectionTestUtils.setField(seeder, "seedDemoAccounts", demoAccounts);
        return seeder;
    }

    private static String[] replacementRow(String email) {
        String[][] table = (String[][]) ReflectionTestUtils.getField(DataSeeder.class, "PUBLIC_SEED_PASSWORD_REPLACEMENTS");
        for (String[] row : table) if (row[0].equals(email)) return row;
        throw new AssertionError("no replacement row for " + email);
    }

    @Test
    void anAccountStillOnItsPublicPasswordGetsItsPrivateReplacement() {
        UserRepository users = mock(UserRepository.class);
        User superAdmin = User.builder().id(1L).email("superadmin@sageitco.com")
                .passwordHash(ENCODER.encode("SageSuper@2026")).build();
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.findByEmail("superadmin@sageitco.com")).thenReturn(Optional.of(superAdmin));

        ReflectionTestUtils.invokeMethod(seeder(users, false), "replacePublicSeedPasswords");

        assertEquals(replacementRow("superadmin@sageitco.com")[2], superAdmin.getPasswordHash());
        assertFalse(ENCODER.matches("SageSuper@2026", superAdmin.getPasswordHash()), "the public password must stop working");
        verify(users).save(superAdmin);
    }

    @Test
    void anAccountWhosePasswordWasAlreadyChangedIsLeftAlone() {
        UserRepository users = mock(UserRepository.class);
        String ownHash = ENCODER.encode("a password the owner chose");
        User finance = User.builder().id(2L).email("finance@sageitco.com").passwordHash(ownHash).build();
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.findByEmail("finance@sageitco.com")).thenReturn(Optional.of(finance));

        ReflectionTestUtils.invokeMethod(seeder(users, false), "replacePublicSeedPasswords");

        assertEquals(ownHash, finance.getPasswordHash());
        verify(users, never()).save(any());
    }

    @Test
    void everyReplacementIsARealBcryptHashAndNoPublicPasswordIsReused() {
        String[][] table = (String[][]) ReflectionTestUtils.getField(DataSeeder.class, "PUBLIC_SEED_PASSWORD_REPLACEMENTS");
        assertEquals(16, table.length);
        for (String[] row : table) {
            assertTrue(row[2].matches("\\$2a\\$10\\$[./A-Za-z0-9]{53}"), row[0]);
            assertFalse(ENCODER.matches(row[1], row[2]), row[0] + ": the replacement must differ from the public password");
        }
    }

    @Test
    void withTheSwitchOffNoStaffOrTrainerAccountIsCreated() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.existsByEmail(anyString())).thenReturn(false);
        DataSeeder seeder = seeder(users, false);

        ReflectionTestUtils.invokeMethod(seeder, "seedPhase4Team");
        ReflectionTestUtils.invokeMethod(seeder, "seedServicesAndTrainer", Role.builder().name("TRAINER").build());

        verify(users, never()).save(any());
    }
}
