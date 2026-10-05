package com.company.lms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A login limiter keyed on the account email is only safe if it cannot be turned against the account owner:
 * an attacker knows the email but not the password, so ten throwaway requests would otherwise lock out a
 * colleague. These tests pin both halves of that deal — guessers do get stopped, and owners do not.
 */
  // Pinned rather than inherited: the deployed budgets are deliberately looser, and these tests assert the
  // mechanism at a size that can be exercised in a second.
  @SpringBootTest(properties = { "app.mail.outbox.enabled=false",
      "app.rate-limit.login-attempts=" + LoginLockoutTests.ACCOUNT_BUDGET,
      "app.rate-limit.login-ip-attempts=" + LoginLockoutTests.IP_BUDGET })
  @AutoConfigureMockMvc
  class LoginLockoutTests {

    static final int ACCOUNT_BUDGET = 10;
    static final int IP_BUDGET = 40;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate db;
  @Autowired PasswordEncoder passwords;
  @Autowired com.company.lms.security.ThrottleService throttle;

  final ObjectMapper json = new ObjectMapper();
  final long victimId = 9301L;
  static final String VICTIM = "lockout-victim@test.local";
  static final String PASSWORD = "the-real-password";

  /** Each test drives its own source address so the shared in-memory counters cannot collide. */
  static final String ATTACKER_IP = "10.99.0.1";
  static final String VICTIM_IP = "10.99.0.2";
  static final String FLOOD_IP = "10.99.0.3";

  @BeforeEach
  void seed() {
    db.update("INSERT INTO users(id,employee_id,full_name,email,password_hash,role,is_active,password_setup_required,token_version) "
            + "VALUES(?,?,?,?,?,'employee',TRUE,FALSE,0) ON CONFLICT (id) DO UPDATE SET email=EXCLUDED.email, "
            + "password_hash=EXCLUDED.password_hash, is_active=TRUE, password_setup_required=FALSE, token_version=0",
        victimId, "LOCK-" + victimId, "Lockout Victim", VICTIM, passwords.encode(PASSWORD));
    // The limiter is one JVM-wide singleton and JUnit may run these in any order, so start from empty budgets.
    for (String ip : new String[] { ATTACKER_IP, VICTIM_IP, FLOOD_IP }) throttle.reset("login", VICTIM, ip);
  }

  @AfterEach
  void tidy() {
    db.update("DELETE FROM user_departments WHERE user_id=?", victimId);
    db.update("DELETE FROM users WHERE id=?", victimId);
  }

  private int attempt(String email, String password, String fromIp) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/login").remoteAddress(fromIp)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password))))
        .andReturn();
    return result.getResponse().getStatus();
  }

  @Test
  @DisplayName("ten wrong passwords lock the account, and the eleventh is refused without checking anything")
  void tenFailuresLockTheAccount() throws Exception {
    for (int attempt = 1; attempt <= ACCOUNT_BUDGET; attempt++) {
      assertEquals(401, attempt(VICTIM, "wrong-" + attempt, ATTACKER_IP), "attempt " + attempt);
    }
    MvcResult blocked = mockMvc.perform(post("/api/auth/login").remoteAddress(ATTACKER_IP)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("email", VICTIM, "password", "wrong-11"))))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.success").value(false))
        .andReturn();
    // The window is five minutes, not the old escalating 15 -> 120 minutes.
    long retryAfter = Long.parseLong(blocked.getResponse().getHeader("Retry-After"));
    assertTrue(retryAfter > 0 && retryAfter <= 300, "expected a 5 minute lockout, got " + retryAfter + "s");
    assertTrue(blocked.getResponse().getContentAsString().contains("5 minute"), blocked.getResponse().getContentAsString());
  }

  @Test
  @DisplayName("the real owner still signs in while the account is locked, and that clears the lockout")
  void lockedAccountOwnerStillLogsIn() throws Exception {
    for (int attempt = 1; attempt <= ACCOUNT_BUDGET + 1; attempt++) attempt(VICTIM, "wrong-" + attempt, ATTACKER_IP);
    assertEquals(429, attempt(VICTIM, "wrong-12", ATTACKER_IP), "guesser stays blocked");

    MvcResult owner = mockMvc.perform(post("/api/auth/login").remoteAddress(VICTIM_IP)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("email", VICTIM, "password", PASSWORD))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andReturn();

    // A genuine sign-in wiped the counters, so a subsequent wrong guess answers honestly again.
    assertEquals(401, attempt(VICTIM, "wrong-again", ATTACKER_IP), "lockout should be cleared, body: "
        + owner.getResponse().getContentAsString());
  }

  @Test
  @DisplayName("a spent per-IP budget is refused outright, because that caller is the problem")
  void exhaustedSourceIsRefusedBeforeVerification() throws Exception {
    for (int attempt = 1; attempt <= IP_BUDGET; attempt++) {
      attempt("nobody" + attempt + "@test.local", "wrong", FLOOD_IP);
    }
    // Even the correct password is not checked from an exhausted address: that is what bounds bcrypt cost.
    assertEquals(429, attempt(VICTIM, PASSWORD, FLOOD_IP));
    // The same account from a fresh address is untouched by that budget.
    assertEquals(200, attempt(VICTIM, PASSWORD, VICTIM_IP));
  }
}
