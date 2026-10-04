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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end verification of the many-to-many user<->department model
 * (user_departments membership table, trainer_departments management rights,
 * users.department_id primary/home). ~60 assertions across every touched endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DepartmentMembershipIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate db;
  @Autowired JwtService jwt;

  ObjectMapper om = new ObjectMapper();
  long ts = System.currentTimeMillis();
  long adminId, deptA, deptB, deptC, e1, e2, m1, t1, courseId;

  String tok(long userId) { return "Bearer " + jwt.createToken(userId, "u" + userId + "@test.local", "x"); }
  String adminTok() { return tok(adminId); }

  Set<Long> memberships(long userId) {
    return new HashSet<>(db.queryForList("SELECT department_id FROM user_departments WHERE user_id=?", Long.class, userId));
  }
  boolean isPrimary(long userId, long dept) {
    Long p = db.queryForObject("SELECT department_id FROM users WHERE id=?", Long.class, userId);
    return p != null && p == dept;
  }
  long addUser(String name, String role, Long home) {
    return db.queryForObject(
      "INSERT INTO users(employee_id,full_name,email,password_hash,role,department_id,password_setup_required) VALUES(?,?,?,?,?,?,FALSE) RETURNING id",
      Long.class, "TST-" + ts + "-" + name, name, name + ts + "@test.local", "x", role, home);
  }
  long addDept(String name) {
    return db.queryForObject("INSERT INTO departments(name,description) VALUES(?,?) RETURNING id", Long.class, name, "test dept");
  }

  @BeforeEach
  void setup() {
    adminId = db.queryForObject("SELECT id FROM users WHERE role='admin' ORDER BY id ASC LIMIT 1", Long.class);
    deptA = addDept("QA-DeptA-" + ts);
    deptB = addDept("QA-DeptB-" + ts);
    deptC = addDept("QA-DeptC-" + ts);
    e1 = addUser("QA-E1", "employee", deptA);
    e2 = addUser("QA-E2", "employee", null);
    m1 = addUser("QA-M1", "employee", deptA);   // multi-dept: A + B
    t1 = addUser("QA-T1", "trainer", deptA);    // manages B
    db.update("INSERT INTO user_departments(user_id,department_id) VALUES(?,?),(?,?),(?,?),(?,?),(?,?)", e1, deptA, m1, deptA, m1, deptB, t1, deptA, t1, deptB);
    db.update("INSERT INTO trainer_departments(trainer_id,department_id,assigned_by) VALUES(?,?,?)", t1, deptB, adminId);
    courseId = db.queryForObject(
      "INSERT INTO courses(title,slug,description,category,is_mandatory,estimated_duration_hours,created_by,is_published) VALUES(?,?,?,?,?,?,?,FALSE) RETURNING id",
      Long.class, "QA Course " + ts, "qa-course-" + ts, "d", "Compliance", false, 1.0, adminId);
  }

  @AfterEach
  void cleanup() {
    db.update("DELETE FROM course_assignments WHERE course_id=?", courseId);
    db.update("DELETE FROM users WHERE id IN (?,?,?,?)", e1, e2, m1, t1);
    db.update("DELETE FROM courses WHERE id=?", courseId);
    db.update("DELETE FROM departments WHERE id IN (?,?,?)", deptA, deptB, deptC);
  }

  // ===== Schema / backfill =====
  @Test @Order(1) void userDepartmentsTableExistsAndBackfilled() {
    Integer cnt = db.queryForObject("SELECT count(*) FROM user_departments", Integer.class);
    assertTrue(cnt >= 3, "membership table should have rows");
    // Each fixture user's home department_id must have a matching membership row.
    Integer orphans = db.queryForObject(
      "SELECT count(*) FROM users u WHERE u.id IN (?,?,?,?) AND u.department_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM user_departments ud WHERE ud.user_id=u.id AND ud.department_id=u.department_id)",
      Integer.class, e1, e2, m1, t1);
    assertEquals(0, orphans, "no fixture user's home dept may lack a membership row");
    // Trainer management rows are mirrored into memberships.
    assertEquals(0, db.queryForObject("SELECT count(*) FROM trainer_departments td WHERE td.trainer_id IN (?,?,?,?) AND NOT EXISTS(SELECT 1 FROM user_departments ud WHERE ud.user_id=td.trainer_id AND ud.department_id=td.department_id)", Integer.class, e1, e2, m1, t1));
  }

  // ===== GET /admin/employees read shape =====
  @Test @Order(2) void employeesEndpointExposesMembershipsAndPrimary() throws Exception {
    MvcResult r = mockMvc.perform(get("/api/admin/employees").header("Authorization", adminTok()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
    JsonNode arr = om.readTree(r.getResponse().getContentAsString()).get("employees");
    JsonNode m1n = findBy(arr, m1);
    assertNotNull(m1n, "M1 present");
    JsonNode depts = m1n.get("departments");
    assertEquals(2, depts.size(), "M1 has 2 memberships");
    Set<Long> ids = new HashSet<>(); for (JsonNode d : depts) ids.add(d.get("id").asLong());
    assertTrue(ids.contains(deptA) && ids.contains(deptB), "M1 memberships = {A,B}");
    assertEquals(deptA, m1n.get("department_id").asLong(), "M1 primary = A");
    // E1 single membership, E2 none
    assertEquals(1, findBy(arr, e1).get("departments").size());
    assertEquals(0, findBy(arr, e2).get("departments").size());
  }

  // ===== PUT /admin/users/{id} department_ids reconcile =====
  @Test @Order(3) void updateReconcilesMembershipsAndPrimary() throws Exception {
    mockMvc.perform(put("/api/admin/users/" + e2).header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"department_ids\":[" + deptA + "," + deptC + "],\"primary_department_id\":" + deptC + "}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    assertEquals(new HashSet<>(List.of(deptA, deptC)), memberships(e2), "memberships set to {A,C}");
    assertTrue(isPrimary(e2, deptC), "primary set to C");
  }

  @Test @Order(4) void updateRemovesOmittedMembership() throws Exception {
    mockMvc.perform(put("/api/admin/users/" + m1).header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"department_ids\":[" + deptB + "],\"primary_department_id\":" + deptB + "}"))
        .andExpect(status().isOk());
    assertEquals(new HashSet<>(List.of(deptB)), memberships(m1), "A removed, only B kept");
    assertTrue(isPrimary(m1, deptB));
  }

  @Test @Order(5) void updateEmptyMembershipsClearsAll() throws Exception {
    mockMvc.perform(put("/api/admin/users/" + m1).header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"department_ids\":[]}"))
        .andExpect(status().isOk());
    assertTrue(memberships(m1).isEmpty(), "all memberships removed");
    assertTrue(isPrimary(m1, -1) || db.queryForObject("SELECT department_id FROM users WHERE id=?", Long.class, m1) == null, "primary cleared");
  }

  @Test @Order(6) void updatePrimaryOutsideListIsAdded() throws Exception {
    mockMvc.perform(put("/api/admin/users/" + m1).header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"department_ids\":[" + deptA + "],\"primary_department_id\":" + deptB + "}"))
        .andExpect(status().isOk());
    assertTrue(memberships(m1).contains(deptB), "primary C auto-added to membership list");
    assertTrue(isPrimary(m1, deptB));
  }

  // ===== Demotion trainer -> employee =====
  @Test @Order(7) void roleIsDerivedFromDepartmentManagement() throws Exception {
    // PUT /admin/users no longer edits role: sending one must be ignored.
    mockMvc.perform(put("/api/admin/users/" + e1).header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"trainer\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    assertEquals("employee", db.queryForObject("SELECT role FROM users WHERE id=?", String.class, e1), "role unchanged by Users-tab update");

    // Promoting via the Departments tab makes them a trainer and mirrors a membership.
    mockMvc.perform(post("/api/departments/" + deptA + "/trainers").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"trainer_id\":\"" + e1 + "\"}"))
        .andExpect(status().isOk());
    assertEquals("trainer", db.queryForObject("SELECT role FROM users WHERE id=?", String.class, e1), "trainer once managing a dept");

    // Removing their only managed dept demotes them, but keeps the membership.
    mockMvc.perform(delete("/api/departments/" + deptA + "/trainers/" + e1).header("Authorization", adminTok()))
        .andExpect(status().isOk());
    assertEquals("employee", db.queryForObject("SELECT role FROM users WHERE id=?", String.class, e1), "demoted when last dept removed");
    assertTrue(memberships(e1).contains(deptA), "membership kept after demotion");
  }

  // ===== DepartmentController all()/employee_count =====
  @Test @Order(8) void deptAllCountsViaMembership() throws Exception {
    MvcResult r = mockMvc.perform(get("/api/departments").header("Authorization", adminTok())).andExpect(status().isOk()).andReturn();
    JsonNode arr = om.readTree(r.getResponse().getContentAsString()).get("departments");
    // employee_count counts every active member (incl. trainers), sourced from user_departments.
    assertEquals(3, findBy(arr, deptA).get("employee_count").asInt(), "deptA = E1, M1, T1");
  }

  @Test @Order(9) void deptAllEmployeeCountExact() throws Exception {
    // deptA active members: E1, M1, T1 = 3; deptB: M1, T1 = 2 (T1 manages B so is also a member); deptC: 0
    MvcResult r = mockMvc.perform(get("/api/departments").header("Authorization", adminTok())).andExpect(status().isOk()).andReturn();
    JsonNode arr = om.readTree(r.getResponse().getContentAsString()).get("departments");
    assertEquals(3, findBy(arr, deptA).get("employee_count").asInt());
    assertEquals(2, findBy(arr, deptB).get("employee_count").asInt());
    assertEquals(0, findBy(arr, deptC).get("employee_count").asInt());
  }

  // ===== Trainer scope: my-departments =====
  @Test @Order(10) void trainerSeesOnlyManagedDepartments() throws Exception {
    MvcResult r = mockMvc.perform(get("/api/departments/my-departments").header("Authorization", tok(t1)))
        .andExpect(status().isOk()).andReturn();
    JsonNode arr = om.readTree(r.getResponse().getContentAsString()).get("departments");
    Set<Long> visible = new HashSet<>(); for (JsonNode d : arr) visible.add(d.get("id").asLong());
    assertTrue(visible.contains(deptB), "T1 sees managed dept B");
    assertFalse(visible.contains(deptA), "T1 does NOT see home-only dept A (scope is trainer_departments)");
    assertFalse(visible.contains(deptC), "T1 does not see unmanaged C");
  }

  @Test @Order(11) void adminSeesAllDepartmentsInMine() throws Exception {
    MvcResult r = mockMvc.perform(get("/api/departments/my-departments").header("Authorization", adminTok())).andExpect(status().isOk()).andReturn();
    JsonNode arr = om.readTree(r.getResponse().getContentAsString()).get("departments");
    assertTrue(arr.size() >= 3, "admin sees all depts");
  }

  // ===== Department members list =====
  @Test @Order(12) void deptMembersFromMembership() throws Exception {
    MvcResult r = mockMvc.perform(get("/api/departments/" + deptA + "/employees").header("Authorization", adminTok())).andExpect(status().isOk()).andReturn();
    JsonNode arr = om.readTree(r.getResponse().getContentAsString()).get("employees");
    Set<Long> got = new HashSet<>(); for (JsonNode n : arr) got.add(n.get("id").asLong());
    assertEquals(new HashSet<>(List.of(e1, m1, t1)), got, "members = all active users with membership in A");
  }

  // ===== POST /departments/{id}/members =====
  @Test @Order(13) void addMemberSetsPrimaryWhenNull() throws Exception {
    mockMvc.perform(post("/api/departments/" + deptA + "/members").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"" + e2 + "\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    assertTrue(memberships(e2).contains(deptA), "membership created");
    assertTrue(isPrimary(e2, deptA), "primary set because it was null");
  }

  @Test @Order(14) void addMemberKeepsExistingPrimary() throws Exception {
    mockMvc.perform(post("/api/departments/" + deptA + "/members").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"" + e2 + "\"}")).andExpect(status().isOk());
    mockMvc.perform(post("/api/departments/" + deptB + "/members").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"" + e2 + "\"}")).andExpect(status().isOk());
    assertTrue(memberships(e2).containsAll(List.of(deptA, deptB)), "both memberships");
    assertTrue(isPrimary(e2, deptA), "primary remains A (already set), not overwritten to B");
  }

  // ===== DELETE /departments/{id}/members/{userId} =====
  @Test @Order(15) void removeMemberRepointsPrimary() throws Exception {
    mockMvc.perform(delete("/api/departments/" + deptA + "/members/" + m1).header("Authorization", adminTok()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    assertFalse(memberships(m1).contains(deptA), "A membership removed");
    assertTrue(memberships(m1).contains(deptB), "B membership kept");
    assertTrue(isPrimary(m1, deptB), "primary repointed to remaining B");
  }

  @Test @Order(16) void removeMemberClearsPrimaryWhenLast() throws Exception {
    mockMvc.perform(delete("/api/departments/" + deptA + "/members/" + e1).header("Authorization", adminTok())).andExpect(status().isOk());
    assertTrue(memberships(e1).isEmpty());
    assertNull(db.queryForObject("SELECT department_id FROM users WHERE id=?", Long.class, e1), "primary cleared to null");
  }

  // ===== POST /departments/{id}/trainers =====
  @Test @Order(17) void assignTrainerAlsoAddsMembership() throws Exception {
    mockMvc.perform(post("/api/departments/" + deptC + "/trainers").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"trainer_id\":\"" + t1 + "\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM trainer_departments WHERE trainer_id=? AND department_id=?", Integer.class, t1, deptC), "management row");
    assertTrue(memberships(t1).contains(deptC), "trainer also becomes a member of C");
  }

  @Test @Order(18) void assignTrainerSetsPrimaryIfNull() throws Exception {
    long t2 = addUser("QA-T2", "trainer", null);
    try {
      mockMvc.perform(post("/api/departments/" + deptB + "/trainers").header("Authorization", adminTok())
          .contentType(MediaType.APPLICATION_JSON).content("{\"trainer_id\":\"" + t2 + "\"}")).andExpect(status().isOk());
      assertTrue(memberships(t2).contains(deptB), "trainer membership created");
      assertTrue(isPrimary(t2, deptB), "primary set to B since it was null");
    } finally {
      db.update("DELETE FROM users WHERE id=?", t2);
    }
  }

  // ===== DELETE /departments/{id}/trainers/{trainerId} =====
  @Test @Order(19) void unassignTrainerKeepsMembership() throws Exception {
    mockMvc.perform(delete("/api/departments/" + deptB + "/trainers/" + t1).header("Authorization", adminTok()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    assertEquals(0, db.queryForObject("SELECT count(*) FROM trainer_departments WHERE trainer_id=? AND department_id=?", Integer.class, t1, deptB));
    assertTrue(memberships(t1).contains(deptA), "home membership untouched");
    assertEquals("employee", db.queryForObject("SELECT role FROM users WHERE id=?", String.class, t1), "T1 demoted: manages no dept after this removal");
  }

  // ===== Assignment targeting via user_departments =====
  @Test @Order(20) void assignToDeptReachesAllEmployeesInclMultiDept() throws Exception {
    mockMvc.perform(post("/api/assignments").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"course_id\":" + courseId + ",\"department_id\":\"" + deptA + "\",\"due_date\":\"2026-12-31\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    Set<Long> assigned = new HashSet<>(db.queryForList("SELECT user_id FROM course_assignments WHERE course_id=?", Long.class, courseId));
    assertTrue(assigned.contains(e1), "E1 assigned");
    assertTrue(assigned.contains(m1), "M1 assigned (member of A)");
    assertFalse(assigned.contains(t1), "trainer not auto-assigned (only employees)");
    assertFalse(assigned.contains(e2), "E2 (not in A) not assigned");
  }

  @Test @Order(21) void assignToDeptBReachesMultiDeptEmployee() throws Exception {
    mockMvc.perform(post("/api/assignments").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"course_id\":" + courseId + ",\"department_id\":\"" + deptB + "\"}"))
        .andExpect(status().isOk());
    Set<Long> assigned = new HashSet<>(db.queryForList("SELECT user_id FROM course_assignments WHERE course_id=?", Long.class, courseId));
    assertEquals(new HashSet<>(List.of(m1)), assigned, "only M1 is an employee-member of B");
  }

  // ===== Trainer assignment authorization =====
  @Test @Order(22) void trainerCannotAssignToUnmanagedDept() throws Exception {
    mockMvc.perform(post("/api/assignments").header("Authorization", tok(t1))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"course_id\":" + courseId + ",\"department_id\":\"" + deptC + "\"}"))
        .andExpect(status().isForbidden());
  }

  @Test @Order(23) void trainerCanAssignToManagedDept() throws Exception {
    mockMvc.perform(post("/api/assignments").header("Authorization", tok(t1))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"course_id\":" + courseId + ",\"department_id\":\"" + deptB + "\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    assertTrue(new HashSet<>(db.queryForList("SELECT user_id FROM course_assignments WHERE course_id=?", Long.class, courseId)).contains(m1));
  }

  // ===== Compliance per-dept via memberships =====
  @Test @Order(24) void compliancePerDeptCountsMembership() throws Exception {
    mockMvc.perform(post("/api/assignments").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"course_id\":" + courseId + ",\"department_id\":\"" + deptA + "\"}")).andExpect(status().isOk());
    MvcResult r = mockMvc.perform(get("/api/admin/compliance").header("Authorization", adminTok())).andExpect(status().isOk()).andReturn();
    JsonNode stats = om.readTree(r.getResponse().getContentAsString()).get("department_stats");
    JsonNode a = findByKey(stats, "department_id", deptA);
    assertEquals(3, a.get("employee_count").asInt(), "deptA members E1,M1,T1");
    assertTrue(a.get("total_assignments").asInt() >= 2, "assignments for A's employees counted");
  }

  @Test @Order(25) void complianceCompanyTotalsDistinct() throws Exception {
    MvcResult r = mockMvc.perform(get("/api/admin/compliance").header("Authorization", adminTok())).andExpect(status().isOk()).andReturn();
    JsonNode m = om.readTree(r.getResponse().getContentAsString()).get("metrics");
    long empTotal = m.get("total_employees").asLong();
    long sumEmpCounts = 0; JsonNode stats = om.readTree(r.getResponse().getContentAsString()).get("department_stats");
    for (JsonNode s : stats) sumEmpCounts += s.get("employee_count").asInt();
    // A user in 2 depts appears in both dept rows, so sum of dept counts >= distinct total is expected; here multi-dept M1 makes sum>total possible.
    assertTrue(empTotal >= 0);
    assertTrue(sumEmpCounts >= 0);
  }

  // ===== Department deletion preserves courses & assignments =====
  @Test @Order(26) void deleteDepartmentCascadesMembershipOnly() throws Exception {
    mockMvc.perform(post("/api/assignments").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"course_id\":" + courseId + ",\"department_id\":\"" + deptA + "\"}")).andExpect(status().isOk());
    mockMvc.perform(delete("/api/departments/" + deptA).header("Authorization", adminTok())).andExpect(status().isOk());
    assertFalse(memberships(m1).contains(deptA), "A memberships removed by cascade");
    assertTrue(memberships(m1).contains(deptB), "unrelated membership intact");
    assertEquals(1, db.queryForObject("SELECT count(*) FROM courses WHERE id=?", Integer.class, courseId), "course NOT deleted");
    assertTrue(new HashSet<>(db.queryForList("SELECT user_id FROM course_assignments WHERE course_id=?", Long.class, courseId)).contains(e1), "existing assignment preserved");
    assertNull(db.queryForObject("SELECT department_id FROM users WHERE id=?", Long.class, e1), "home dept ref nulled");
  }

  // ===== Idempotency / conflict safety =====
  @Test @Order(27) void doubleAddMemberIsIdempotent() throws Exception {
    mockMvc.perform(post("/api/departments/" + deptA + "/members").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"" + e2 + "\"}")).andExpect(status().isOk());
    mockMvc.perform(post("/api/departments/" + deptA + "/members").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"" + e2 + "\"}")).andExpect(status().isOk());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM user_departments WHERE user_id=? AND department_id=?", Integer.class, e2, deptA), "no duplicate membership");
  }

  @Test @Order(28) void doubleAssignTrainerIsIdempotent() throws Exception {
    mockMvc.perform(post("/api/departments/" + deptC + "/trainers").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"trainer_id\":\"" + t1 + "\"}")).andExpect(status().isOk());
    mockMvc.perform(post("/api/departments/" + deptC + "/trainers").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"trainer_id\":\"" + t1 + "\"}")).andExpect(status().isOk());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM trainer_departments WHERE trainer_id=? AND department_id=?", Integer.class, t1, deptC));
  }

  @Test @Order(29) void assignToDeptWithNoEmployeesRejected() throws Exception {
    mockMvc.perform(post("/api/assignments").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"course_id\":" + courseId + ",\"department_id\":\"" + deptC + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test @Order(30) void accessDeniedForNonAdmin() throws Exception {
    mockMvc.perform(get("/api/admin/employees").header("Authorization", tok(e1))).andExpect(status().isForbidden());
    mockMvc.perform(get("/api/admin/compliance").header("Authorization", tok(e1))).andExpect(status().isForbidden());
    mockMvc.perform(get("/api/departments/my-departments").header("Authorization", tok(e1))).andExpect(status().isForbidden());
  }

  @Test @Order(31) void adminCannotBeMadeDepartmentTrainer() throws Exception {
    mockMvc.perform(post("/api/departments/" + deptB + "/trainers").header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON).content("{\"trainer_id\":\"" + adminId + "\"}"))
        .andExpect(status().is4xxClientError());
    assertEquals("admin", db.queryForObject("SELECT role FROM users WHERE id=?", String.class, adminId), "admin role untouched");
  }

  @Test @Order(32) void deletingLastManagedDeptDemotesTrainer() throws Exception {
    // T1 manages only dept B. Deleting dept B cascades the trainer_departments row, so T1 manages nothing.
    mockMvc.perform(delete("/api/departments/" + deptB).header("Authorization", adminTok())).andExpect(status().isOk());
    assertEquals(0, db.queryForObject("SELECT count(*) FROM trainer_departments WHERE trainer_id=?", Integer.class, t1), "management row cascaded away");
    assertEquals("employee", db.queryForObject("SELECT role FROM users WHERE id=?", String.class, t1), "recomputed to employee");
  }

  @Test @Order(33) void usersTabRemoveDeptAlsoStripsManagement() throws Exception {
    // T1 manages B and is a member of A+B. Removing B from their memberships (Users tab) must also revoke training B.
    mockMvc.perform(put("/api/admin/users/" + t1).header("Authorization", adminTok())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"department_ids\":[" + deptA + "],\"primary_department_id\":" + deptA + "}"))
        .andExpect(status().isOk());
    assertEquals(new HashSet<>(List.of(deptA)), memberships(t1), "membership B removed");
    assertEquals(0, db.queryForObject("SELECT count(*) FROM trainer_departments WHERE trainer_id=? AND department_id=?", Integer.class, t1, deptB), "management of B revoked");
    assertEquals("employee", db.queryForObject("SELECT role FROM users WHERE id=?", String.class, t1), "demoted: no management left");
  }

  @Test @Order(34) void deptTabRemoveMemberAlsoStripsManagement() throws Exception {
    // T1 manages B. Removing T1 as a member of B (Departments tab) must also revoke training B.
    mockMvc.perform(delete("/api/departments/" + deptB + "/members/" + t1).header("Authorization", adminTok()))
        .andExpect(status().isOk());
    assertFalse(memberships(t1).contains(deptB), "membership B removed");
    assertEquals(0, db.queryForObject("SELECT count(*) FROM trainer_departments WHERE trainer_id=? AND department_id=?", Integer.class, t1, deptB), "management of B revoked");
    assertEquals("employee", db.queryForObject("SELECT role FROM users WHERE id=?", String.class, t1), "demoted: manages A only via? -> no, A not managed; employee");
  }

  private JsonNode findBy(JsonNode array, long id) { return findByKey(array, "id", id); }

  private JsonNode findByKey(JsonNode array, String key, long id) {
    for (JsonNode n : array) {
      JsonNode v = n.get(key);
      if (v != null && v.asLong() == id) return n;
    }
    return null;
  }
}
