package com.company.lms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class EmailOtpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final JavaMailSender mail;
    private final String from;
    private final String frontendUrl;

    public EmailOtpService(JdbcTemplate db, PasswordEncoder passwords, JavaMailSender mail,
                           @Value("${app.mail.from:no-reply@company.local}") String from,
                           @Value("${app.frontend-url:http://localhost:8080}") String frontendUrl) {
        this.db = db;
        this.passwords = passwords;
        this.mail = mail;
        this.from = from;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    @Transactional
    public void sendAccountSetupLink(long userId, String email, String employeeId) {
        db.update("UPDATE account_setup_tokens SET used_at=CURRENT_TIMESTAMP WHERE user_id=? AND used_at IS NULL", userId);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO account_setup_tokens(user_id,token_hash,expires_at) VALUES(?,?,?)", userId, sha256(token), OffsetDateTime.now().plusHours(24));
        String link = frontendUrl + "/setup.html?token=" + token;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.trim().toLowerCase());
        message.setSubject("Set up your LMS account");
        message.setText("Your LMS account has been created.\n\nEmployee ID: " + employeeId
                + "\n\nSet your password and activate your account using this one-time link:\n" + link
                + "\n\nThis link expires in 24 hours. Do not share it with anyone.");
        try {
            mail.send(message);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to send the account setup email. Please try again later.", e);
        }
    }

    @Transactional
    public boolean completeAccountSetup(String token, String password) {
        List<Map<String,Object>> tokens = db.queryForList("SELECT t.id,t.user_id FROM account_setup_tokens t JOIN users u ON u.id=t.user_id WHERE t.token_hash=? AND t.used_at IS NULL AND t.expires_at>CURRENT_TIMESTAMP AND u.is_active=TRUE AND u.password_setup_required=TRUE FOR UPDATE", sha256(token));
        if (tokens.isEmpty()) return false;
        Map<String,Object> setup = tokens.getFirst();
        db.update("UPDATE account_setup_tokens SET used_at=CURRENT_TIMESTAMP WHERE id=?", setup.get("id"));
        db.update("UPDATE users SET password_hash=?,password_setup_required=FALSE WHERE id=?", passwords.encode(password), setup.get("user_id"));
        return true;
    }

    private String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Secure token hashing is unavailable.", e);
        }
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
        } catch (RuntimeException e) {
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
