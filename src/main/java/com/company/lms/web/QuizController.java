package com.company.lms.web;
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

  public QuizController(JdbcTemplate db, ObjectMapper json, ProgressController progress) {
    this.db = db; this.json = json; this.progress = progress;
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
  public Map<String,Object> save(@RequestBody Map<String,Object> b) throws Exception {
    Object course = b.get("course_id");
    var existing = db.queryForList("SELECT id FROM quizzes WHERE course_id=?", course);
    long id = existing.isEmpty()
      ? db.queryForObject("INSERT INTO quizzes(course_id,title,passing_score,time_limit_mins) VALUES(?,?,?,?) RETURNING id",
          Long.class, course, b.get("title"), b.getOrDefault("passing_score", 70), b.getOrDefault("time_limit_mins", 15))
      : ((Number) existing.getFirst().get("id")).longValue();
    if (!existing.isEmpty())
      db.update("UPDATE quizzes SET title=?,passing_score=?,time_limit_mins=? WHERE id=?",
        b.get("title"), b.getOrDefault("passing_score", 70), b.getOrDefault("time_limit_mins", 15), id);
    if (b.get("questions") instanceof List<?> questions) {
      db.update("DELETE FROM quiz_questions WHERE quiz_id=?", id);
      int order = 1;
      for (Object item : questions) {
        if (!(item instanceof Map<?,?> q)) continue;
        Object correct = q.get("correct_option");
        String answer = correct instanceof Collection<?> c
          ? String.join(",", c.stream().map(Object::toString).sorted().toList())
          : String.valueOf(correct);
        Object type = q.get("question_type"), explanation = q.get("explanation");
        db.update("INSERT INTO quiz_questions(quiz_id,question_type,question_text,options,correct_option,explanation,sequence_order) VALUES(?,?,?,?::jsonb,?,?,?)",
          id, type == null ? "mcq" : type, q.get("question_text"),
          json.writeValueAsString(q.get("options")), answer, explanation == null ? "" : explanation, order++);
      }
    }
    return Map.of("success", true, "message", "Quiz saved successfully.", "quiz_id", id);
  }

  @DeleteMapping("/questions/{id}") @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public ResponseEntity<?> delete(@PathVariable long id) {
    return db.update("DELETE FROM quiz_questions WHERE id=?", id) == 0
      ? ResponseEntity.status(404).body(Map.of("success", false, "message", "Question not found."))
      : ResponseEntity.ok(message("Question deleted successfully."));
  }
}
