package com.company.lms.web;

import org.springframework.security.core.Authentication;
import java.util.*;

abstract class ApiSupport {
  @SuppressWarnings("unchecked") Map<String,Object> user(Authentication authentication) { return (Map<String,Object>) authentication.getPrincipal(); }
  long userId(Authentication authentication) { return ((Number)user(authentication).get("id")).longValue(); }
  String role(Authentication authentication) { return (String)user(authentication).get("role"); }
  Map<String,Object> ok(Object key, Object value) { Map<String,Object> response = new LinkedHashMap<>(); response.put("success", true); response.put(String.valueOf(key), value); return response; }
  Map<String,Object> message(String value) { return Map.of("success",true,"message",value); }
  RuntimeException bad(String text) { return new IllegalArgumentException(text); }
}
