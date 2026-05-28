package com.spire.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling enables @Scheduled annotations on services —
// currently used by the inactive-nudge job in EmailNudgeJob. Cheap
// to leave on; Spring only spins up the scheduler thread when at
// least one @Scheduled method exists.
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
