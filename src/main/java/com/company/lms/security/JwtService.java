package com.company.lms.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {
  /** A validated token's subject and the session generation it was issued under. */
  public record Principal(long userId, int tokenVersion) { }

  private final SecretKey key;
  private final long expiration;
  public JwtService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.expiration}") long expiration) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); this.expiration = expiration;
  }
  public String createToken(long userId, String email, String role, int tokenVersion) {
    return Jwts.builder().subject(String.valueOf(userId)).claim("email", email).claim("role", role).claim("tv", tokenVersion)
      .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + expiration)).signWith(key).compact();
  }
  /** Throws for a forged, expired or malformed token; the caller treats that as anonymous. */
  public Principal parse(String token) {
    var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    Number version = claims.get("tv", Number.class);
    // -1 never matches a stored version, so a token minted before revocation existed is rejected.
    return new Principal(Long.parseLong(claims.getSubject()), version == null ? -1 : version.intValue());
  }
}
