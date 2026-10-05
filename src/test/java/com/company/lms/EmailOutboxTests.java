package com.company.lms;

import com.company.lms.security.JwtService;
import com.company.lms.service.EmailOutboxService;
import com.company.lms.service.EmailTransport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Email must never run inside a web request: the old code held a pooled connection for the whole
 * SMTP handshake, so several admins at once starved the pool. These tests pin the queue behaviour.
 */
@SpringBootTest(properties = "app.mail.outbox.enabled=false")
@AutoConfigureMockMvc
class EmailOutboxTests {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate db;
  @Autowired JwtService jwt;
  @Autowired EmailOutboxService outbox;

  String queuedTo;
  long createdUserId;

  String adminToken() {
    long adminId = db.queryForObject("SELECT id FROM users WHERE role='admin' ORDER BY id LIMIT 1", Long.class);
    int version = db.queryForObject("SELECT token_version FROM users WHERE id=?", Integer.class, adminId);
    return "Bearer " + jwt.createToken(adminId, "admin@company.local", "admin", version);
  }

  @AfterEach
  void cleanUp() {
    if (queuedTo != null) db.update("DELETE FROM email_outbox WHERE to_email=?", queuedTo);
    if (createdUserId != 0) {
      db.update("DELETE FROM account_setup_tokens WHERE user_id=?", createdUserId);
      db.update("DELETE FROM user_departments WHERE user_id=?", createdUserId);
      db.update("DELETE FROM users WHERE id=?", createdUserId);
      createdUserId = 0;
    }
    queuedTo = null;
  }

  @Test
  void userCreationQueuesMailInsteadOfSendingItInTheRequest() throws Exception {
    queuedTo = "outbox-" + System.currentTimeMillis() + "@test.local";
    mockMvc.perform(post("/api/admin/users").header("Authorization", adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"full_name\":\"Queue Only User\",\"email\":\"" + queuedTo + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.id").exists());

    createdUserId = db.queryForObject("SELECT id FROM users WHERE LOWER(email)=LOWER(?)", Long.class, queuedTo);
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT status,purpose,to_email,body_text,attempts,next_attempt_at FROM email_outbox WHERE to_email=?", queuedTo);
    assertEquals(1, rows.size(), "exactly one message should be queued per setup link");
    Map<String, Object> row = rows.getFirst();
    assertEquals("account_setup", row.get("purpose"));
    assertEquals("pending", row.get("status"));
    assertEquals(0, ((Number) row.get("attempts")).intValue());
    assertTrue(row.get("body_text").toString().contains("/setup.html?token="),
        "the queued body must carry the same one-time link the request path used to send");
    // The setup token is written with the queue row, so a failed send can be retried without re-issuing.
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM account_setup_tokens WHERE user_id=?", Integer.class, createdUserId));
  }

  @Test
  void claimMarksRowsSendingAndHoldsThemUntilAbandoned() {
    queuedTo = "claim-" + System.currentTimeMillis() + "@test.local";
    outbox.enqueue(queuedTo, "Subject", "Body", "login");
    long id = db.queryForObject("SELECT id FROM email_outbox WHERE to_email=?", Long.class, queuedTo);

    List<EmailOutboxService.Pending> claimed = outbox.claim(50, Duration.ofMinutes(5));
    assertTrue(claimed.stream().anyMatch(p -> p.id() == id), "the new row should be claimed");
    assertEquals("sending", db.queryForObject("SELECT status FROM email_outbox WHERE id=?", String.class, id));
    assertEquals(1, db.queryForObject("SELECT attempts FROM email_outbox WHERE id=?", Integer.class, id));
    assertFalse(outbox.claim(50, Duration.ofMinutes(5)).stream().anyMatch(p -> p.id() == id),
        "an in-flight row must not be handed out twice");

    db.update("UPDATE email_outbox SET claimed_at=CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE id=?", id);
    assertTrue(outbox.claim(50, Duration.ofMinutes(5)).stream().anyMatch(p -> p.id() == id),
        "a row abandoned by a crash has to become eligible again");
  }

  @Test
  void failedSendsBackOffAndThenStop() {
    queuedTo = "retry-" + System.currentTimeMillis() + "@test.local";
    outbox.enqueue(queuedTo, "Subject", "Body", "login");
    long id = db.queryForObject("SELECT id FROM email_outbox WHERE to_email=?", Long.class, queuedTo);

    outbox.markFailed(id, 1, 3, Duration.ofMinutes(1), "smtp connect timed out");
    Map<String, Object> retrying = db.queryForMap(
        "SELECT status,last_error,(next_attempt_at>CURRENT_TIMESTAMP) AS scheduled_later FROM email_outbox WHERE id=?", id);
    assertEquals("pending", retrying.get("status"));
    assertEquals("smtp connect timed out", retrying.get("last_error"));
    assertEquals(Boolean.TRUE, retrying.get("scheduled_later"), "the retry must be pushed into the future, not spun immediately");

    outbox.markSent(id);
    Map<String, Object> sent = db.queryForMap("SELECT status,sent_at,last_error FROM email_outbox WHERE id=?", id);
    assertEquals("sent", sent.get("status"));
    assertNotNull(sent.get("sent_at"));
    assertNull(sent.get("last_error"));

    outbox.enqueue(queuedTo, "Subject", "Body", "login");
    long exhausted = db.queryForObject("SELECT id FROM email_outbox WHERE to_email=? ORDER BY id DESC LIMIT 1", Long.class, queuedTo);
    outbox.markFailed(exhausted, 3, 3, Duration.ofMinutes(1), "mailbox unknown");
    assertEquals("failed", db.queryForObject("SELECT status FROM email_outbox WHERE id=?", String.class, exhausted));
  }

  @Test
  void httpProvidersRefuseToSendWithoutTheirKeyAndUnknownProvidersAreRejected() {
    EmailTransport postmark = new EmailTransport(null, "postmark", "training@company.test", "", "");
    EmailTransport.SendingException missingKey = assertThrows(EmailTransport.SendingException.class,
        () -> postmark.send("someone@company.test", "Setup", "Body"));
    assertTrue(missingKey.getMessage().contains("MAIL_API_KEY"), missingKey.getMessage());

    EmailTransport unsupported = new EmailTransport(null, "mailgun", "training@company.test", "key", "");
    assertThrows(EmailTransport.SendingException.class, () -> unsupported.send("someone@company.test", "Setup", "Body"));
  }
}
