package com.insightgiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Accounts authenticate with opaque device tokens (see account.BearerTokenAuthenticationFilter),
// so Boot's generated in-memory user is never used.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class InsightGiverApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightGiverApplication.class, args);
    }
}
