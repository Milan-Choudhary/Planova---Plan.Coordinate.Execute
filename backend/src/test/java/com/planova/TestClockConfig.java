package com.planova;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the system clock in tests with one the test can move forward, so "the acknowledgement is
 * overdue" is produced by advancing time - never by sleeping.
 */
@TestConfiguration
public class TestClockConfig {

    public static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        public void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }
}
