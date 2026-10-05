package com.company.lms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Drains the outbox on a timer. Sending happens after the claim commits, so a provider that hangs
 * holds a scheduler thread instead of a pooled database connection and a request worker.
 */
@Component
@ConditionalOnProperty(name = "app.mail.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class EmailOutboxDispatcher {

  private static final Logger log = LoggerFactory.getLogger(EmailOutboxDispatcher.class);

  private final EmailOutboxService outbox;
  private final EmailTransport transport;
  private final int batchSize;
  private final int maxAttempts;
  private final Duration backoff;

  public EmailOutboxDispatcher(EmailOutboxService outbox, EmailTransport transport,
      @Value("${app.mail.outbox.batch-size:10}") int batchSize,
      @Value("${app.mail.outbox.max-attempts:5}") int maxAttempts,
      @Value("${app.mail.outbox.retry-base-seconds:60}") long retryBaseSeconds) {
    this.outbox = outbox;
    this.transport = transport;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.backoff = Duration.ofSeconds(retryBaseSeconds);
  }

  @Scheduled(fixedDelayString = "${app.mail.outbox.poll-ms:2000}", initialDelayString = "${app.mail.outbox.initial-delay-ms:5000}")
  public void deliverDueMessages() {
    List<EmailOutboxService.Pending> due;
    try {
      due = outbox.claim(batchSize, Duration.ofMinutes(5));
    } catch (RuntimeException e) {
      log.warn("Could not claim email outbox rows: {}", e.getMessage());
      return;
    }
    for (EmailOutboxService.Pending message : due) {
      try {
        transport.send(message.toEmail(), message.subject(), message.body());
        outbox.markSent(message.id());
      } catch (RuntimeException e) {
        outbox.markFailed(message.id(), message.attempts(), maxAttempts, backoff, e.getMessage());
        boolean exhausted = message.attempts() >= maxAttempts;
        log.error("Email to {} failed on attempt {} of {}{}. Reason: {}", message.toEmail(),
            message.attempts(), maxAttempts, exhausted ? " - marked failed" : ", will retry", e.getMessage());
      }
    }
  }
}
