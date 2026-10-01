package com.company.lms.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
public class ApiExceptionHandler {
 private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
 @ExceptionHandler(AccessDeniedException.class) ResponseEntity<?> forbidden() { return ResponseEntity.status(403).body(Map.of("success",false,"message","Access forbidden.")); }
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Map.of("success",false,"message","The record conflicts with existing data.")); }
 @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> mailUnavailable(IllegalStateException e) { return ResponseEntity.status(503).body(Map.of("success",false,"message",e.getMessage())); }
 @ExceptionHandler(Exception.class) ResponseEntity<?> error(Exception e) { log.error("Unhandled API exception", e); return ResponseEntity.status(500).body(Map.of("success",false,"message","An unexpected server error occurred.")); }
}
