package com.company.lms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Creates the first administrator only for an otherwise admin-less deployment. */
@Configuration
class InitialAdminBootstrap {
    private static final Logger log = LoggerFactory.getLogger(InitialAdminBootstrap.class);

    @Bean @Order(-100)
    CommandLineRunner initialAdmin(JdbcTemplate db, PasswordEncoder passwords,
                                  @Value("${app.initial-admin.email:}") String email,
                                  @Value("${app.initial-admin.password:}") String password,
                                  @Value("${app.initial-admin.full-name:Initial Administrator}") String fullName) {
        return args -> {
            Integer admins = db.queryForObject("SELECT COUNT(*) FROM users WHERE role='admin'", Integer.class);
            if (admins != null && admins > 0) return;
            if (email.isBlank() || password.isBlank()) {
                log.warn("No administrator exists. Set INITIAL_ADMIN_EMAIL and INITIAL_ADMIN_PASSWORD to bootstrap the first admin.");
                return;
            }
            if (password.length() < 8) {
                log.error("Initial administrator was not created because INITIAL_ADMIN_PASSWORD is shorter than 8 characters.");
                return;
            }
            db.update("INSERT INTO users(employee_id,full_name,email,password_hash,role,designation,password_setup_required) VALUES(?,?,?,?,?,?,FALSE)",
                    "ADMIN-001", fullName.trim(), email.trim().toLowerCase(), passwords.encode(password), "admin", "LMS Administrator");
            log.info("Initial LMS administrator created for {}.", email.trim().toLowerCase());
        };
    }
}
