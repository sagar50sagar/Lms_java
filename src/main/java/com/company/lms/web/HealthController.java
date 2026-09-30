package com.company.lms.web;
import org.springframework.web.bind.annotation.*; import java.time.Instant; import java.util.Map;
@RestController public class HealthController { @GetMapping("/api/health") Map<String,Object> health(){return Map.of("status","online","system","Corporate Employee Training & Compliance LMS","roles",new String[]{"admin","trainer","employee"},"timestamp", Instant.now());} }
