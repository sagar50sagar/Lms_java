package com.company.lms.web;

import com.company.lms.security.JwtService;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/auth")
public class AuthController extends ApiSupport {
 private final JdbcTemplate db; private final PasswordEncoder passwords; private final JwtService jwt;
 public AuthController(JdbcTemplate db, PasswordEncoder passwords, JwtService jwt) {this.db=db;this.passwords=passwords;this.jwt=jwt;}
 @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Map<String,Object> body) {
   String email=string(body,"email"), password=string(body,"password"); if(email==null||password==null) return fail(400,"Please provide both email and password.");
   List<Map<String,Object>> found=db.queryForList("SELECT u.id,u.employee_id,u.full_name,u.email,u.password_hash,u.role,u.department_id,u.designation,u.is_active,d.name department_name FROM users u LEFT JOIN departments d ON d.id=u.department_id WHERE LOWER(u.email)=LOWER(?)",email.trim());
   if(found.isEmpty() || !passwords.matches(password,(String)found.getFirst().get("password_hash"))) return fail(401,"Invalid email or password.");
   Map<String,Object> user=new LinkedHashMap<>(found.getFirst()); if(!Boolean.TRUE.equals(user.get("is_active"))) return fail(403,"This account has been deactivated. Please contact HR."); user.remove("password_hash");
   return ResponseEntity.ok(Map.of("success",true,"message","Login successful.","token",jwt.createToken(((Number)user.get("id")).longValue(),(String)user.get("email"),(String)user.get("role")),"user",user));
 }
 @PostMapping("/register") public ResponseEntity<?> register(@RequestBody Map<String,Object> b) {
   String name=string(b,"full_name"), email=string(b,"email"), pass=string(b,"password"), role=Optional.ofNullable(string(b,"role")).orElse("employee");
   if(name==null||email==null||pass==null) return fail(400,"Full name, email, and password are required."); if(!List.of("admin","trainer","employee").contains(role)) return fail(400,"Invalid role.");
   if(!db.queryForList("SELECT id FROM users WHERE LOWER(email)=LOWER(?)",email).isEmpty()) return fail(409,"An account with this corporate email already exists.");
   String employee=Optional.ofNullable(string(b,"employee_id")).orElse("EMP-"+(1000+new Random().nextInt(9000)));
   Map<String,Object> user=db.queryForMap("INSERT INTO users(employee_id,full_name,email,password_hash,role,department_id,designation) VALUES(?,?,?,?,?,?,?) RETURNING id,employee_id,full_name,email,role,department_id,designation,created_at",employee,name.trim(),email.trim().toLowerCase(),passwords.encode(pass),role,b.get("department_id"),Optional.ofNullable(string(b,"designation")).orElse("Staff"));
   return ResponseEntity.status(201).body(Map.of("success",true,"message","User registered successfully.","token",jwt.createToken(((Number)user.get("id")).longValue(),(String)user.get("email"),(String)user.get("role")),"user",user));
 }
 @GetMapping("/me") public Map<String,Object> me(Authentication a) { return ok("user",user(a)); }
 private String string(Map<String,Object>b,String k){Object v=b.get(k);return v==null?null:v.toString();}
 private ResponseEntity<?> fail(int s,String m){return ResponseEntity.status(s).body(Map.of("success",false,"message",m));}
}
