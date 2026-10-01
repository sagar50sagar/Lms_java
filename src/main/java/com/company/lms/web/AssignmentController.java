package com.company.lms.web;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/assignments")
public class AssignmentController extends ApiSupport {
 private final JdbcTemplate db; public AssignmentController(JdbcTemplate db){this.db=db;}

 @GetMapping("/my") public Map<String,Object> mine(Authentication a){
   String sql="SELECT ca.id assignment_id,ca.course_id,ca.due_date,ca.enrolled_at,ca.completed_at,c.title course_title,c.slug course_slug,c.description course_description,c.category course_category,c.is_mandatory,c.estimated_duration_hours,u.full_name assigned_by_name,COUNT(DISTINCT l.id) total_lessons,COUNT(DISTINCT lp.lesson_id) completed_lessons,CASE WHEN ca.completed_at IS NOT NULL THEN 'completed' WHEN ca.due_date<CURRENT_DATE THEN 'overdue' WHEN COUNT(DISTINCT lp.lesson_id)>0 THEN 'in_progress' ELSE 'enrolled' END compliance_status FROM course_assignments ca JOIN courses c ON c.id=ca.course_id LEFT JOIN users u ON u.id=ca.assigned_by LEFT JOIN lessons l ON l.course_id=c.id LEFT JOIN lesson_progress lp ON lp.lesson_id=l.id AND lp.user_id=? AND lp.completed=TRUE WHERE ca.user_id=? GROUP BY ca.id,c.id,u.full_name ORDER BY ca.due_date ASC NULLS LAST";
   List<Map<String,Object>> rows=db.queryForList(sql,userId(a),userId(a));
   for(var r:rows){
     int t=((Number)r.get("total_lessons")).intValue(),d=((Number)r.get("completed_lessons")).intValue();
     r.put("progress_percent",t==0?0:Math.round(d*100.0/t));
     r.put("is_overdue","overdue".equals(r.get("compliance_status")));
   }
   return ok("assignments",rows);
 }

 @PostMapping @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
 public ResponseEntity<?> assign(@RequestBody Map<String,Object> b, Authentication a){
   Object courseObj=b.get("course_id"), uidObj=b.get("user_id"), deptObj=b.get("department_id");
   if(courseObj==null || courseObj.toString().isBlank() || ((uidObj==null || uidObj.toString().isBlank()) && (deptObj==null || deptObj.toString().isBlank()))){
     return ResponseEntity.badRequest().body(Map.of("success",false,"message","Course and target (user or department) are required."));
   }
   long courseId;
   try {
     courseId = Long.parseLong(courseObj.toString().trim());
   } catch(NumberFormatException e) {
     return ResponseEntity.badRequest().body(Map.of("success",false,"message","Invalid course ID format."));
   }

   List<Long> users;
   if (uidObj != null && !uidObj.toString().isBlank()) {
     try {
       users = List.of(Long.parseLong(uidObj.toString().trim()));
     } catch(NumberFormatException e) {
       return ResponseEntity.badRequest().body(Map.of("success",false,"message","Invalid user ID format."));
     }
   } else {
     int deptInt = Integer.parseInt(deptObj.toString().trim());
     users = db.queryForList("SELECT id FROM users WHERE department_id=? AND is_active=TRUE AND role='employee'", Long.class, deptInt);
     if("trainer".equals(role(a))){
       List<Map<String,Object>> td = db.queryForList("SELECT id FROM trainer_departments WHERE trainer_id=? AND department_id=?", userId(a), deptInt);
       if(td.isEmpty()){
         return ResponseEntity.status(403).body(Map.of("success",false,"message","You are not assigned to this department."));
       }
     }
   }

   if (users.isEmpty()) {
     return ResponseEntity.badRequest().body(Map.of("success",false,"message","No active employees found in the selected department."));
   }

   java.sql.Date dueDate = null;
   Object dueDateObj = b.get("due_date");
   if (dueDateObj != null && !dueDateObj.toString().isBlank()) {
     try {
       dueDate = java.sql.Date.valueOf(dueDateObj.toString().trim());
     } catch(IllegalArgumentException ignored) {}
   }

   for (Long u : users) {
     db.update("INSERT INTO course_assignments(course_id,user_id,assigned_by,due_date,status) VALUES(?,?,?,?, 'enrolled') ON CONFLICT(course_id,user_id) DO UPDATE SET due_date=EXCLUDED.due_date,assigned_by=EXCLUDED.assigned_by", courseId, u, userId(a), dueDate);
   }

   return ResponseEntity.ok(Map.of("success",true,"message","Course assigned successfully to "+users.size()+" employee(s).","assigned_count",users.size()));
 }

 @PostMapping("/self-enroll")
 public ResponseEntity<?> selfEnroll(@RequestBody Map<String,Object> b, Authentication a){
   if(a == null) return ResponseEntity.status(401).body(Map.of("success",false,"message","Authentication required."));
   Object courseObj = b.get("course_id");
   if(courseObj == null || courseObj.toString().isBlank()){
     return ResponseEntity.badRequest().body(Map.of("success",false,"message","course_id is required."));
   }
   long courseId;
   try { courseId = Long.parseLong(courseObj.toString().trim()); }
   catch(Exception e) { return ResponseEntity.badRequest().body(Map.of("success",false,"message","Invalid course_id.")); }

   long uid = userId(a);
   java.sql.Date dueDate = java.sql.Date.valueOf(java.time.LocalDate.now().plusDays(30));
   db.update("INSERT INTO course_assignments(course_id,user_id,assigned_by,due_date,status) VALUES(?,?,?,?, 'enrolled') ON CONFLICT(course_id,user_id) DO NOTHING", courseId, uid, uid, dueDate);
   return ResponseEntity.ok(Map.of("success",true,"message","Successfully enrolled in training course."));
 }
}

