package com.company.lms.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Email is written here inside the caller's transaction and delivered later, so a web request
 * never waits on a provider and a failed send never takes the database write down with it.
 */
@Service
public class EmailOutboxService {

  /** A claimed row: the dispatcher has committed the claim before touching the network. */
  public record Pending(long id, String toEmail, String subject, String body, int attempts) { }

  private final JdbcTemplate db;

  public EmailOutboxService(JdbcTemplate db) { this.db = db; }

  @Transactional
  public void enqueue(String toEmail, String subject, String body, String purpose) {
    db.update("INSERT INTO email_outbox(to_email,subject,body_text,purpose) VALUES(?,?,?,?)",
        toEmail, subject, body, purpose);
  }

  /**
   * Claims due rows atomically and returns them already marked 'sending', so the claim survives the
   * commit and the network attempt happens outside any transaction. SKIP LOCKED keeps two dispatcher
   * instances from taking the same row, and rows abandoned by a crash are released first.
   */
  @Transactional
  public List<Pending> claim(int limit, Duration abandonedAfter) {
    db.update("UPDATE email_outbox SET status='pending', next_attempt_at=CURRENT_TIMESTAMP "
        + "WHERE status='sending' AND claimed_at < CURRENT_TIMESTAMP - ? * INTERVAL '1 second'", abandonedAfter.getSeconds());
    return db.query("WITH due AS (SELECT id FROM email_outbox "
        + "WHERE status='pending' AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED) "
        + "UPDATE email_outbox e SET status='sending', attempts=e.attempts+1, claimed_at=CURRENT_TIMESTAMP "
        + "FROM due WHERE e.id=due.id RETURNING e.id,e.to_email,e.subject,e.body_text,e.attempts",
        (rs, rowNum) -> new Pending(rs.getLong("id"), rs.getString("to_email"), rs.getString("subject"),
            rs.getString("body_text"), rs.getInt("attempts")),
        limit);
  }

  @Transactional
  public void markSent(long id) {
    db.update("UPDATE email_outbox SET status='sent', sent_at=CURRENT_TIMESTAMP, last_error=NULL WHERE id=?", id);
  }

  /** Doubles the wait each time, then stops so a permanently bad address cannot spin forever. */
  @Transactional
  public void markFailed(long id, int attempts, int maxAttempts, Duration baseBackoff, String error) {
    String trimmed = error == null ? "unknown error" : (error.length() > 500 ? error.substring(0, 500) : error);
    if (attempts >= maxAttempts) {
      db.update("UPDATE email_outbox SET status='failed', last_error=? WHERE id=?", trimmed, id);
      return;
    }
    long delaySeconds = baseBackoff.getSeconds() * (1L << Math.min(attempts, 6));
    db.update("UPDATE email_outbox SET status='pending', last_error=?, next_attempt_at=CURRENT_TIMESTAMP + ? * INTERVAL '1 second' WHERE id=?",
        trimmed, delaySeconds, id);
  }
}
