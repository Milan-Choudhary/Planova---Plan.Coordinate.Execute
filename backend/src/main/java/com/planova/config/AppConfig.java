package com.planova.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {

    /**
     * Injecting a Clock (instead of calling Instant.now() everywhere) lets tests freeze or move
     * time. This matters later for acknowledgement deadlines and escalation tests.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
