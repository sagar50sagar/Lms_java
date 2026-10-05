package com.company.lms.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
 private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
 private static Map<String,Object> body(String message) { return Map.of("success",false,"message",message); }
 @ExceptionHandler(AccessDeniedException.class) ResponseEntity<?> forbidden() { return ResponseEntity.status(403).body(body("Access forbidden.")); }
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> badRequest(IllegalArgumentException e) { return ResponseEntity.status(400).body(body(e.getMessage()==null?"Invalid request.":e.getMessage())); }
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(body("The record conflicts with existing data.")); }
 @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> serviceUnavailable(IllegalStateException e) { return ResponseEntity.status(503).body(body(e.getMessage())); }
 // A typo'd JSON body, a wrong path, a wrong verb and a non-numeric id are client mistakes. Without
 // these the Exception handler below reports them as 500s, which hides the faults worth chasing.
 @ExceptionHandler({NoResourceFoundException.class,NoHandlerFoundException.class}) ResponseEntity<?> notFound() { return ResponseEntity.status(404).body(body("Resource not found.")); }
 @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<?> wrongMethod() { return ResponseEntity.status(405).body(body("That method is not allowed here.")); }
 @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> unreadable() { return ResponseEntity.status(400).body(body("The request body is missing or not valid JSON.")); }
 @ExceptionHandler(MethodArgumentTypeMismatchException.class) ResponseEntity<?> badParameter() { return ResponseEntity.status(400).body(body("A URL parameter is not a valid value for this request.")); }
 @ExceptionHandler(Exception.class) ResponseEntity<?> error(Exception e) { log.error("Unhandled API exception", e); return ResponseEntity.status(500).body(body("An unexpected server error occurred.")); }
}
