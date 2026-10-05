package com.company.lms;

import com.company.lms.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Guards the access-control and API-contract decisions made for production: what stays public,
 * which headers ship, whose courses a trainer may edit, and whether a password change really
 * ends the sessions it should.
 */
@SpringBootTest(properties = "app.mail.outbox.enabled=false")
@AutoConfigureMockMvc
class ProductionHardeningTests {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate db;
  @Autowired JwtService jwt;
  @Autowired PasswordEncoder passwords;

  final ObjectMapper json = new ObjectMapper();
  long adminId;
  final long workerId = 9211L;
  final long otherTrainerId = 9212L;
  long courseId;

  String tokenFor(long userId, String role) throws Exception {
    int version = db.queryForObject("SELECT token_version FROM users WHERE id=?", Integer.class, userId);
    return "Bearer " + jwt.createToken(userId, "user" + userId + "@test.local", role, version);
  }

  void insertUser(long id, String role, String password) {
    db.update("INSERT INTO users(id,employee_id,full_name,email,password_hash,role,is_active,password_setup_required,token_version) "
            + "VALUES(?,?,?,?,?,?,TRUE,FALSE,0) ON CONFLICT (id) DO UPDATE SET email=EXCLUDED.email, password_hash=EXCLUDED.password_hash, "
            + "role=EXCLUDED.role, is_active=TRUE, password_setup_required=FALSE, token_version=0",
        id, "HARD-" + id, "Hardening User " + id, "hardening" + id + "@test.local",
        passwords.encode(password), role);
  }

  @BeforeEach
  void seed() {
    adminId = db.queryForObject("SELECT id FROM users WHERE role='admin' ORDER BY id LIMIT 1", Long.class);
    insertUser(workerId, "employee", "worker-password");
    insertUser(otherTrainerId, "trainer", "trainer-password");
    db.update("DELETE FROM user_departments WHERE user_id IN (?,?)", workerId, otherTrainerId);
    courseId = db.queryForObject("INSERT INTO courses(title,slug,is_published,created_by) VALUES('Hardening Course','hardening-course',TRUE,?) RETURNING id",
        Long.class, adminId);
  }

  @AfterEach
  void teardown() {
    db.update("DELETE FROM quiz_questions WHERE quiz_id IN (SELECT id FROM quizzes WHERE course_id=?)", courseId);
    db.update("DELETE FROM quizzes WHERE course_id=?", courseId);
    db.update("DELETE FROM lessons WHERE course_id=?", courseId);
    db.update("DELETE FROM chapters WHERE course_id=?", courseId);
    db.update("DELETE FROM course_assignments WHERE course_id=? OR user_id IN (?,?)", courseId, workerId, otherTrainerId);
    db.update("DELETE FROM courses WHERE id=?", courseId);
    db.update("DELETE FROM account_setup_tokens WHERE user_id IN (?,?)", workerId, otherTrainerId);
    db.update("DELETE FROM users WHERE id IN (?,?)", workerId, otherTrainerId);
  }

  @Test
  void departmentListingIsPrivateButPagesAndHealthStayOpen() throws Exception {
    for (String path : new String[] { "/api/departments", "/api/departments/1/employees" }) {
      int status = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
      assertTrue(status == 401 || status == 403, path + " must not answer anonymously, got " + status);
    }
    mockMvc.perform(get("/api/departments").header("Authorization", tokenFor(adminId, "admin"))).andExpect(status().isOk());
    mockMvc.perform(get("/login.html")).andExpect(status().isOk());
    mockMvc.perform(get("/api/health")).andExpect(status().isOk());
  }

  @Test
  void browserHardeningHeadersShipOnEveryPage() throws Exception {
    MvcResult page = mockMvc.perform(get("/admin.html")).andExpect(status().isOk()).andReturn();
    String csp = page.getResponse().getHeader("Content-Security-Policy");
    assertNotNull(csp, "a CSP has to be present or the header work is pointless");
    assertTrue(csp.contains("frame-ancestors 'none'"));
    assertTrue(csp.contains("object-src 'none'"));
    assertTrue(csp.contains("default-src 'self'"));
    assertEquals("DENY", page.getResponse().getHeader("X-Frame-Options"));
    assertEquals("same-origin", page.getResponse().getHeader("Referrer-Policy"));
  }

  @Test
  void cdnAndInlineScriptNeedsStayAllowedByThePolicy() throws Exception {
    // The certificate download pulls jsPDF from cdnjs and every page uses inline modules;
    // a stricter policy would break them silently, so the allowances are pinned here.
    String csp = mockMvc.perform(get("/certificate-view.html")).andReturn().getResponse().getHeader("Content-Security-Policy");
    assertTrue(csp.contains("https://cdnjs.cloudflare.com"));
    assertTrue(csp.contains("script-src 'self' 'unsafe-inline'"));
  }

  @Test
  @DisplayName("the policy frames Google Drive, so a pasted Drive lesson video can actually play")
  void driveLessonsAreFrameable() throws Exception {
    String csp = mockMvc.perform(get("/course-player.html")).andReturn().getResponse().getHeader("Content-Security-Policy");
    String frameSrc = java.util.Arrays.stream(csp.split(";")).map(String::trim)
        .filter(directive -> directive.startsWith("frame-src")).findFirst().orElse("");
    assertTrue(frameSrc.contains("drive.google.com"),
        "Drive has to be named in frame-src or every Drive lesson renders as a blank box: " + frameSrc);
    assertTrue(frameSrc.contains("youtube-nocookie.com"), "the existing YouTube allowance must survive: " + frameSrc);
  }

  @Test
  void unmappedPathsAndBadRequestsAreClientErrorsNotServerFaults() throws Exception {
    String admin = tokenFor(adminId, "admin");
    mockMvc.perform(get("/api/definitely-not-a-route").header("Authorization", admin))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false));
    mockMvc.perform(post("/api/courses").header("Authorization", admin)
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\": "))
        .andExpect(status().isBadRequest());
    mockMvc.perform(delete("/api/courses/not-a-number").header("Authorization", admin))
        .andExpect(status().isBadRequest());
  }

  @Test
  void trainerCannotRewriteAnotherPersonsCourseOrQuiz() throws Exception {
    String trainer = tokenFor(otherTrainerId, "trainer");
    mockMvc.perform(put("/api/courses/" + courseId).header("Authorization", trainer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Hijacked Title\"}"))
        .andExpect(status().isForbidden());
    assertEquals("Hardening Course", db.queryForObject("SELECT title FROM courses WHERE id=?", String.class, courseId));

    mockMvc.perform(post("/api/courses/" + courseId + "/chapters").header("Authorization", trainer)
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Sneaked chapter\",\"sequence_order\":1}"))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/quizzes").header("Authorization", trainer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"course_id\":" + courseId + ",\"title\":\"Sneaked quiz\",\"passing_score\":70,\"time_limit_mins\":10}"))
        .andExpect(status().isForbidden());

    long chapterId = db.queryForObject(
        "INSERT INTO chapters(course_id,title,sequence_order) VALUES(?,?,1) RETURNING id", Long.class, courseId, "Owned chapter");
    mockMvc.perform(post("/api/courses/" + courseId + "/chapters/" + chapterId + "/lessons")
            .header("Authorization", tokenFor(adminId, "admin")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Lesson\",\"content_type\":\"text\",\"content\":\"Body\","
                + "\"video_url\":\"https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ\"}"))
        .andExpect(status().isCreated());
    long lessonChapter = db.queryForObject("SELECT chapter_id FROM lessons WHERE course_id=?", Long.class, courseId);
    assertEquals(chapterId, (long) lessonChapter, "the lesson must sit on the chapter it named");
  }

  @Test
  void assignmentRejectsADueDateItCannotStore() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/assignments").header("Authorization", tokenFor(adminId, "admin"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"course_id\":" + courseId + ",\"user_id\":" + workerId + ",\"due_date\":\"2026-13-45\"}"))
        .andExpect(status().isBadRequest())
        .andReturn();
    assertTrue(json.readTree(result.getResponse().getContentAsString()).get("message").asText().contains("Due date"),
        result.getResponse().getContentAsString());
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM course_assignments WHERE course_id=? AND user_id=?",
        Integer.class, courseId, workerId), "a malformed date must not create an undated assignment");
  }

  @Test
  void changingAPasswordEndsSessionsIssuedBeforeIt() throws Exception {
    String oldToken = tokenFor(workerId, "employee");
    mockMvc.perform(get("/api/auth/me").header("Authorization", oldToken)).andExpect(status().isOk());

    MvcResult changed = mockMvc.perform(post("/api/auth/change-password").header("Authorization", oldToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"current_password\":\"worker-password\",\"new_password\":\"worker-password-two\"}"))
        .andReturn();
    assertEquals(200, changed.getResponse().getStatus(), changed.getResponse().getContentAsString());
    JsonNode body = json.readTree(changed.getResponse().getContentAsString());
    assertTrue(body.hasNonNull("token"), "the caller needs a replacement session: " + body);

    // A session that predates the change is now worthless, even though its signature still verifies.
    mockMvc.perform(get("/api/auth/me").header("Authorization", oldToken)).andExpect(status().isForbidden());

    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + body.get("token").asText()))
        .andExpect(status().isOk());

    mockMvc.perform(put("/api/admin/users/" + workerId).header("Authorization", tokenFor(adminId, "admin"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"designation\":\"Kept role\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + body.get("token").asText()))
        .andExpect(status().isOk());
  }

  @Test
  void deactivatingAnAccountRetiresItsTokensEvenAfterReactivation() throws Exception {
    String workerToken = tokenFor(workerId, "employee");
    mockMvc.perform(put("/api/admin/users/" + workerId).header("Authorization", tokenFor(adminId, "admin"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"is_active\":false}"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/auth/me").header("Authorization", workerToken)).andExpect(status().isForbidden());

    mockMvc.perform(put("/api/admin/users/" + workerId).header("Authorization", tokenFor(adminId, "admin"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"is_active\":true}"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/auth/me").header("Authorization", workerToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void headcountCountsEmployeesOnlySoBulkAssignCannotPromiseAFailure() throws Exception {
    long deptId = db.queryForObject("INSERT INTO departments(name,description) VALUES('Hardening Dept','test') RETURNING id", Long.class);
    try {
      db.update("INSERT INTO user_departments(user_id,department_id) VALUES(?,?) ON CONFLICT DO NOTHING", otherTrainerId, deptId);
      db.update("INSERT INTO user_departments(user_id,department_id) VALUES(?,?) ON CONFLICT DO NOTHING", workerId, deptId);

      MvcResult list = mockMvc.perform(get("/api/departments").header("Authorization", tokenFor(adminId, "admin")))
          .andExpect(status().isOk()).andReturn();
      int employeeCount = -1;
      for (JsonNode department : json.readTree(list.getResponse().getContentAsString()).get("departments")) {
        if (department.get("id").asLong() == deptId) employeeCount = department.get("employee_count").asInt();
      }
      assertEquals(1, employeeCount, "the managing trainer is a member but not headcount");
    } finally {
      db.update("DELETE FROM user_departments WHERE department_id=?", deptId);
      db.update("DELETE FROM trainer_departments WHERE department_id=?", deptId);
      db.update("DELETE FROM departments WHERE id=?", deptId);
    }
  }

  @Test
  void deleteRemovesLearningHistoryButNeverAnAdministrator() throws Exception {
    String admin = tokenFor(adminId, "admin");
    db.update("INSERT INTO course_assignments(course_id,user_id,assigned_by,status) VALUES(?,?,?,'enrolled')",
        courseId, workerId, adminId);
    db.update("INSERT INTO certificates(certificate_code,user_id,course_id) VALUES('CERT-DELETE-CASCADE',?,?)", workerId, courseId);

    mockMvc.perform(delete("/api/admin/users/" + adminId).header("Authorization", admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("your own")));

    insertUser(9213L, "admin", "other-admin-password");
    try {
      mockMvc.perform(delete("/api/admin/users/9213").header("Authorization", admin))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot be deleted")));

      mockMvc.perform(delete("/api/admin/users/" + workerId).header("Authorization", admin))
          .andExpect(status().isOk());

      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Integer.class, workerId));
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM certificates WHERE user_id=?", Integer.class, workerId),
          "the cascade must take certificates with the account");
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM course_assignments WHERE user_id=?", Integer.class, workerId));
    } finally {
      db.update("DELETE FROM users WHERE id=9213");
    }
  }

  @Test
  @DisplayName("an administrator's own learning never moves Company Compliance")
  void companyComplianceCountsEmployeesOnly() throws Exception {
    String admin = tokenFor(adminId, "admin");
    long departmentId = db.queryForObject("SELECT id FROM departments ORDER BY id LIMIT 1", Long.class);
    db.update("INSERT INTO course_assignments(course_id,user_id,assigned_by,status,completed_at) VALUES(?,?,?,'completed',CURRENT_TIMESTAMP)",
        courseId, workerId, adminId);
    JsonNode baseline = complianceBody(admin);

    // An unfinished admin enrolment would visibly drag the rate down if the metric counted administrators,
    // and the certificate would inflate the headline count. The membership row covers the department rows too.
    long adminAssignment = db.queryForObject(
        "INSERT INTO course_assignments(course_id,user_id,assigned_by,status,due_date) VALUES(?,?,?,'enrolled',CURRENT_DATE + 30) RETURNING id",
        Long.class, courseId, adminId, adminId);
    long adminCertificate = db.queryForObject(
        "INSERT INTO certificates(certificate_code,user_id,course_id) VALUES('CERT-ADMIN-EXCLUDED',?,?) RETURNING id",
        Long.class, adminId, courseId);
    db.update("INSERT INTO user_departments(user_id,department_id) VALUES(?,?) ON CONFLICT DO NOTHING", adminId, departmentId);
    try {
      assertEquals(baseline, complianceBody(admin),
          "only employees are compliance targets, so the admin rows must not appear anywhere in the payload");
    } finally {
      db.update("DELETE FROM certificates WHERE id=?", adminCertificate);
      db.update("DELETE FROM course_assignments WHERE id=?", adminAssignment);
      db.update("DELETE FROM user_departments WHERE user_id=? AND department_id=?", adminId, departmentId);
    }
  }

  /** The whole compliance payload, so company metrics and per-department rows are both compared. */
  private JsonNode complianceBody(String admin) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/admin/compliance").header("Authorization", admin))
        .andExpect(status().isOk()).andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }
}
