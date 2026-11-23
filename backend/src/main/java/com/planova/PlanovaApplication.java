package com.planova;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the modular monolith.
 *
 * <p>{@code @SpringBootApplication} = {@code @Configuration} + {@code @EnableAutoConfiguration}
 * + {@code @ComponentScan}. It scans this package and every sub-package, so each module
 * ({@code identity}, later {@code planning}, {@code incident}, ...) is simply a sub-package.
 *
 * <p>{@code @ConfigurationPropertiesScan} finds {@code @ConfigurationProperties} records
 * (e.g. {@link com.planova.config.SecurityProperties}) without registering each one by hand.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PlanovaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanovaApplication.class, args);
    }
}
