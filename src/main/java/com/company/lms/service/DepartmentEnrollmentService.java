package com.company.lms.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A course granted to a department is materialised as one row per member, so someone who joins that
 * department later has to be caught up against the same grant. Joiners inherit the department's
 * deadline; when that deadline has already passed they get the original learning window measured from
 * today, so a new employee never starts their career already overdue on training they were not around for.
 */
@Service
public class DepartmentEnrollmentService {

	private static final int FALLBACK_WINDOW_DAYS = 30;

	private final JdbcTemplate db;

	public DepartmentEnrollmentService(JdbcTemplate db) {
		this.db = db;
	}

	@Transactional
	public void catchUpMember(long userId, Collection<Long> departmentIds) {
		if (departmentIds.isEmpty())
			return;
		List<Map<String, Object>> me = db.queryForList("SELECT role, is_active FROM users WHERE id=?", userId);
		if (me.isEmpty())
			return;
		String role = String.valueOf(me.get(0).get("role"));
		if (!Boolean.TRUE.equals(me.get(0).get("is_active")) || "admin".equals(role))
			return;

		for (Long departmentId : departmentIds) {
			if (departmentId == null)
				continue;
			inheritDepartmentGrants(userId, departmentId);
		}
	}

	private void inheritDepartmentGrants(long userId, long departmentId) {
		List<Map<String, Object>> grants = db.queryForList(
				"SELECT course_id, MIN(assigned_by) assigned_by, MIN(due_date) due_date, "
						+ "MIN(due_date - CAST(enrolled_at AS DATE)) learning_window_days "
						+ "FROM course_assignments WHERE assigned_department_id=? GROUP BY course_id",
				departmentId);
		for (Map<String, Object> grant : grants) {
			Long courseId = number(grant.get("course_id"));
			if (courseId == null)
				continue;
			Object raw = grant.get("due_date");
			java.sql.Date inherited = raw instanceof java.sql.Date d ? d : null;
			java.sql.Date effective = null;
			if (inherited != null) {
				LocalDate deadline = inherited.toLocalDate();
				if (!deadline.isBefore(LocalDate.now())) {
					effective = inherited;
				} else {
					Long window = number(grant.get("learning_window_days"));
					int days = window == null || window <= 0 ? FALLBACK_WINDOW_DAYS : window.intValue();
					effective = java.sql.Date.valueOf(LocalDate.now().plusDays(days));
				}
			}
			// DO NOTHING: an employee who already owns this course keeps their own deadline and progress.
			db.update("INSERT INTO course_assignments(course_id,user_id,assigned_by,due_date,status,assigned_department_id) "
					+ "VALUES(?,?,?,?,'enrolled',?) ON CONFLICT(course_id,user_id) DO NOTHING",
					courseId, userId, number(grant.get("assigned_by")), effective, departmentId);
		}
	}

	private static Long number(Object value) {
		return value instanceof Number n ? n.longValue() : null;
	}
}
