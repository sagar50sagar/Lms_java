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

@SpringBootTest
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
    return "Bearer " + jwtService.createToken(adminId, "admin@company.local", "admin");
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

    // 4. Self-enroll in course
    mockMvc.perform(post("/api/assignments/self-enroll")
            .header("Authorization", adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"course_id\": " + idStr + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
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
}
