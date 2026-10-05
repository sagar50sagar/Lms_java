package com.company.lms.web;

import com.company.lms.security.JwtService;
import com.company.lms.security.ThrottleService;
import com.company.lms.service.EmailOtpService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/auth")
public class AuthController extends ApiSupport {
 private final JdbcTemplate db; private final PasswordEncoder passwords; private final JwtService jwt; private final EmailOtpService otp; private final ThrottleService throttle;
 public AuthController(JdbcTemplate db, PasswordEncoder passwords, JwtService jwt, EmailOtpService otp, ThrottleService throttle) {this.db=db;this.passwords=passwords;this.jwt=jwt;this.otp=otp;this.throttle=throttle;}
 @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Map<String,Object> body, HttpServletRequest request) {
   String email=string(body,"email"), password=string(body,"password"); if(email==null||password==null) return fail(400,"Please provide both email and password.");
   String account=email.trim().toLowerCase(), ip=throttle.clientIp(request);
   ThrottleService.Decision limited=throttle.check("login",account,ip);
   // Only a spent IP budget refuses before verification. A spent account budget still has to check the
   // password, otherwise anyone who merely knows an email could lock its real owner out of the LMS.
   if(!limited.allowed() && limited.blocksSource()) return tooManyRequests(limited);
   List<Map<String,Object>> found=db.queryForList("SELECT u.id,u.employee_id,u.full_name,u.email,u.password_hash,u.role,u.department_id,u.designation,u.is_active,u.password_setup_required,u.token_version,d.name department_name FROM users u LEFT JOIN departments d ON d.id=u.department_id WHERE LOWER(u.email)=LOWER(?)",email.trim());
   if(found.isEmpty() || !passwords.matches(password,(String)found.getFirst().get("password_hash")))
     return !limited.allowed() ? tooManyRequests(limited) : fail(401,"Invalid email or password.");
   Map<String,Object> user=new LinkedHashMap<>(found.getFirst()); if(!Boolean.TRUE.equals(user.get("is_active"))) return fail(403,"This account has been deactivated. Please contact HR."); if(Boolean.TRUE.equals(user.get("password_setup_required"))) return fail(403,"Complete password setup using the link sent to your email."); user.remove("password_hash"); user.remove("password_setup_required");
   throttle.reset("login",account,ip);
   String token=issueToken(user); user.remove("token_version");
   return ResponseEntity.ok(Map.of("success",true,"message","Login successful.","token",token,"user",user));
 }
 @PostMapping("/otp/request") public ResponseEntity<?> requestOtp(@RequestBody Map<String,Object> body, HttpServletRequest request) {
   String email=string(body,"email"), purpose=string(body,"purpose");
   if(!validEmail(email) || !List.of("login","password_reset").contains(purpose)) return fail(400,"Email and a valid OTP purpose are required.");
   String account=email.trim().toLowerCase();
   ThrottleService.Decision limited=throttle.check("otp-request",account,throttle.clientIp(request)); if(!limited.allowed()) return tooManyRequests(limited);
   List<Map<String,Object>> users=db.queryForList("SELECT employee_id,is_active FROM users WHERE LOWER(email)=LOWER(?)",email.trim());
   // Keep this response neutral so an endpoint cannot be used to enumerate accounts.
   if(!users.isEmpty() && Boolean.TRUE.equals(users.getFirst().get("is_active"))) otp.send(email,purpose,(String)users.getFirst().get("employee_id"));
   return ResponseEntity.ok(Map.of("success",true,"message","If an active account exists for that email, a verification code has been sent."));
 }
 @PostMapping("/otp/verify-login") public ResponseEntity<?> verifyLoginOtp(@RequestBody Map<String,Object> body, HttpServletRequest request) {
   String email=string(body,"email"), code=string(body,"code"); if(!validEmail(email)||code==null) return fail(400,"Email and verification code are required.");
   String account=email.trim().toLowerCase();
   ThrottleService.Decision limited=throttle.check("otp-verify",account,throttle.clientIp(request)); if(!limited.allowed()) return tooManyRequests(limited);
   if(!otp.consume(email,"login",code)) return fail(400,"The verification code is invalid or has expired.");
   List<Map<String,Object>> found=db.queryForList("SELECT u.id,u.employee_id,u.full_name,u.email,u.role,u.department_id,u.designation,u.is_active,u.password_setup_required,u.token_version,d.name department_name FROM users u LEFT JOIN departments d ON d.id=u.department_id WHERE LOWER(u.email)=LOWER(?)",email.trim());
   if(found.isEmpty() || !Boolean.TRUE.equals(found.getFirst().get("is_active"))) return fail(403,"This account is unavailable. Please contact HR.");
   if(Boolean.TRUE.equals(found.getFirst().get("password_setup_required"))) return fail(403,"Complete password setup using the link sent to your email.");
   Map<String,Object> user=found.getFirst();
   throttle.reset("otp-verify",account,throttle.clientIp(request));
   String token=issueToken(user); user.remove("password_setup_required"); user.remove("token_version");
   return ResponseEntity.ok(Map.of("success",true,"message","Login successful.","token",token,"user",user));
 }
 @PostMapping("/password/complete") public ResponseEntity<?> completePassword(@RequestBody Map<String,Object> body, HttpServletRequest request) {
   String email=string(body,"email"), code=string(body,"code"), password=string(body,"password"), purpose=string(body,"purpose");
   if(!validEmail(email)||code==null||password==null||!List.of("password_setup","password_reset").contains(purpose)) return fail(400,"Email, code, password, and a valid purpose are required.");
   if(password.length()<8) return fail(400,"Password must be at least 8 characters long.");
   ThrottleService.Decision limited=throttle.check("password-complete",email.trim().toLowerCase(),throttle.clientIp(request)); if(!limited.allowed()) return tooManyRequests(limited);
   if(!otp.consume(email,purpose,code)) return fail(400,"The verification code is invalid or has expired.");
   int updated=db.update("UPDATE users SET password_hash=?,password_setup_required=FALSE,token_version=token_version+1 WHERE LOWER(email)=LOWER(?) AND is_active=TRUE",passwords.encode(password),email.trim());
   return updated==0?fail(404,"Account not found."):ResponseEntity.ok(message("Password updated. You can now sign in."));
 }
 @PostMapping("/account-setup") public ResponseEntity<?> accountSetup(@RequestBody Map<String,Object> body, HttpServletRequest request) {
   String token=string(body,"token"), password=string(body,"password");
   if(token==null||token.isBlank()||password==null) return fail(400,"Account setup link and password are required.");
   if(password.length()<8) return fail(400,"Password must be at least 8 characters long.");
   ThrottleService.Decision limited=throttle.check("account-setup",null,throttle.clientIp(request)); if(!limited.allowed()) return tooManyRequests(limited);
   return otp.completeAccountSetup(token,password) ? ResponseEntity.ok(message("Account activated. You can now sign in.")) : fail(400,"This account setup link is invalid or has expired. Please contact your administrator.");
 }
 @PostMapping("/change-password") public ResponseEntity<?> changePassword(@RequestBody Map<String,Object> body, Authentication auth, HttpServletRequest request) {
   String current=string(body,"current_password"), next=string(body,"new_password");
   if(current==null||next==null) return fail(400,"Current password and new password are required.");
   if(next.length()<8) return fail(400,"Password must be at least 8 characters long.");
   Object account=val(user(auth),"email");
   ThrottleService.Decision limited=throttle.check("change-password",account==null?null:account.toString().toLowerCase(),throttle.clientIp(request)); if(!limited.allowed()) return tooManyRequests(limited);
   List<Map<String,Object>> found=db.queryForList("SELECT id,email,role,token_version,password_hash FROM users WHERE id=? AND is_active=TRUE",userId(auth));
   if(found.isEmpty() || !passwords.matches(current,(String)found.getFirst().get("password_hash"))) return fail(400,"Current password is incorrect.");
   db.update("UPDATE users SET password_hash=?,password_setup_required=FALSE,token_version=token_version+1 WHERE id=?",passwords.encode(next),userId(auth));
   throttle.reset("change-password",account==null?null:account.toString().toLowerCase(),throttle.clientIp(request));
   // The caller keeps working on a replacement token; every session issued before this change is dead.
   Map<String,Object> rotated=new LinkedHashMap<>(found.getFirst()); rotated.remove("password_hash");
   rotated.put("token_version",((Number)rotated.get("token_version")).intValue()+1);
   return ResponseEntity.ok(Map.of("success",true,"message","Password changed successfully.","token",issueToken(rotated)));
 }
 @GetMapping("/me") public Map<String,Object> me(Authentication a) { return ok("user",user(a)); }
 private String string(Map<String,Object>b,String k){Object v=b.get(k);return v==null?null:v.toString();}
 /** New tokens carry the account's session generation, so raising it in the database logs sessions out. */
 private String issueToken(Map<String,Object> user){return jwt.createToken(((Number)user.get("id")).longValue(),(String)user.get("email"),(String)user.get("role"),((Number)user.get("token_version")).intValue());}
 private boolean validEmail(String email){return email!=null&&email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");}
 private ResponseEntity<?> fail(int s,String m){return ResponseEntity.status(s).body(Map.of("success",false,"message",m));}
 private ResponseEntity<?> tooManyRequests(ThrottleService.Decision d){return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER,String.valueOf(d.retryAfterSeconds())).body(Map.of("success",false,"message",d.message()));}
}
