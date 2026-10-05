package com.company.lms;

import com.company.lms.security.JwtService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.mail.outbox.enabled=false")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LmsApplicationTests {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtService jwtService;

  @Autowired
  private JdbcTemplate db;

  private String getAdminToken() {
    Long adminId = db.queryForObject("SELECT id FROM users WHERE role='admin' ORDER BY id ASC LIMIT 1", Long.class);
    int version = db.queryForObject("SELECT token_version FROM users WHERE id=?", Integer.class, adminId);
    return "Bearer " + jwtService.createToken(adminId, "admin@company.local", "admin", version);
  }

  @Test
  @Order(1)
  void testCourseCreationViewingEditingAndSelfEnroll() throws Exception {
    String adminToken = getAdminToken();

    // 1. Create a course as ADMIN
    String createJson = """
        {
          "title": "Test Integration Course",
          "description": "Integration Test Description",
          "category": "Compliance",
          "is_mandatory": true,
          "estimated_duration_hours": 2,
          "is_published": true
        }
        """;

    String response = mockMvc.perform(post("/api/courses")
            .header("Authorization", adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createJson))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.course.id").exists())
        .andReturn().getResponse().getContentAsString();

    // Parse course ID from response
    String idStr = response.split("\"id\":")[1].split(",")[0].trim();

    // 2. View the created course (admin has no assignment, tests null-safety of user_progress)
    mockMvc.perform(get("/api/courses/" + idStr)
            .header("Authorization", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.course.title").value("Test Integration Course"));

    // 3. Update the course
    String updateJson = """
        {
          "title": "Updated Integration Course Title",
          "description": "Updated Description",
          "category": "Technical",
          "is_mandatory": false,
          "estimated_duration_hours": 3,
          "is_published": true
        }
        """;

    mockMvc.perform(put("/api/courses/" + idStr)
            .header("Authorization", adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateJson))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.course.title").value("Updated Integration Course Title"));

    // 4. Self-enrolment is closed: an employee cannot pull themselves into a course, and the
    // attempt must not publish the Draft.
    mockMvc.perform(post("/api/assignments/self-enroll")
            .header("Authorization", adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"course_id\": " + idStr + "}"))
        .andExpect(status().isGone());

    mockMvc.perform(get("/api/courses/" + idStr)
            .header("Authorization", adminToken))
        .andExpect(jsonPath("$.course.is_published").value(false));

    db.update("DELETE FROM courses WHERE id=?", Long.parseLong(idStr));
  }

  @Test
  @Order(2)
  void testAssignCourseToUserAndDepartment() throws Exception {
    String adminToken = getAdminToken();
    Long courseId = db.queryForObject("SELECT id FROM courses ORDER BY id ASC LIMIT 1", Long.class);
    Long deptId = db.queryForObject("SELECT id FROM departments ORDER BY id ASC LIMIT 1", Long.class);
    Long empId = db.queryForObject("SELECT id FROM users WHERE role='employee' ORDER BY id ASC LIMIT 1", Long.class);

    // Assign course to user
    mockMvc.perform(post("/api/assignments")
            .header("Authorization", adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"course_id\": " + courseId + ", \"user_id\": \"" + empId + "\", \"due_date\": \"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    // Ensure employee is in department before assigning to department
    mockMvc.perform(post("/api/departments/" + deptId + "/members")
            .header("Authorization", adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"user_id\": \"" + empId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    // Assign course to department (will assign to all employees in that dept)
    mockMvc.perform(post("/api/assignments")
            .header("Authorization", adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"course_id\": " + courseId + ", \"department_id\": \"" + deptId + "\", \"due_date\": \"2026-12-31\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @Test
  @Order(3)
  void testAddEmployeeToDepartmentAndRoleUpdate() throws Exception {
    String adminToken = getAdminToken();
    Long deptId = db.queryForObject("SELECT id FROM departments ORDER BY id ASC LIMIT 1", Long.class);
    Long empId = db.queryForObject("SELECT id FROM users WHERE role='employee' ORDER BY id ASC LIMIT 1", Long.class);

    // 1. Add employee to department
    mockMvc.perform(post("/api/departments/" + deptId + "/members")
            .header("Authorization", adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"user_id\": \"" + empId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    // 2. Update user role to trainer
    mockMvc.perform(put("/api/admin/users/" + empId)
            .header("Authorization", adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\": \"trainer\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    // 3. Restore role back to employee (keeps DB clean for any re-runs)
    mockMvc.perform(put("/api/admin/users/" + empId)
            .header("Authorization", adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\": \"employee\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @Test
  @Order(4)
  void trainerCannotAssignOutsideManagedDepartments() throws Exception {
    Long outsiderDept = db.queryForObject("SELECT id FROM departments ORDER BY id LIMIT 1", Long.class);
    Long courseId = db.queryForObject("SELECT id FROM courses ORDER BY id LIMIT 1", Long.class);
    String trainerToken = "Bearer " + jwtService.createToken(9001L, "temp-trainer@company.local", "trainer", 0);

    try {
      db.update("INSERT INTO departments(id,name) VALUES(9001,'Temp Managed Dept') ON CONFLICT DO NOTHING");
      db.update("INSERT INTO users(id,employee_id,full_name,email,password_hash,role,department_id,is_active) "
          + "VALUES(9001,'TMP-TRAINER','Temp Trainer','temp-trainer@company.local','x','trainer',9001,TRUE) ON CONFLICT DO NOTHING");
      db.update("INSERT INTO users(id,employee_id,full_name,email,password_hash,role,department_id,is_active) "
          + "VALUES(9002,'TMP-OUTSIDER','Temp Outsider','temp-outsider@company.local','x','employee',?,TRUE) ON CONFLICT DO NOTHING", outsiderDept);
      db.update("INSERT INTO user_departments(user_id,department_id) VALUES(9001,9001),(9002,?) ON CONFLICT DO NOTHING", outsiderDept);
      db.update("INSERT INTO trainer_departments(trainer_id,department_id) VALUES(9001,9001) ON CONFLICT DO NOTHING");

      // Targeting an employee by id must not escape the departments this trainer manages.
      mockMvc.perform(post("/api/assignments")
              .header("Authorization", trainerToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"course_id\": " + courseId + ", \"user_id\": 9002}"))
          .andExpect(status().isForbidden());

      // A department the trainer does not manage is likewise refused.
      mockMvc.perform(post("/api/assignments")
              .header("Authorization", trainerToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"course_id\": " + courseId + ", \"department_id\": " + outsiderDept + "}"))
          .andExpect(status().isForbidden());

      org.junit.jupiter.api.Assertions.assertEquals(0, db.queryForObject(
          "SELECT COUNT(*) FROM course_assignments WHERE user_id IN (9001,9002)", Integer.class));
    } finally {
      db.update("DELETE FROM course_assignments WHERE user_id IN (9001,9002)");
      db.update("DELETE FROM trainer_departments WHERE trainer_id=9001");
      db.update("DELETE FROM user_departments WHERE user_id IN (9001,9002)");
      db.update("DELETE FROM users WHERE id IN (9001,9002)");
      db.update("DELETE FROM departments WHERE id=9001");
    }
  }

  @Test
  @Order(5)
  void laterJoinerInheritsDepartmentCourses() throws Exception {
    String adminToken = getAdminToken();
    java.time.LocalDate today = java.time.LocalDate.now();

    Long currentCourseId = db.queryForObject(
        "INSERT INTO courses(title,slug,is_published) VALUES('Dept Inheritance Course','dept-inheritance-course',TRUE) RETURNING id", Long.class);
    Long lapsedCourseId = db.queryForObject(
        "INSERT INTO courses(title,slug,is_published) VALUES('Already Lapsed Dept Course','already-lapsed-dept-course',TRUE) RETURNING id", Long.class);

    try {
      db.update("INSERT INTO departments(id,name) VALUES(9010,'Temp Inheritance Dept') ON CONFLICT DO NOTHING");
      db.update("INSERT INTO users(id,employee_id,full_name,email,password_hash,role,department_id,is_active) VALUES"
          + "(9011,'TMP-SENIOR','Senior Member','temp-senior@company.local','x','employee',9010,TRUE),"
          + "(9012,'TMP-JOINER','Later Joiner','temp-joiner@company.local','x','employee',NULL,TRUE) ON CONFLICT DO NOTHING");
      db.update("INSERT INTO user_departments(user_id,department_id) VALUES(9011,9010) ON CONFLICT DO NOTHING");
      // The joiner already owns the current course personally, with their own deadline.
      db.update("INSERT INTO course_assignments(course_id,user_id,due_date,status) VALUES(?,?,?, 'enrolled')"
          + " ON CONFLICT(course_id,user_id) DO UPDATE SET due_date=EXCLUDED.due_date,assigned_department_id=NULL",
          currentCourseId, 9012L, java.sql.Date.valueOf(today.plusDays(5)));

      // 1. Grant a course to the department while only the senior employee belongs to it.
      mockMvc.perform(post("/api/assignments")
              .header("Authorization", adminToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"course_id\": " + currentCourseId + ", \"department_id\": 9010, \"due_date\": \"" + today.plusDays(40) + "\"}"))
          .andExpect(status().isOk());
      org.junit.jupiter.api.Assertions.assertEquals(java.sql.Date.valueOf(today.plusDays(40)),
          db.queryForObject("SELECT due_date FROM course_assignments WHERE user_id=9011 AND course_id=?", java.sql.Date.class, currentCourseId));

      // 2. A deadline that has already lapsed is still the department's date for people who were around for it.
      mockMvc.perform(post("/api/assignments")
              .header("Authorization", adminToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"course_id\": " + lapsedCourseId + ", \"department_id\": 9010, \"due_date\": \"" + today.minusDays(10) + "\"}"))
          .andExpect(status().isOk());
      org.junit.jupiter.api.Assertions.assertEquals(java.sql.Date.valueOf(today.minusDays(10)),
          db.queryForObject("SELECT due_date FROM course_assignments WHERE user_id=9011 AND course_id=?", java.sql.Date.class, lapsedCourseId));

      // 3. The employee who joins afterwards is caught up against both grants.
      mockMvc.perform(post("/api/departments/9010/members")
              .header("Authorization", adminToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"user_id\": 9012}"))
          .andExpect(status().isOk());

      // Their own deadline survives the inheritance instead of being overwritten by the department's.
      org.junit.jupiter.api.Assertions.assertEquals(java.sql.Date.valueOf(today.plusDays(5)),
          db.queryForObject("SELECT due_date FROM course_assignments WHERE user_id=9012 AND course_id=?", java.sql.Date.class, currentCourseId));

      // A lapsed department deadline must not make a new hire overdue on their first day: they get the
      // original learning window from today, and the stored window here is negative, so the fallback applies.
      org.junit.jupiter.api.Assertions.assertEquals(java.sql.Date.valueOf(today.plusDays(30)),
          db.queryForObject("SELECT due_date FROM course_assignments WHERE user_id=9012 AND course_id=?", java.sql.Date.class, lapsedCourseId));

      String joinerJson = mockMvc.perform(get("/api/assignments/my")
              .header("Authorization", "Bearer " + jwtService.createToken(9012L, "temp-joiner@company.local", "employee", 0)))
          .andExpect(status().isOk())
          .andReturn().getResponse().getContentAsString();
      org.junit.jupiter.api.Assertions.assertTrue(joinerJson.contains("Dept Inheritance Course"));
      org.junit.jupiter.api.Assertions.assertTrue(joinerJson.contains("Already Lapsed Dept Course"));
      org.junit.jupiter.api.Assertions.assertFalse(joinerJson.contains("\"compliance_status\":\"overdue\""));

      // 4. Leaving the department keeps the assignments: compliance and certificate history must not vanish.
      mockMvc.perform(delete("/api/departments/9010/members/9012")
              .header("Authorization", adminToken))
          .andExpect(status().isOk());
      org.junit.jupiter.api.Assertions.assertEquals(2, db.queryForObject(
          "SELECT COUNT(*) FROM course_assignments WHERE user_id=9012", Integer.class));
    } finally {
      db.update("DELETE FROM course_assignments WHERE user_id IN (9011,9012)");
      db.update("DELETE FROM course_assignments WHERE course_id IN (?,?)", currentCourseId, lapsedCourseId);
      db.update("DELETE FROM user_departments WHERE user_id IN (9011,9012)");
      db.update("DELETE FROM trainer_departments WHERE trainer_id IN (9011,9012)");
      db.update("DELETE FROM users WHERE id IN (9011,9012)");
      db.update("DELETE FROM departments WHERE id=9010");
      db.update("DELETE FROM courses WHERE id IN (?,?)", currentCourseId, lapsedCourseId);
    }
  }
}
