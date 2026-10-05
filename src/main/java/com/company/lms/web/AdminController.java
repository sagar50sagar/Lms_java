package com.company.lms.web;

import com.company.lms.service.DepartmentEnrollmentService;
import com.company.lms.service.EmailOtpService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController extends ApiSupport {
	private final JdbcTemplate db;
	private final PasswordEncoder passwords;
	private final EmailOtpService otp;
	private final DepartmentEnrollmentService departmentEnrollments;

	public AdminController(JdbcTemplate db, PasswordEncoder passwords, EmailOtpService otp,
			DepartmentEnrollmentService departmentEnrollments) {
		this.db = db;
		this.passwords = passwords;
		this.otp = otp;
		this.departmentEnrollments = departmentEnrollments;
	}

	@GetMapping("/compliance")
	public Map<String, Object> compliance() {
		// Only employees are compliance targets (see DepartmentEnrollmentService): an administrator or trainer who
		// takes a course for their own interest must not move the company number, up or down.
		Map<String, Object> m = new LinkedHashMap<>(db.queryForMap(
				"SELECT (SELECT COUNT(*) FROM users WHERE role='employee' AND is_active) total_employees,(SELECT COUNT(*) FROM users WHERE role='trainer' AND is_active) total_trainers,(SELECT COUNT(*) FROM courses WHERE is_published) total_courses,(SELECT COUNT(*) FROM certificates ct JOIN users cu ON cu.id=ct.user_id AND cu.role='employee' AND cu.is_active) total_certificates,COUNT(*) total_assignments,COUNT(*) FILTER(WHERE ca.completed_at IS NOT NULL) completed_assignments,COUNT(*) FILTER(WHERE ca.due_date<CURRENT_DATE AND ca.completed_at IS NULL) overdue_assignments FROM course_assignments ca JOIN users u ON u.id=ca.user_id AND u.role='employee' AND u.is_active"));
		long total = ((Number) m.get("total_assignments")).longValue(),
				done = ((Number) m.get("completed_assignments")).longValue();
		m.put("company_compliance_rate", total == 0 ? 100 : Math.round(done * 100.0 / total));
		List<Map<String, Object>> rows = db.queryForList(
				"SELECT d.id department_id,d.name department_name,COUNT(DISTINCT u.id) FILTER(WHERE u.role='employee') employee_count,COUNT(DISTINCT ca.id) total_assignments,COUNT(DISTINCT ca.id) FILTER(WHERE ca.completed_at IS NOT NULL) completed_assignments,COUNT(DISTINCT ca.id) FILTER(WHERE ca.due_date<CURRENT_DATE AND ca.completed_at IS NULL) overdue_assignments FROM departments d LEFT JOIN user_departments ud ON ud.department_id=d.id LEFT JOIN users u ON u.id=ud.user_id AND u.is_active AND u.role='employee' LEFT JOIN course_assignments ca ON ca.user_id=u.id GROUP BY d.id,d.name ORDER BY d.name");
		List<Map<String, Object>> deptStats = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			Map<String, Object> d = new LinkedHashMap<>(row);
			long dTotal = d.get("total_assignments") != null ? ((Number) d.get("total_assignments")).longValue() : 0;
			long dDone = d.get("completed_assignments") != null ? ((Number) d.get("completed_assignments")).longValue() : 0;
			d.put("compliance_percent", dTotal == 0 ? 100 : Math.round(dDone * 100.0 / dTotal));
			deptStats.add(d);
		}
		return Map.of("success", true, "metrics", m, "department_stats", deptStats);
	}

	@GetMapping("/employees")
	public Map<String, Object> employees(@RequestParam(required = false) String role,
			@RequestParam(required = false) String search) {
		String sql = "SELECT u.id,u.employee_id,u.full_name,u.email,u.role,u.designation,u.is_active,u.password_setup_required,u.created_at,d.name department_name,d.id department_id,(SELECT string_agg(m.department_id::text,',' ORDER BY m.department_id) FROM user_departments m WHERE m.user_id=u.id) membership_ids,COUNT(DISTINCT ca.id) total_assigned_courses,COUNT(DISTINCT ca.id) FILTER(WHERE ca.completed_at IS NOT NULL) completed_courses,COUNT(DISTINCT ca.id) FILTER(WHERE ca.due_date<CURRENT_DATE AND ca.completed_at IS NULL) overdue_courses FROM users u LEFT JOIN departments d ON d.id=u.department_id LEFT JOIN course_assignments ca ON ca.user_id=u.id WHERE u.role IN ('trainer','employee')";
		List<Object> p = new ArrayList<>();
		if (role != null && List.of("trainer", "employee").contains(role)) {
			sql += " AND u.role=?";
			p.add(role);
		}
		if (search != null) {
			sql += " AND (u.full_name ILIKE ? OR u.email ILIKE ? OR u.employee_id ILIKE ?)";
			for (int i = 0; i < 3; i++)
				p.add("%" + search + "%");
		}
		List<Map<String, Object>> rows = db.queryForList(sql + " GROUP BY u.id,u.employee_id,u.full_name,u.email,u.role,u.designation,u.is_active,u.password_setup_required,u.created_at,d.name,d.id ORDER BY u.full_name", p.toArray());
		for (Map<String, Object> r : rows) {
			Object ids = r.remove("membership_ids");
			List<Map<String, Object>> depts = new ArrayList<>();
			if (ids != null && !ids.toString().isBlank())
				for (String id : ids.toString().split(",")) {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", Long.parseLong(id));
					depts.add(m);
				}
			r.put("departments", depts);
		}
		return ok("employees", rows);
	}

	@PostMapping("/users")
	@Transactional
	public ResponseEntity<?> create(@RequestBody Map<String, Object> b) {
		if (b.get("full_name") == null || b.get("email") == null)
			return ResponseEntity.badRequest()
					.body(Map.of("success", false, "message", "full_name and email are required."));
		String email = b.get("email").toString().trim().toLowerCase();
		// Role is derived from department management; every new account starts as an employee.
		String role = "employee";
		if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
			return ResponseEntity.badRequest()
					.body(Map.of("success", false, "message", "A valid email address is required."));
		if (!db.queryForList("SELECT id FROM users WHERE LOWER(email)=LOWER(?)", email).isEmpty())
			return ResponseEntity.status(409)
					.body(Map.of("success", false, "message", "An account with this email already exists."));
		String employeeId = b.get("employee_id") == null || b.get("employee_id").toString().isBlank()
				? "EMP-" + (100000 + new java.security.SecureRandom().nextInt(900000))
				: plain(b.get("employee_id"), 40);
		String fullName = plain(b.get("full_name"), 120);
		if (fullName == null || fullName.isBlank())
			return ResponseEntity.badRequest()
					.body(Map.of("success", false, "message", "A valid full name is required."));
		Integer departmentId = departmentId(b);
		if (b.containsKey("department_id") && b.get("department_id") != null && departmentId == null)
			return ResponseEntity.badRequest()
					.body(Map.of("success", false, "message", "Department must be a valid numeric ID."));
		Map<String, Object> u = db.queryForMap(
				"INSERT INTO users(employee_id,full_name,email,password_hash,role,department_id,designation,password_setup_required) VALUES(?,?,?,?,?,?,?,TRUE) RETURNING id,employee_id,full_name,email,role,department_id,designation,is_active,password_setup_required",
				employeeId, fullName, email,
				passwords.encode(java.util.UUID.randomUUID().toString()), role, departmentId, plain(b.get("designation"), 80));
		long newUserId = ((Number) u.get("id")).longValue();
		if (departmentId != null) {
			db.update("INSERT INTO user_departments(user_id,department_id) VALUES(?,?) ON CONFLICT DO NOTHING", newUserId, departmentId);
			departmentEnrollments.catchUpMember(newUserId, List.of(departmentId.longValue()));
		}
		otp.sendAccountSetupLink(newUserId, email, employeeId);
		return ResponseEntity.status(201).body(Map.of("success", true, "message",
				"User created. An account setup link is on its way to their email.", "user", u));
	}

	@PutMapping("/users/{id}")
	@Transactional
	public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> b) {
		if (db.queryForList("SELECT id FROM users WHERE id=?", id).isEmpty())
			return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found."));

		// Role is intentionally NOT editable here: it is derived from department management
		// (trainer_departments) and only changes through the Departments tab.
		List<String> sets = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		List<Long> joinedDepartmentIds = new ArrayList<>();
		if (b.get("designation") != null) { sets.add("designation=?"); params.add(plain(b.get("designation"), 80)); }
		if (b.containsKey("is_active") && b.get("is_active") != null) { sets.add("is_active=?"); params.add(b.get("is_active")); }

		// Reconcile memberships + primary department.
		if (b.containsKey("department_ids")) {
			List<Integer> deptIds = new ArrayList<>();
			Object arr = b.get("department_ids");
			if (arr instanceof List<?> list) {
				for (Object o : list) {
					if (o == null || o.toString().isBlank()) continue;
					try { deptIds.add(Integer.parseInt(o.toString().trim())); }
					catch (NumberFormatException e) {
						return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Department IDs must be numeric."));
					}
				}
			}
			Integer primary = null;
			Object pObj = b.get("primary_department_id");
			if (pObj != null && !pObj.toString().isBlank()) {
				try { primary = Integer.parseInt(pObj.toString().trim()); }
				catch (NumberFormatException e) {
					return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Primary department must be numeric."));
				}
			}
			if (primary != null && !deptIds.contains(primary)) deptIds.add(primary);
			db.update("DELETE FROM user_departments WHERE user_id=?", id);
			for (Integer d : deptIds)
				db.update("INSERT INTO user_departments(user_id,department_id) VALUES(?,?) ON CONFLICT DO NOTHING", id, d);
			// Management must be a subset of membership: drop trainer rights on any dept removed here, then recompute role.
			if (deptIds.isEmpty()) {
				db.update("DELETE FROM trainer_departments WHERE trainer_id=?", id);
			} else {
				String placeholders = String.join(",", Collections.nCopies(deptIds.size(), "?"));
				List<Object> args = new ArrayList<>();
				args.add(id);
				args.addAll(deptIds);
				db.update("DELETE FROM trainer_departments WHERE trainer_id=? AND department_id NOT IN (" + placeholders + ")", args.toArray());
			}
			db.update("UPDATE users SET role='employee' WHERE id=? AND role='trainer' AND NOT EXISTS(SELECT 1 FROM trainer_departments WHERE trainer_id=?)", id, id);
			Integer newPrimary = primary != null ? primary : (deptIds.isEmpty() ? null : deptIds.get(0));
			sets.add("department_id=?"); params.add(newPrimary);
			deptIds.forEach(d -> joinedDepartmentIds.add(d.longValue()));
		} else if (b.containsKey("department_id")) {
			// Additive: set/change the primary department and ensure membership exists.
			Integer departmentId = departmentId(b);
			if (b.get("department_id") != null && !b.get("department_id").toString().isBlank() && departmentId == null)
				return ResponseEntity.badRequest()
						.body(Map.of("success", false, "message", "Department must be a valid numeric ID."));
			sets.add("department_id=?"); params.add(departmentId);
			if (departmentId != null) {
				db.update("INSERT INTO user_departments(user_id,department_id) VALUES(?,?) ON CONFLICT DO NOTHING", id, departmentId);
				joinedDepartmentIds.add(departmentId.longValue());
			}
		}

		if (!sets.isEmpty()) {
			params.add(id);
			db.update("UPDATE users SET " + String.join(",", sets) + " WHERE id=?", params.toArray());
		}
		// Disabling must retire live sessions too, otherwise re-enabling the account revives them.
		if (b.get("is_active") != null && !Boolean.parseBoolean(b.get("is_active").toString()))
			db.update("UPDATE users SET token_version=token_version+1 WHERE id=?", id);

		departmentEnrollments.catchUpMember(id, joinedDepartmentIds);

		return ResponseEntity.ok(message("User updated successfully."));
	}

	@DeleteMapping("/users/{id}")
	@Transactional
	public ResponseEntity<?> deleteUser(@PathVariable long id, Authentication a) {
		if (id == userId(a))
			return ResponseEntity.badRequest().body(Map.of("success", false, "message", "You cannot delete your own account."));
		List<Map<String, Object>> target = db.queryForList("SELECT full_name,role FROM users WHERE id=?", id);
		if (target.isEmpty())
			return ResponseEntity.status(404).body(Map.of("success", false, "message", "Account not found."));
		if ("admin".equalsIgnoreCase(String.valueOf(target.getFirst().get("role"))))
			return ResponseEntity.status(403).body(Map.of("success", false,
					"message", "Administrator accounts cannot be deleted. Deactivate the account instead."));
		// Every foreign key into users either cascades or nulls out, so no child cleanup is needed here.
		db.update("DELETE FROM users WHERE id=?", id);
		return ResponseEntity.ok(message(target.getFirst().get("full_name")
				+ " and all of their training records have been permanently deleted."));
	}

	@PostMapping("/users/{id}/reset-password")
	public ResponseEntity<?> resetPassword(@PathVariable long id) {
		List<Map<String, Object>> users = db.queryForList(
				"SELECT email,employee_id,password_setup_required FROM users WHERE id=? AND role IN ('trainer','employee') AND is_active=TRUE",
				id);
		if (users.isEmpty())
			return ResponseEntity.status(404)
					.body(Map.of("success", false, "message", "Active normal user not found."));
		Map<String, Object> user = users.getFirst();
		if (Boolean.TRUE.equals(user.get("password_setup_required")))
			return ResponseEntity.badRequest().body(Map.of("success", false, "message",
					"This user has not activated their account. Resend the setup link instead."));
		otp.send((String) user.get("email"), "password_reset", (String) user.get("employee_id"));
		return ResponseEntity.ok(message("Password reset code is on its way to the user's email."));
	}

	@PostMapping("/users/{id}/resend-setup")
	public ResponseEntity<?> resendSetup(@PathVariable long id) {
		List<Map<String, Object>> users = db.queryForList(
				"SELECT id,email,employee_id FROM users WHERE id=? AND role IN ('trainer','employee') AND is_active=TRUE AND password_setup_required=TRUE",
				id);
		if (users.isEmpty())
			return ResponseEntity.status(404)
					.body(Map.of("success", false, "message", "Pending account setup user not found."));
		Map<String, Object> user = users.getFirst();
		otp.sendAccountSetupLink(((Number) user.get("id")).longValue(), (String) user.get("email"),
				(String) user.get("employee_id"));
		return ResponseEntity.ok(message("A new account setup link is on its way to the user's email."));
	}

	/** Delivery is queued, so a permanently failing address is otherwise invisible to the operator. */
	@GetMapping("/email-status")
	public Map<String, Object> emailStatus() {
		return Map.of("success", true,
				"summary", db.queryForList("SELECT purpose,status,COUNT(*) AS count,MAX(created_at) AS latest FROM email_outbox GROUP BY purpose,status ORDER BY MAX(created_at) DESC"),
				"recent_failures", db.queryForList("SELECT id,to_email,purpose,attempts,LEFT(COALESCE(last_error,''),300) AS last_error FROM email_outbox WHERE status='failed' ORDER BY id DESC LIMIT 20"));
	}

	private Integer departmentId(Map<String, Object> body) {
		Object value = body.get("department_id");
		if (value == null || value.toString().isBlank())
			return null;
		try {
			return Integer.valueOf(value.toString());
		} catch (NumberFormatException ignored) {
			return null;
		}
	}
}
