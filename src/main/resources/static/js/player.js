import { API } from './api.js';
import { requireAuth } from './auth.js';

let currentCourse = null;
let allLessons = [];
let currentLessonIndex = 0;
let userProgress = { completed_lesson_ids: [] };

async function initPlayer() {
  const user = requireAuth();
  if (!user) return;

  const urlParams = new URLSearchParams(window.location.search);
  const courseId = urlParams.get('id');

  if (!courseId) {
    alert('No course ID specified.');
    window.location.href = '/dashboard.html';
    return;
  }

  try {
    const res = await API.get(`/courses/${courseId}`);
    currentCourse = res.course;
    userProgress = currentCourse.user_progress || { completed_lesson_ids: [] };

    // Set course title
    document.getElementById('player-course-title').textContent = currentCourse.title;

    // Flatten lessons list for linear navigation
    allLessons = [];
    currentCourse.chapters.forEach(ch => {
      ch.lessons.forEach(l => {
        allLessons.push({
          ...l,
          chapterTitle: ch.title,
        });
      });
    });

    document.getElementById('sidebar-lesson-count').textContent = `${allLessons.length} lessons`;

    // Determine initial lesson to show: first uncompleted lesson or lesson 0
    const firstUnfinishedIdx = allLessons.findIndex(l => !userProgress.completed_lesson_ids.includes(l.id));
    currentLessonIndex = firstUnfinishedIdx !== -1 ? firstUnfinishedIdx : 0;

    renderCurriculumSidebar();
    renderCurrentLesson();
    updateProgressUI();
    attachEventListeners();
  } catch (err) {
    console.error('Failed to load course player:', err);
    alert(`Failed to load training: ${err.message}`);
    window.location.href = '/dashboard.html';
  }
}

function renderCurriculumSidebar() {
  const container = document.getElementById('sidebar-curriculum');
  if (!container || !currentCourse) return;

  let html = '';
  currentCourse.chapters.forEach(chapter => {
    html += `
      <div class="chapter-group">
        <div class="chapter-title">${chapter.title}</div>
        <ul class="lesson-list">
          ${chapter.lessons.map(lesson => {
            const isCompleted = userProgress.completed_lesson_ids.includes(lesson.id);
            const isCurrent = allLessons[currentLessonIndex] && allLessons[currentLessonIndex].id === lesson.id;

            return `
              <li class="lesson-item ${isCurrent ? 'active' : ''}" data-lesson-id="${lesson.id}">
                <div class="lesson-status-icon ${isCompleted ? 'completed' : ''}">
                  ${isCompleted ? '✓' : ''}
                </div>
                <div style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                  <span>${lesson.content_type === 'video' ? '🎥' : '📄'}</span>
                  <span>${lesson.title}</span>
                </div>
              </li>
            `;
          }).join('')}
        </ul>
      </div>
    `;
  });

  // If course has a quiz, show it at bottom of syllabus
  if (currentCourse.quizzes && currentCourse.quizzes.length > 0) {
    const q = currentCourse.quizzes[0];
    const isPassed = userProgress.quiz_submissions && userProgress.quiz_submissions.some(s => s.passed);

    html += `
      <div class="chapter-group" style="margin-top: 20px; border-top: 1px dashed var(--border); padding-top: 14px;">
        <div class="chapter-title">Assessment</div>
        <a href="/quiz-view.html?courseId=${currentCourse.id}" class="lesson-item" style="text-decoration: none; font-weight: 600; color: ${isPassed ? 'var(--success)' : 'var(--primary)'};">
          <div class="lesson-status-icon ${isPassed ? 'completed' : ''}">
            ${isPassed ? '✓' : '📝'}
          </div>
          <div style="flex: 1;">
            <div>${q.title}</div>
            <div style="font-size: 0.75rem; color: var(--text-muted);">${isPassed ? 'Exam Passed' : `Passing: ${q.passing_score}%`}</div>
          </div>
        </a>
      </div>
    `;
  }

  container.innerHTML = html;

  // Add click listeners to sidebar lessons
  container.querySelectorAll('.lesson-item[data-lesson-id]').forEach(item => {
    item.addEventListener('click', () => {
      const lessonId = parseInt(item.dataset.lessonId, 10);
      const idx = allLessons.findIndex(l => l.id === lessonId);
      if (idx !== -1) {
        currentLessonIndex = idx;
        renderCurriculumSidebar();
        renderCurrentLesson();
      }
    });
  });
}

function renderCurrentLesson() {
  if (allLessons.length === 0) return;

  const lesson = allLessons[currentLessonIndex];
  if (!lesson) return;

  document.getElementById('lesson-chapter-badge').textContent = lesson.chapterTitle;
  document.getElementById('lesson-duration-badge').textContent = `⏱ ${lesson.duration_mins} mins`;
  document.getElementById('lesson-main-title').textContent = lesson.title;

  const videoBox = document.getElementById('lesson-video-box');
  const videoIframe = document.getElementById('lesson-video-iframe');
  const bodyContent = document.getElementById('lesson-main-body');

  if (lesson.content_type === 'video' && lesson.video_url) {
    videoBox.style.display = 'block';
    videoIframe.src = lesson.video_url;
  } else {
    videoBox.style.display = 'none';
    videoIframe.src = '';
  }

  bodyContent.innerHTML = lesson.content || '<p style="color: var(--text-muted);">No written text content for this lesson.</p>';

  // Update Toggle Complete Button
  const toggleBtn = document.getElementById('btn-toggle-complete');
  const isCompleted = userProgress.completed_lesson_ids.includes(lesson.id);
  if (isCompleted) {
    toggleBtn.textContent = '✓ Lesson Completed';
    toggleBtn.className = 'btn btn-secondary btn-sm';
    toggleBtn.style.color = 'var(--success)';
  } else {
    toggleBtn.textContent = 'Mark Lesson Complete';
    toggleBtn.className = 'btn btn-primary btn-sm';
    toggleBtn.style.color = '#ffffff';
  }

  // Update Next/Prev buttons
  document.getElementById('btn-prev-lesson').disabled = currentLessonIndex === 0;
  const nextBtn = document.getElementById('btn-next-lesson');
  if (currentLessonIndex === allLessons.length - 1) {
    nextBtn.textContent = 'Final Lesson reached';
    nextBtn.className = 'btn btn-secondary btn-sm';
    nextBtn.disabled = true;
  } else {
    nextBtn.textContent = 'Next Lesson →';
    nextBtn.className = 'btn btn-primary btn-sm';
    nextBtn.disabled = false;
  }
}

function updateProgressUI() {
  const total = allLessons.length;
  const completed = userProgress.completed_lesson_ids.length;
  const percent = total > 0 ? Math.round((completed / total) * 100) : 0;

  document.getElementById('player-progress-text').textContent = `${percent}%`;
  const fill = document.getElementById('player-progress-bar');
  fill.style.width = `${percent}%`;
  if (percent === 100) fill.classList.add('completed');

  // Certificate badge
  const certBadge = document.getElementById('player-cert-badge');
  if (userProgress.certificate) {
    certBadge.innerHTML = `
      <a href="/certificate-view.html?code=${userProgress.certificate.certificate_code}" target="_blank" class="btn btn-secondary btn-sm" style="color: var(--success); font-weight: 700;">
        📜 Certificate
      </a>
    `;
  }

  // Quiz button visibility
  const quizBtn = document.getElementById('btn-goto-quiz');
  if (quizBtn && currentCourse.quizzes && currentCourse.quizzes.length > 0) {
    if (completed >= total) {
      quizBtn.style.display = 'inline-flex';
      quizBtn.href = `/quiz-view.html?courseId=${currentCourse.id}`;
    } else {
      quizBtn.style.display = 'none';
    }
  }
}

function attachEventListeners() {
  // Toggle complete
  document.getElementById('btn-toggle-complete').addEventListener('click', async () => {
    const lesson = allLessons[currentLessonIndex];
    if (!lesson) return;

    const currentlyDone = userProgress.completed_lesson_ids.includes(lesson.id);
    const newStatus = !currentlyDone;

    try {
      const res = await API.post('/progress/toggle-lesson', {
        course_id: currentCourse.id,
        lesson_id: lesson.id,
        completed: newStatus,
      });

      if (newStatus) {
        if (!userProgress.completed_lesson_ids.includes(lesson.id)) {
          userProgress.completed_lesson_ids.push(lesson.id);
        }
        API.showToast('Lesson marked as completed!', 'success');
      } else {
        userProgress.completed_lesson_ids = userProgress.completed_lesson_ids.filter(id => id !== lesson.id);
      }

      if (res.progress && res.progress.certificate_issued) {
        userProgress.certificate = { certificate_code: res.progress.certificate_code };
        API.showToast('🎉 Congratulations! Course completed and Certificate awarded!', 'success');
      }

      renderCurriculumSidebar();
      renderCurrentLesson();
      updateProgressUI();

      // Auto-advance to next lesson if marking complete
      if (newStatus && currentLessonIndex < allLessons.length - 1) {
        currentLessonIndex++;
        renderCurriculumSidebar();
        renderCurrentLesson();
      }
    } catch (err) {
      API.showToast(err.message, 'error');
    }
  });

  // Previous
  document.getElementById('btn-prev-lesson').addEventListener('click', () => {
    if (currentLessonIndex > 0) {
      currentLessonIndex--;
      renderCurriculumSidebar();
      renderCurrentLesson();
    }
  });

  // Next
  document.getElementById('btn-next-lesson').addEventListener('click', () => {
    if (currentLessonIndex < allLessons.length - 1) {
      currentLessonIndex++;
      renderCurriculumSidebar();
      renderCurrentLesson();
    }
  });
}

document.addEventListener('DOMContentLoaded', initPlayer);
