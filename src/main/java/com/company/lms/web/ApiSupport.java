package com.company.lms.web;

import org.springframework.security.core.Authentication;
import java.util.*;

abstract class ApiSupport {
  @SuppressWarnings("unchecked")
  Map<String,Object> user(Authentication authentication) { return (Map<String,Object>) authentication.getPrincipal(); }

  static Object val(Map<String,Object> map, String key) {
    if (map == null) return null;
    if (map.containsKey(key)) return map.get(key);
    for (Map.Entry<String,Object> entry : map.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
    }
    return null;
  }

  long userId(Authentication authentication) {
    Object idObj = val(user(authentication), "id");
    return idObj != null ? ((Number) idObj).longValue() : 0L;
  }

  String role(Authentication authentication) {
    Object roleObj = val(user(authentication), "role");
    return roleObj != null ? roleObj.toString() : "";
  }

  Map<String,Object> ok(Object key, Object value) { Map<String,Object> response = new LinkedHashMap<>(); response.put("success", true); response.put(String.valueOf(key), value); return response; }
  Map<String,Object> message(String value) { return Map.of("success",true,"message",value); }
  RuntimeException bad(String text) { return new IllegalArgumentException(text); }
}
