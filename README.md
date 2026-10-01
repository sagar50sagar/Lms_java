# Corporate Training LMS — Spring Boot Maven

This enterprise-style employee training and compliance LMS keeps its browser portal but replaces Node/Express with Spring Boot while preserving the `/api` contract.

## Run it

1. Install Java 21+ and PostgreSQL, then create `lms_db`.
2. Configure `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, a long `JWT_SECRET`, SMTP (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`), and initial-admin credentials (`INITIAL_ADMIN_EMAIL`, `INITIAL_ADMIN_PASSWORD`; see `.env.example`). Use an IDE run configuration or shell variables.
3. Start it with:

```powershell
mvn spring-boot:run
```

Open `http://localhost:8080`. Flyway creates the schema and local demo data includes `admin@company.local/admin123`, `trainer@company.local/trainer123`, and `employee@company.local/employee123`.

Build a deployable JAR with `mvn clean package`, then run `java -jar target/corporate-lms-1.0.0.jar`.

## Architecture to learn

```
Browser static pages → Spring MVC REST controllers → PostgreSQL
                         ↓
               JWT filter + role authorization
```

| Location | Purpose |
| --- | --- |
| `pom.xml` | Maven build and dependency definition. |
| `src/main/java/com/company/lms/LmsApplication.java` | Spring Boot entry point. |
| `config/SecurityConfig.java` | Stateless security and route rules. |
| `security/` | JWT creation and per-request authentication. |
| `web/` | REST endpoints and centralized error responses. |
| `resources/db/migration/` | Flyway versioned schema migrations. |
| `resources/static/` | Existing HTML/CSS/JavaScript portal, now served by Boot. |

## Enterprise practices included

- Standard Maven layout and executable Spring Boot JAR
- PostgreSQL connection pooling via HikariCP
- Flyway owns the database schema; add a `V2__...sql` migration instead of changing an applied migration
- BCrypt password hashing and stateless signed JWT authentication
- Admin/trainer/employee role checks; only admins can delete courses or provision user accounts
- Email OTP sign-in and password reset (six-digit codes are hashed, expire after 10 minutes, and are single-use)
- New trainer/employee accounts receive a one-time setup link instead; its SHA-256 hash is stored, it expires after 24 hours, and it is invalidated after use or when an admin resends it
- On a deployment with no admin, `INITIAL_ADMIN_EMAIL` and `INITIAL_ADMIN_PASSWORD` bootstrap exactly one admin; admins then create trainer/employee accounts that must complete emailed password setup before signing in
- Parameterized SQL with `JdbcTemplate` and consistent JSON error responses

For a real production rollout, add dev/test/prod profiles, vault-managed secrets, token revocation, audit trails, structured logging, Testcontainers integration tests, pagination, rate limits, and CI security scanning.

## Suggested learning order

Read `LmsApplication`, then trace login through `AuthController`, `JwtService`, and `JwtAuthenticationFilter`. Next compare the original Express controllers in the legacy `src/` subfolders with the Spring `web/` controllers. Maven compiles only `src/main/java`, so the old source remains as a safe reference until you are ready to remove it.
