package com.company.lms.web;
import com.company.lms.service.CourseAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/quizzes")
public class QuizController extends ApiSupport {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final ProgressController progress;
  private final CourseAccess access;

  public QuizController(JdbcTemplate db, ObjectMapper json, ProgressController progress, CourseAccess access) {
    this.db = db; this.json = json; this.progress = progress; this.access = access;
  }

  /** Parse a JSONB value from PostgreSQL into a real Java object (List/Map).
   *  JDBC returns JSONB columns as PGobject (not String) — call toString() to get the JSON text,
   *  then deserialize it so the frontend receives a proper array/object. */
  private Object parseJsonb(Object raw) {
    if (raw == null) return null;
    // PGobject, String, or any other wrapper — toString() always gives the JSON text
    try { return json.readValue(raw.toString(), Object.class); } catch (Exception e) { return raw; }
  }

  /** Apply parseJsonb to the "options" field of every question row. */
  private List<Map<String,Object>> parseOptions(List<Map<String,Object>> questions) {
    for (var q : questions) {
      Object opts = q.get("options");
      if (opts == null) opts = q.get("OPTIONS"); // case fallback
      q.put("options", parseJsonb(opts));
    }
    return questions;
  }

  @GetMapping("/course/{courseId}")
  public ResponseEntity<?> get(@PathVariable long courseId, Authentication a) {
    var qs = db.queryForList("SELECT * FROM quizzes WHERE course_id=?", courseId);
    if (qs.isEmpty()) return ResponseEntity.status(404).body(Map.of("success", false, "message", "No final assessment exam found for this course."));
    Map<String,Object> quiz = new LinkedHashMap<>(qs.getFirst());
    var questions = db.queryForList(
      "SELECT id,quiz_id,question_type,question_text,options,correct_option,explanation,sequence_order FROM quiz_questions WHERE quiz_id=? ORDER BY sequence_order,id",
      quiz.get("id"));
    // Strip answers for non-admin/trainer roles
    if (!List.of("trainer", "admin").contains(role(a)))
      for (var q : questions) { q.remove("correct_option"); q.remove("explanation"); }
    // Parse options JSONB string → real List so frontend gets a proper array
    parseOptions(questions);
    quiz.put("questions", questions);
    quiz.put("user_submissions", db.queryForList(
      "SELECT * FROM quiz_submissions WHERE quiz_id=? AND user_id=? ORDER BY submitted_at DESC",
      quiz.get("id"), userId(a)));
    return ResponseEntity.ok(ok("quiz", quiz));
  }

  @PostMapping("/{quizId}/submit")
  public ResponseEntity<?> submit(@PathVariable long quizId, @RequestBody Map<String,Object> b, Authentication a) throws Exception {
    if (!(b.get("answers") instanceof Map<?,?> answers))
      return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Answers object is required."));
    var quizzes = db.queryForList("SELECT * FROM quizzes WHERE id=?", quizId);
    if (quizzes.isEmpty()) return ResponseEntity.status(404).body(Map.of("success", false, "message", "Quiz not found."));
    var quiz = quizzes.getFirst();
    var qs = db.queryForList("SELECT id,question_type,question_text,correct_option,explanation FROM quiz_questions WHERE quiz_id=?", quizId);
    int correct = 0;
    List<Map<String,Object>> evaluation = new ArrayList<>();
    for (var q : qs) {
      Object submitted = answers.get(String.valueOf(q.get("id")));
      boolean hit = normal(submitted).equals(normal(q.get("correct_option"))) && !normal(submitted).isBlank();
      if (hit) correct++;
      Map<String,Object> e = new LinkedHashMap<>();
      e.put("question_id", q.get("id"));
      e.put("question_text", q.get("question_text"));
      e.put("submitted_answer", submitted);
      e.put("correct_answer", q.get("correct_option"));
      e.put("is_correct", hit);
      e.put("explanation", q.get("explanation"));
      evaluation.add(e);
    }
    double percent = qs.isEmpty() ? 0 : Math.round(correct * 10000.0 / qs.size()) / 100.0;
    boolean passed = percent >= ((Number) quiz.get("passing_score")).doubleValue();
    db.update("INSERT INTO quiz_submissions(quiz_id,user_id,score,total_questions,percentage,passed,answers) VALUES(?,?,?,?,?,?,?::jsonb)",
      quizId, userId(a), correct, qs.size(), percent, passed, json.writeValueAsString(answers));
    String code = null;
    if (passed) {
      int total = db.queryForObject("SELECT COUNT(*) FROM lessons WHERE course_id=?", Integer.class, quiz.get("course_id"));
      int done  = db.queryForObject("SELECT COUNT(*) FROM lesson_progress WHERE user_id=? AND course_id=? AND completed=TRUE", Integer.class, userId(a), quiz.get("course_id"));
      if (total == 0 || done >= total) {
        db.update("UPDATE course_assignments SET status='completed',completed_at=CURRENT_TIMESTAMP WHERE course_id=? AND user_id=?", quiz.get("course_id"), userId(a));
        code = progress.certificate(quiz.get("course_id"), userId(a));
      }
    }
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("score", correct);
    result.put("total_questions", qs.size());
    result.put("percentage", percent);
    result.put("passing_score", quiz.get("passing_score"));
    result.put("passed", passed);
    result.put("evaluation", evaluation);
    result.put("certificate_issued", code != null);
    result.put("certificate_code", code);
    return ResponseEntity.ok(ok("result", result));
  }

  private String normal(Object v) {
    if (v == null) return "";
    String s = v instanceof Collection<?> c ? String.join(",", c.stream().map(Object::toString).toList()) : v.toString();
    return Arrays.stream(s.split(",")).map(String::trim).map(String::toUpperCase).filter(x -> !x.isBlank()).sorted().reduce((x,y) -> x+","+y).orElse("");
  }

  @PostMapping @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public ResponseEntity<?> save(@RequestBody Map<String,Object> b, Authentication a) throws Exception {
    long courseId;
    try { courseId = Long.parseLong(String.valueOf(b.get("course_id")).trim()); }
    catch (NumberFormatException e) { return ResponseEntity.badRequest().body(Map.of("success",false,"message","A valid course_id is required.")); }
    if (!access.canEdit(userId(a), isAdmin(a), courseId))
      return ResponseEntity.status(403).body(Map.of("success",false,"message","You can only change quizzes on courses you created."));
    String quizTitle = plain(b.get("title"), 150);
    var existing = db.queryForList("SELECT id FROM quizzes WHERE course_id=?", courseId);
    long id = existing.isEmpty()
      ? db.queryForObject("INSERT INTO quizzes(course_id,title,passing_score,time_limit_mins) VALUES(?,?,?,?) RETURNING id",
          Long.class, courseId, quizTitle, b.getOrDefault("passing_score", 70), b.getOrDefault("time_limit_mins", 15))
      : ((Number) existing.getFirst().get("id")).longValue();
    if (!existing.isEmpty())
      db.update("UPDATE quizzes SET title=?,passing_score=?,time_limit_mins=? WHERE id=?",
        quizTitle, b.getOrDefault("passing_score", 70), b.getOrDefault("time_limit_mins", 15), id);
    if (b.get("questions") instanceof List<?> questions) {
      db.update("DELETE FROM quiz_questions WHERE quiz_id=?", id);
      int order = 1;
      for (Object item : questions) {
        if (!(item instanceof Map<?,?> q)) continue;
        Object correct = q.get("correct_option");
        String answer = correct instanceof Collection<?> c
          ? c.stream().map(x -> plain(x, 4)).filter(Objects::nonNull).sorted().reduce((x,y) -> x+","+y).orElse("")
          : plain(correct, 4);
        Object type = q.get("question_type"), explanation = q.get("explanation");
        String questionType = type == null ? "mcq" : plain(type, 10);
        db.update("INSERT INTO quiz_questions(quiz_id,question_type,question_text,options,correct_option,explanation,sequence_order) VALUES(?,?,?,?::jsonb,?,?,?)",
          id, questionType, plain(q.get("question_text"), 2000),
          json.writeValueAsString(safeOptions(q.get("options"))), answer, plain(explanation, 2000), order++);
      }
    }
    return ResponseEntity.ok(Map.of("success", true, "message", "Quiz saved successfully.", "quiz_id", id));
  }

  /** Option labels are rendered with innerHTML, so only the id/text pair survives and neither may carry markup. */
  private Object safeOptions(Object raw) {
    if (!(raw instanceof List<?> list)) return List.of();
    List<Object> clean = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?,?> option) {
        Map<String,Object> entry = new LinkedHashMap<>();
        entry.put("id", plain(option.get("id"), 4));
        entry.put("text", plain(option.get("text"), 500));
        clean.add(entry);
      } else {
        clean.add(plain(item, 500));
      }
    }
    return clean;
  }

  @DeleteMapping("/questions/{id}") @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public ResponseEntity<?> delete(@PathVariable long id, Authentication a) {
    List<Map<String,Object>> owners = db.queryForList(
      "SELECT q.course_id FROM quiz_questions qq JOIN quizzes q ON q.id=qq.quiz_id WHERE qq.id=?", id);
    if (owners.isEmpty()) return ResponseEntity.status(404).body(Map.of("success", false, "message", "Question not found."));
    if (!access.canEdit(userId(a), isAdmin(a), ((Number) owners.getFirst().get("course_id")).longValue()))
      return ResponseEntity.status(403).body(Map.of("success",false,"message","You can only change quizzes on courses you created."));
    return db.update("DELETE FROM quiz_questions WHERE id=?", id) == 0
      ? ResponseEntity.status(404).body(Map.of("success", false, "message", "Question not found."))
      : ResponseEntity.ok(message("Question deleted successfully."));
  }
}
