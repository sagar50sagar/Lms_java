package com.company.lms.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
public class ApiExceptionHandler {
 @ExceptionHandler(AccessDeniedException.class) ResponseEntity<?> forbidden() { return ResponseEntity.status(403).body(Map.of("success",false,"message","Access forbidden.")); }
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Map.of("success",false,"message","The record conflicts with existing data.")); }
 @ExceptionHandler(Exception.class) ResponseEntity<?> error(Exception e) { return ResponseEntity.status(500).body(Map.of("success",false,"message","An unexpected server error occurred.")); }
}
