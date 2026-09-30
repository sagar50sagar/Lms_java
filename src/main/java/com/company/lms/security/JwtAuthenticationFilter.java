package com.company.lms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private final JwtService jwt; private final JdbcTemplate db;
  public JwtAuthenticationFilter(JwtService jwt, JdbcTemplate db) { this.jwt=jwt; this.db=db; }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String header=request.getHeader("Authorization"); String token=header != null && header.startsWith("Bearer ") ? header.substring(7) : request.getParameter("token");
    if (token != null && SecurityContextHolder.getContext().getAuthentication()==null) try {
      Map<String,Object> user=db.queryForMap("SELECT id, employee_id, full_name, email, role, department_id, designation, is_active FROM users WHERE id=?", jwt.userId(token));
      if (Boolean.TRUE.equals(user.get("is_active"))) {
        String role=(String)user.get("role"); var auth=new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_"+role.toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(auth);
      }
    } catch (Exception ignored) { }
    chain.doFilter(request,response);
  }
}
