package com.company.lms.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start a production instance that is still relying on development defaults.
 * Runs during context initialisation, before the demo data seed can write any rows.
 */
@Component
public class ProductionGuard {

  /** The fallback in application.yml. Public because it is a known value, so production must never use it. */
  private static final String DEV_JWT_SECRET = "VGhpcy1pcy1hLWRldmVsb3BtZW50LW9ubHktc2VjcmV0LWNoYW5nZS1tZQ==";
  private static final List<String> DEV_DB_PASSWORDS = List.of("", "1234", "postgres", "password");

  public ProductionGuard(Environment env) {
    if (!"production".equalsIgnoreCase(env.getProperty("app.environment", "development").trim())) return;

    List<String> problems = new ArrayList<>();
    String secret = env.getProperty("app.jwt.secret", "");
    if (DEV_JWT_SECRET.equals(secret)) {
      problems.add("JWT_SECRET is still the development secret published in this repository; anyone with repo access can mint admin tokens.");
    } else if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      problems.add("JWT_SECRET must be at least 32 bytes of unpredictable data.");
    }

    if (DEV_DB_PASSWORDS.contains(env.getProperty("spring.datasource.password", "").trim())) {
      problems.add("DB_PASSWORD must be replaced with a strong credential.");
    }
    if (env.getProperty("app.demo-data", Boolean.class, true)) {
      problems.add("app.demo-data must be false; it seeds accounts with well-known passwords.");
    }
    String frontend = env.getProperty("app.frontend-url", "");
    if (frontend.isBlank() || frontend.contains("localhost")) {
      problems.add("FRONTEND_URL must be the public HTTPS origin, so account setup links are not sent to localhost.");
    }
    String adminPassword = env.getProperty("app.initial-admin.password", "");
    if (!adminPassword.isBlank() && adminPassword.length() < 12) {
      problems.add("INITIAL_ADMIN_PASSWORD must be at least 12 characters.");
    }
    long expiration = env.getProperty("app.jwt.expiration", Long.class, 604800000L);
    if (expiration > 12L * 60 * 60 * 1000) {
      problems.add("JWT_EXPIRATION is " + expiration / 3600000 + " hours; a stolen token outlives the workday. Use 12 hours (43200000) or less.");
    }
    String provider = env.getProperty("app.mail.provider", "smtp").trim().toLowerCase();
    switch (provider) {
      case "postmark", "resend" -> {
        if (env.getProperty("app.mail.api-key", "").isBlank()) {
          problems.add("MAIL_API_KEY must be set because MAIL_PROVIDER=" + provider + ".");
        }
      }
      case "smtp" -> {
        String host = env.getProperty("spring.mail.host", "").trim();
        if (host.isBlank() || host.equalsIgnoreCase("localhost")) {
          problems.add("MAIL_HOST must be a real SMTP server; account setup links cannot reach anyone from localhost.");
        }
      }
      default -> problems.add("MAIL_PROVIDER must be smtp, postmark or resend, not '" + provider + "'.");
    }
    if (!env.getProperty("app.mail.outbox.enabled", Boolean.class, true)) {
      problems.add("MAIL_OUTBOX_ENABLED=false delivers nothing; setup links and codes would silently vanish.");
    }

    if (!problems.isEmpty()) {
      throw new IllegalStateException("Refusing to start with app.environment=production:\n - " + String.join("\n - ", problems));
    }
  }
}
