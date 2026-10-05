package com.company.lms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * One place that knows how to hand a message to a provider. The outbox dispatcher is the only
 * caller, so a provider outage or a hanging socket can no longer stall a web request.
 */
@Service
public class EmailTransport {

  public static final class SendingException extends RuntimeException {
    public SendingException(String message, Throwable cause) { super(message, cause); }
  }

  private final JavaMailSender smtp;
  private final String provider;
  private final String from;
  private final String apiKey;
  private final String apiUrl;

  public EmailTransport(JavaMailSender smtp,
      @Value("${app.mail.provider:smtp}") String provider,
      @Value("${app.mail.from:no-reply@company.local}") String from,
      @Value("${app.mail.api-key:}") String apiKey,
      @Value("${app.mail.api-url:}") String apiUrl) {
    this.smtp = smtp;
    this.provider = provider.trim().toLowerCase();
    this.from = from;
    this.apiKey = apiKey.trim();
    this.apiUrl = apiUrl.trim().isEmpty() ? switch (this.provider) {
      case "postmark" -> "https://api.postmarkapp.com/email";
      case "resend" -> "https://api.resend.com/emails";
      default -> "";
    } : apiUrl.trim();
  }

  public String providerName() { return provider; }

  /** Throws so the dispatcher can retry; never returns quietly after a rejected send. */
  public void send(String to, String subject, String body) {
    if (to == null || to.isBlank()) throw new SendingException("Recipient is missing.", null);
    switch (provider) {
      case "postmark" -> sendJson(to, subject, body,
          Map.of("From", from, "To", to, "Subject", subject, "TextBody", body), "X-Postmark-Server-Token");
      case "resend" -> sendJson(to, subject, body,
          Map.of("from", from, "to", List.of(to), "subject", subject, "text", body), "Authorization");
      case "smtp" -> sendSmtp(to, subject, body);
      default -> throw new SendingException("Unknown email provider '" + provider + "'.", null);
    }
  }

  private void sendSmtp(String to, String subject, String body) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(to);
    message.setSubject(subject);
    message.setText(body);
    try {
      smtp.send(message);
    } catch (RuntimeException e) {
      throw new SendingException(describe(e), e);
    }
  }

  private void sendJson(String to, String subject, String body, Map<String, ?> payload, String authHeader) {
    if (apiKey.isEmpty()) throw new SendingException("MAIL_API_KEY is not configured for provider '" + provider + "'.", null);
    String value = "Authorization".equals(authHeader) ? "Bearer " + apiKey : apiKey;
    try {
      RestClient.create().post().uri(apiUrl).header(authHeader, value)
          .header("Accept", "application/json").body(payload).retrieve().toBodilessEntity();
    } catch (RuntimeException e) {
      throw new SendingException(provider + " rejected the message to " + to + ": " + describe(e), e);
    }
  }

  private static String describe(Throwable e) {
    String message = e.getMessage();
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
    String detail = message == null || message.isBlank() ? String.valueOf(root.getMessage()) : message;
    return detail.length() > 480 ? detail.substring(0, 480) : detail;
  }
}
