package com.company.lms.web;

import com.company.lms.service.CourseAccess;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/courses")
public class CourseController extends ApiSupport {
	private final JdbcTemplate db;
	private final CourseAccess access;

	public CourseController(JdbcTemplate db, CourseAccess access) {
		this.db = db;
		this.access = access;
	}

	private Map<String, Object> notYourCourse() {
		return Map.of("success", false, "message", "You can only change courses you created.");
	}

	@GetMapping
	public Map<String, Object> all(@RequestParam(required = false) String category,
			@RequestParam(required = false) String search, Authentication auth) {
		boolean employee = auth != null && "employee".equals(role(auth));
		boolean isAdminOrTrainer = auth != null && ("admin".equals(role(auth)) || "trainer".equals(role(auth)));
		List<Object> p = new ArrayList<>();
		String sql;
		if (employee) {
			sql = "SELECT c.*,u.full_name creator_name,COUNT(DISTINCT l.id) total_lessons,COALESCE(SUM(l.duration_mins),0) total_duration_mins,ca.status assignment_status,ca.due_date,ca.completed_at FROM course_assignments ca JOIN courses c ON c.id=ca.course_id LEFT JOIN users u ON u.id=c.created_by LEFT JOIN lessons l ON l.course_id=c.id WHERE ca.user_id=?";
			p.add(userId(auth));
		} else {
			sql = "SELECT c.*,u.full_name creator_name,COUNT(DISTINCT l.id) total_lessons,COALESCE(SUM(l.duration_mins),0) total_duration_mins,COUNT(DISTINCT ca.id) enrolled_count FROM courses c LEFT JOIN users u ON u.id=c.created_by LEFT JOIN lessons l ON l.course_id=c.id LEFT JOIN course_assignments ca ON ca.course_id=c.id WHERE 1=1";
			if (!isAdminOrTrainer) {
				sql += " AND 1=0"; // Anonymous visitors cannot browse courses; visibility is limited to assigned employees and staff.
			}
		}
		if (category != null && !category.isBlank()) {
			sql += " AND c.category=?";
			p.add(category);
		}
		if (search != null && !search.isBlank()) {
			sql += " AND (c.title ILIKE ? OR c.description ILIKE ?)";
			p.add("%" + search.trim() + "%");
			p.add("%" + search.trim() + "%");
		}
		sql += " GROUP BY c.id,u.full_name" + (employee ? ",ca.status,ca.due_date,ca.completed_at" : "")
				+ " ORDER BY c.is_mandatory DESC,c.created_at DESC";
		return ok("courses", db.queryForList(sql, p.toArray()));
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> one(@PathVariable long id, Authentication auth) {
		List<Map<String, Object>> courses = db.queryForList(
				"SELECT c.*,u.full_name creator_name,u.email creator_email FROM courses c LEFT JOIN users u ON u.id=c.created_by WHERE c.id=?",
				id);
		if (courses.isEmpty())
			return ResponseEntity.status(404).body(Map.of("success", false, "message", "Training course not found."));
		Map<String, Object> course = new LinkedHashMap<>(courses.getFirst());
		if (auth == null)
			return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please sign in to view this course."));
		long currentUid = userId(auth);
		Object createdBy = val(course, "created_by");
		boolean isCreator = createdBy != null && ((Number) createdBy).longValue() == currentUid;
		boolean canView = "admin".equals(role(auth)) || isCreator
				|| !db.queryForList("SELECT 1 FROM course_assignments WHERE course_id=? AND user_id=?", id, currentUid).isEmpty();
		if (!canView)
			return ResponseEntity.status(403).body(Map.of("success", false, "message", "This course is private. It is visible only to assigned employees and administrators."));
		List<Map<String, Object>> chapters = db
				.queryForList("SELECT * FROM chapters WHERE course_id=? ORDER BY sequence_order,id", id);
		List<Map<String, Object>> lessons = db.queryForList(
				"SELECT id,chapter_id,course_id,title,content_type,duration_mins,sequence_order,video_url,content FROM lessons WHERE course_id=? ORDER BY sequence_order,id",
				id);
		List<Map<String, Object>> quizzes = db.queryForList(
				"SELECT id,course_id,title,passing_score,time_limit_mins FROM quizzes WHERE course_id=?", id);
		List<Long> completed = new ArrayList<>();
		Map<String, Object> assignment = null, certificate = null;
		List<Map<String, Object>> submissions = List.of();
		if (auth != null) {
			long uid = userId(auth);
			List<Map<String, Object>> progRows = db.queryForList(
					"SELECT lesson_id FROM lesson_progress WHERE user_id=? AND course_id=? AND completed=TRUE", uid,
					id);
			for (var pr : progRows) {
				Object lidObj = val(pr, "lesson_id");
				if (lidObj != null)
					completed.add(((Number) lidObj).longValue());
			}
			var ass = db.queryForList("SELECT * FROM course_assignments WHERE user_id=? AND course_id=?", uid, id);
			assignment = ass.isEmpty() ? null : ass.getFirst();
			if (!quizzes.isEmpty()) {
				Object qId = val(quizzes.getFirst(), "id");
				if (qId != null)
					submissions = db.queryForList(
							"SELECT * FROM quiz_submissions WHERE user_id=? AND quiz_id=? ORDER BY submitted_at DESC",
							uid, qId);
			}
			var cert = db.queryForList("SELECT * FROM certificates WHERE user_id=? AND course_id=?", uid, id);
			certificate = cert.isEmpty() ? null : cert.getFirst();
		}
		List<Map<String, Object>> nested = new ArrayList<>();
		for (var ch : chapters) {
			Map<String, Object> c = new LinkedHashMap<>(ch);
			List<Map<String, Object>> ls = new ArrayList<>();
			for (var lesson : lessons) {
				Object chId = val(lesson, "chapter_id");
				Object currentChId = val(ch, "id");
				if (chId != null && currentChId != null
						&& ((Number) chId).longValue() == ((Number) currentChId).longValue()) {
					Map<String, Object> l = new LinkedHashMap<>(lesson);
					Object lId = val(lesson, "id");
					boolean isComp = lId != null
							&& completed.stream().anyMatch(comp -> comp.longValue() == ((Number) lId).longValue());
					l.put("is_completed", isComp);
					ls.add(l);
				}
			}
			c.put("lessons", ls);
			nested.add(c);
		}
		course.put("chapters", nested);
		course.put("total_lessons", lessons.size());
		course.put("quizzes", quizzes);
		// Use LinkedHashMap instead of Map.of() — Map.of() throws NullPointerException
		// if any value is null
		Map<String, Object> progressMap = new LinkedHashMap<>();
		progressMap.put("completed_count", completed.size());
		progressMap.put("percent", lessons.isEmpty() ? 0 : Math.round(completed.size() * 100.0 / lessons.size()));
		progressMap.put("completed_lesson_ids", completed);
		progressMap.put("assignment", assignment);
		progressMap.put("quiz_submissions", submissions);
		progressMap.put("certificate", certificate);
		course.put("user_progress", progressMap);
		return ResponseEntity.ok(ok("course", course));
	}

	@PostMapping
	@PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
	public ResponseEntity<?> create(@RequestBody Map<String, Object> b, Authentication a) {
		String title = plain(b.get("title"), 150);
		if (title == null || title.isBlank())
			return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Course title is required."));
		String slug = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "") + "-"
				+ (1000 + new Random().nextInt(9000));
		boolean isPub = false; // New courses always start as Draft; they publish automatically when assigned to an employee.
		Map<String, Object> c = db.queryForMap(
				"INSERT INTO courses(title,slug,description,category,is_mandatory,estimated_duration_hours,created_by,is_published) VALUES(?,?,?,?,?,?,?,?) RETURNING *",
				title, slug, richText(b.get("description"), 4000),
				b.get("category") == null ? "General" : plain(b.get("category"), 60),
				bool(b, "is_mandatory"), number(b, "estimated_duration_hours", 1), userId(a), isPub);
		return ResponseEntity.status(201)
				.body(Map.of("success", true, "message", "Course created successfully.", "course", c));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
	public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> b, Authentication a) {
		if (!access.canEdit(userId(a), isAdmin(a), id))
			return ResponseEntity.status(403).body(notYourCourse());
		int count = db.update(
				"UPDATE courses SET title=COALESCE(?,title),description=COALESCE(?,description),category=COALESCE(?,category),is_mandatory=COALESCE(?,is_mandatory),estimated_duration_hours=COALESCE(?,estimated_duration_hours),updated_at=CURRENT_TIMESTAMP WHERE id=?",
				b.containsKey("title") ? plain(b.get("title"), 150) : null,
				b.containsKey("description") ? richText(b.get("description"), 4000) : null,
				b.containsKey("category") ? plain(b.get("category"), 60) : null,
				b.get("is_mandatory"), b.get("estimated_duration_hours"), id);
		if (count == 0)
			return ResponseEntity.status(404).body(Map.of("success", false, "message", "Course not found."));
		Map<String, Object> updatedCourse = db.queryForMap("SELECT * FROM courses WHERE id=?", id);
		return ResponseEntity
				.ok(Map.of("success", true, "message", "Course updated successfully.", "course", updatedCourse));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<?> delete(@PathVariable long id) {
		return db.update("DELETE FROM courses WHERE id=?", id) == 0
				? ResponseEntity.status(404).body(Map.of("success", false, "message", "Course not found."))
				: ResponseEntity.ok(message("Course deleted successfully."));
	}

	@PostMapping("/{courseId}/chapters")
	@PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
	public ResponseEntity<?> chapter(@PathVariable long courseId, @RequestBody Map<String, Object> b, Authentication a) {
		if (!access.canEdit(userId(a), isAdmin(a), courseId))
			return ResponseEntity.status(403).body(notYourCourse());
		Map<String, Object> r = db.queryForMap(
				"INSERT INTO chapters(course_id,title,sequence_order) VALUES(?,?,?) RETURNING *", courseId,
				plain(b.get("title"), 200), number(b, "sequence_order", 1));
		return ResponseEntity.status(201).body(ok("chapter", r));
	}

	@PostMapping("/{courseId}/chapters/{chapterId}/lessons")
	@PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
	public ResponseEntity<?> lesson(@PathVariable long courseId, @PathVariable long chapterId,
			@RequestBody Map<String, Object> b, Authentication a) {
		if (!access.canEdit(userId(a), isAdmin(a), courseId))
			return ResponseEntity.status(403).body(notYourCourse());
		if (!access.chapterBelongsToCourse(chapterId, courseId))
			return ResponseEntity.status(400).body(Map.of("success", false, "message", "That chapter is not part of this course."));
		String videoUrl = videoEmbedUrl(b.get("video_url"));
		if (videoUrl == null)
			throw bad("Video must be a full https:// link to a YouTube video or a shared Google Drive file.");
		Map<String, Object> r = db.queryForMap(
				"INSERT INTO lessons(chapter_id,course_id,title,content_type,content,video_url,duration_mins,sequence_order) VALUES(?,?,?,?,?,?,?,?) RETURNING *",
				chapterId, courseId, plain(b.get("title"), 200), plain(b.get("content_type"), 20),
				richText(b.get("content"), 20000), videoUrl.isEmpty() ? null : videoUrl,
				number(b, "duration_mins", 10), number(b, "sequence_order", 1));
		return ResponseEntity.status(201).body(ok("lesson", r));
	}

	private boolean bool(Map<String, Object> b, String k) {
		return Boolean.parseBoolean(String.valueOf(b.getOrDefault(k, false)));
	}

	private Number number(Map<String, Object> b, String k, Number d) {
		Object v = b.get(k);
		return v instanceof Number n ? n : d;
	}
}
