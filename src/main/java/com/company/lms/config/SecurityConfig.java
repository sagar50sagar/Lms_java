package com.company.lms.config;

import com.company.lms.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration @EnableWebSecurity @EnableMethodSecurity
public class SecurityConfig {
  // The static pages use inline scripts, and the certificate download pulls jsPDF from cdnjs,
  // so 'unsafe-inline' and that one origin have to stay or the pages stop working.
  private static final String CSP = String.join("; ",
      "default-src 'self'",
      "script-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com",
      "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdnjs.cloudflare.com",
      "font-src 'self' https://fonts.gstatic.com",
      "img-src 'self' data: blob: https://i.ytimg.com",
      "connect-src 'self'",
      // frame-src governs only what this page frames directly; a Drive preview loads its own media inside that
      // frame, so listing drive.google.com is sufficient for it to play.
      "frame-src https://www.youtube-nocookie.com https://drive.google.com",
      "object-src 'none'",
      "base-uri 'self'",
      "form-action 'self'",
      "frame-ancestors 'none'");

  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
  @Bean SecurityFilterChain security(HttpSecurity http, JwtAuthenticationFilter jwt) throws Exception { return http
    .csrf(csrf -> csrf.disable()).cors(cors -> {}).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(a -> a.requestMatchers("/api/auth/otp/**", "/api/auth/password/complete", "/api/auth/account-setup", "/api/auth/login", "/api/health", "/api/progress/certificates/verify/**", "/", "/**.html", "/css/**", "/js/**", "/images/**").permitAll().anyRequest().authenticated())
    .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
        .frameOptions(f -> f.deny())
        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
    .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class).build(); }
}
