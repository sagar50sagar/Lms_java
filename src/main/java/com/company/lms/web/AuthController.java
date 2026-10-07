package com.company.lms.web;

import com.company.lms.security.JwtService;
import com.company.lms.security.ThrottleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/auth")
public class AuthController extends ApiSupport {
 private final JdbcTemplate db; private final PasswordEncoder passwords; private final JwtService jwt; private final ThrottleService throttle;
 public AuthController(JdbcTemplate db, PasswordEncoder passwords, JwtService jwt, ThrottleService throttle) {this.db=db;this.passwords=passwords;this.jwt=jwt;this.throttle=throttle;}
 @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Map<String,Object> body, HttpServletRequest request) {
   String email=string(body,"email"), password=string(body,"password"); if(email==null||password==null) return fail(400,"Please provide both email and password.");
   String account=email.trim().toLowerCase(), ip=throttle.clientIp(request);
   ThrottleService.Decision limited=throttle.check("login",account,ip);
   // Only a spent IP budget refuses before verification. A spent account budget still has to check the
   // password, otherwise anyone who merely knows an email could lock its real owner out of the LMS.
   if(!limited.allowed() && limited.blocksSource()) return tooManyRequests(limited);
   List<Map<String,Object>> found=db.queryForList("SELECT u.id,u.employee_id,u.full_name,u.email,u.password_hash,u.role,u.department_id,u.designation,u.is_active,u.token_version,d.name department_name FROM users u LEFT JOIN departments d ON d.id=u.department_id WHERE LOWER(u.email)=LOWER(?)",email.trim());
   if(found.isEmpty() || !passwords.matches(password,(String)found.getFirst().get("password_hash")))
     return !limited.allowed() ? tooManyRequests(limited) : fail(401,"Invalid email or password.");
   Map<String,Object> user=new LinkedHashMap<>(found.getFirst()); if(!Boolean.TRUE.equals(user.get("is_active"))) return fail(403,"This account has been deactivated. Please contact HR."); user.remove("password_hash");
   throttle.reset("login",account,ip);
   String token=issueToken(user); user.remove("token_version");
   return ResponseEntity.ok(Map.of("success",true,"message","Login successful.","token",token,"user",user));
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
