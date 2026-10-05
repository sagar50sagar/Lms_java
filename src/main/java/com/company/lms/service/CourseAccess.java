package com.company.lms.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Course editing is scoped to the trainer who created it. Roles alone were not enough: any trainer
 * could write to any course id, so chapters, lessons and quizzes leaked across accounts.
 */
@Service
public class CourseAccess {

  private final JdbcTemplate db;

  public CourseAccess(JdbcTemplate db) { this.db = db; }

  public boolean canEdit(long userId, boolean admin, long courseId) {
    if (admin) return true;
    return db.queryForObject("SELECT COUNT(*) FROM courses WHERE id=? AND created_by=?", Integer.class, courseId, userId) > 0;
  }

  /** A lesson insert must not weld another course's chapter onto the caller's course. */
  public boolean chapterBelongsToCourse(long chapterId, long courseId) {
    return db.queryForObject("SELECT COUNT(*) FROM chapters WHERE id=? AND course_id=?", Integer.class, chapterId, courseId) > 0;
  }
}
