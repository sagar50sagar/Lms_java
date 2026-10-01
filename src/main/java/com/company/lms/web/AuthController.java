package com.company.lms.web;

import com.company.lms.security.JwtService;
import com.company.lms.service.EmailOtpService;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/auth")
public class AuthController extends ApiSupport {
 private final JdbcTemplate db; private final PasswordEncoder passwords; private final JwtService jwt; private final EmailOtpService otp;
 public AuthController(JdbcTemplate db, PasswordEncoder passwords, JwtService jwt, EmailOtpService otp) {this.db=db;this.passwords=passwords;this.jwt=jwt;this.otp=otp;}
 @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Map<String,Object> body) {
   String email=string(body,"email"), password=string(body,"password"); if(email==null||password==null) return fail(400,"Please provide both email and password.");
   List<Map<String,Object>> found=db.queryForList("SELECT u.id,u.employee_id,u.full_name,u.email,u.password_hash,u.role,u.department_id,u.designation,u.is_active,d.name department_name FROM users u LEFT JOIN departments d ON d.id=u.department_id WHERE LOWER(u.email)=LOWER(?)",email.trim());
   if(found.isEmpty() || !passwords.matches(password,(String)found.getFirst().get("password_hash"))) return fail(401,"Invalid email or password.");
   Map<String,Object> user=new LinkedHashMap<>(found.getFirst()); if(!Boolean.TRUE.equals(user.get("is_active"))) return fail(403,"This account has been deactivated. Please contact HR."); user.remove("password_hash");
   return ResponseEntity.ok(Map.of("success",true,"message","Login successful.","token",jwt.createToken(((Number)user.get("id")).longValue(),(String)user.get("email"),(String)user.get("role")),"user",user));
 }
 @PostMapping("/otp/request") public ResponseEntity<?> requestOtp(@RequestBody Map<String,Object> body) {
   String email=string(body,"email"), purpose=string(body,"purpose");
   if(email==null || !List.of("login","password_reset").contains(purpose)) return fail(400,"Email and a valid OTP purpose are required.");
   List<Map<String,Object>> users=db.queryForList("SELECT employee_id,is_active FROM users WHERE LOWER(email)=LOWER(?)",email.trim());
   // Keep this response neutral so an endpoint cannot be used to enumerate accounts.
   if(!users.isEmpty() && Boolean.TRUE.equals(users.getFirst().get("is_active"))) otp.send(email,purpose,(String)users.getFirst().get("employee_id"));
   return ResponseEntity.ok(Map.of("success",true,"message","If an active account exists for that email, a verification code has been sent."));
 }
 @PostMapping("/otp/verify-login") public ResponseEntity<?> verifyLoginOtp(@RequestBody Map<String,Object> body) {
   String email=string(body,"email"), code=string(body,"code"); if(email==null||code==null) return fail(400,"Email and verification code are required.");
   if(!otp.consume(email,"login",code)) return fail(400,"The verification code is invalid or has expired.");
   List<Map<String,Object>> found=db.queryForList("SELECT u.id,u.employee_id,u.full_name,u.email,u.role,u.department_id,u.designation,u.is_active,d.name department_name FROM users u LEFT JOIN departments d ON d.id=u.department_id WHERE LOWER(u.email)=LOWER(?)",email.trim());
   if(found.isEmpty() || !Boolean.TRUE.equals(found.getFirst().get("is_active"))) return fail(403,"This account is unavailable. Please contact HR.");
   Map<String,Object> user=found.getFirst();
   return ResponseEntity.ok(Map.of("success",true,"message","Login successful.","token",jwt.createToken(((Number)user.get("id")).longValue(),(String)user.get("email"),(String)user.get("role")),"user",user));
 }
 @PostMapping("/password/complete") public ResponseEntity<?> completePassword(@RequestBody Map<String,Object> body) {
   String email=string(body,"email"), code=string(body,"code"), password=string(body,"password"), purpose=string(body,"purpose");
   if(email==null||code==null||password==null||!List.of("password_setup","password_reset").contains(purpose)) return fail(400,"Email, code, password, and a valid purpose are required.");
   if(password.length()<8) return fail(400,"Password must be at least 8 characters long.");
   if(!otp.consume(email,purpose,code)) return fail(400,"The verification code is invalid or has expired.");
   int updated=db.update("UPDATE users SET password_hash=? WHERE LOWER(email)=LOWER(?) AND is_active=TRUE",passwords.encode(password),email.trim());
   return updated==0?fail(404,"Account not found."):ResponseEntity.ok(message("Password updated. You can now sign in."));
 }
 @GetMapping("/me") public Map<String,Object> me(Authentication a) { return ok("user",user(a)); }
 private String string(Map<String,Object>b,String k){Object v=b.get(k);return v==null?null:v.toString();}
 private ResponseEntity<?> fail(int s,String m){return ResponseEntity.status(s).body(Map.of("success",false,"message",m));}
}
