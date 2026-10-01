package com.company.lms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class EmailOtpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final JavaMailSender mail;
    private final String from;

    public EmailOtpService(JdbcTemplate db, PasswordEncoder passwords, JavaMailSender mail,
                           @Value("${app.mail.from:no-reply@company.local}") String from) {
        this.db = db;
        this.passwords = passwords;
        this.mail = mail;
        this.from = from;
    }

    @Transactional
    public void send(String email, String purpose, String employeeId) {
        String normalized = email.trim().toLowerCase();
        db.update("UPDATE email_otp_tokens SET consumed_at=CURRENT_TIMESTAMP WHERE LOWER(email)=LOWER(?) AND purpose=? AND consumed_at IS NULL", normalized, purpose);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        db.update("INSERT INTO email_otp_tokens(email,purpose,code_hash,expires_at) VALUES(?,?,?,?)",
                normalized, purpose, passwords.encode(code), OffsetDateTime.now().plusMinutes(10));

        String subject = switch (purpose) {
            case "login" -> "Your LMS sign-in code";
            case "password_setup" -> "Set up your LMS account";
            default -> "Reset your LMS password";
        };
        String action = switch (purpose) {
            case "login" -> "sign in";
            case "password_setup" -> "set your password";
            default -> "reset your password";
        };
        String idLine = employeeId == null || employeeId.isBlank() ? "" : "\nYour employee ID: " + employeeId + "\n";
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(normalized);
        message.setSubject(subject);
        message.setText("Use this one-time code to " + action + "." + idLine
                + "\nCode: " + code + "\n\nThis code expires in 10 minutes. Do not share it with anyone.");
        try {
            mail.send(message);
        } catch (MailException e) {
            throw new IllegalStateException("Unable to send the verification email. Please try again later.", e);
        }
    }

    @Transactional
    public boolean consume(String email, String purpose, String code) {
        List<Map<String, Object>> tokens = db.queryForList(
                "SELECT id,code_hash,attempts FROM email_otp_tokens WHERE LOWER(email)=LOWER(?) AND purpose=? AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP ORDER BY created_at DESC LIMIT 1 FOR UPDATE",
                email.trim(), purpose);
        if (tokens.isEmpty()) return false;
        Map<String,Object> token = tokens.getFirst();
        if (((Number) token.get("attempts")).intValue() >= 5 || !passwords.matches(code, (String) token.get("code_hash"))) {
            db.update("UPDATE email_otp_tokens SET attempts=attempts+1 WHERE id=?", token.get("id"));
            return false;
        }
        db.update("UPDATE email_otp_tokens SET consumed_at=CURRENT_TIMESTAMP WHERE id=?", token.get("id"));
        return true;
    }
}
