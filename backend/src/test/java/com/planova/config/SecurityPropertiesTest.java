package com.planova.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plain unit test (no Spring, no Docker): the startup guard against a weak JWT secret. NOT YET EXECUTED. */
class SecurityPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shortSecretIsRejected() {
        var props = new SecurityProperties("too-short", 60, List.of("http://localhost:5173"));
        assertThat(validator.validate(props)).isNotEmpty();
    }

    @Test
    void blankSecretIsRejected() {
        var props = new SecurityProperties("   ", 60, List.of("http://localhost:5173"));
        assertThat(validator.validate(props)).isNotEmpty();
    }

    @Test
    void absurdExpiryIsRejected() {
        var props = new SecurityProperties("x".repeat(40), 100000, List.of("http://localhost:5173"));
        assertThat(validator.validate(props)).isNotEmpty();
    }

    @Test
    void validPropertiesPass() {
        var props = new SecurityProperties("x".repeat(40), 60, List.of("http://localhost:5173"));
        assertThat(validator.validate(props)).isEmpty();
    }
}
