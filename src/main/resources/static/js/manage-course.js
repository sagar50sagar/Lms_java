import { API } from './api.js';
import { requireRole } from './auth.js';
import { UI } from './ui.js';

let activeCourse = null;
let currentQuiz = null;

async function initCourseBuilder() {
  const user = requireRole('trainer', 'admin');
  if (!user) return;

  const urlParams = new URLSearchParams(window.location.search);
  const courseId = urlParams.get('id');

  if (courseId) {
    await loadExistingCourse(courseId);
  }

  setupEventListeners();
}

async function loadExistingCourse(courseId) {
  try {
    const res = await API.get(`/courses/${courseId}`);
    activeCourse = res.course;

    document.getElementById('course-title').value = activeCourse.title;
    document.getElementById('course-description').value = activeCourse.description || '';
    document.getElementById('course-category').value = activeCourse.category || 'General';
    document.getElementById('course-duration').value = activeCourse.estimated_duration_hours || 1.0;
    document.getElementById('course-mandatory').checked = Boolean(activeCourse.is_mandatory);

    // Switch UI to "edit" mode
    const heading = document.getElementById('page-heading');
    if (heading) heading.textContent = `Edit Course: ${activeCourse.title}`;
    const submitBtn = document.getElementById('course-submit-btn');
    if (submitBtn) submitBtn.textContent = '💾 Update Course Details';

    activateCoursePanels();
  } catch (err) {
    API.showToast(`Failed to load course: ${err.message}`, 'error');
  }
}

function activateCoursePanels() {
  if (!activeCourse) return;

  document.getElementById('active-course-badge').textContent = `Editing: ${activeCourse.title}`;
  document.getElementById('active-course-badge').style.background = 'var(--primary-light)';
  document.getElementById('active-course-badge').style.color = 'var(--primary)';
  document.getElementById('curriculum-empty-hint').style.display = 'none';

  document.getElementById('chapter-form-box').style.display = 'block';
  document.getElementById('lesson-form-box').style.display = 'block';

  // Populate chapter select dropdown
  const chSelect = document.getElementById('lesson-chapter-select');
  const chapters = activeCourse.chapters || [];
  if (chapters.length === 0) {
    chSelect.innerHTML = '<option value="" disabled selected>No Chapters available</option>';
  } else {
    chSelect.innerHTML = chapters.map(ch => `
      <option value="${ch.id}">${ch.title}</option>
    `).join('');
  }

  renderCurriculumTree();

  // Activate Final Quiz & Assign sections (direct test creation enabled)
  document.getElementById('final-quiz-editor-card').style.display = 'block';
  document.getElementById('assign-section').style.display = 'block';

  loadQuizSection();
  loadAssignSection();
}

async function loadQuizSection() {
  if (!activeCourse) return;

  const titleInput = document.getElementById('quiz-title-input');
  const passInput = document.getElementById('quiz-passing-score');
  const timeInput = document.getElementById('quiz-time-limit');
  const statusBadge = document.getElementById('quiz-status-badge');

  // Default title
  if (!titleInput.value) {
    titleInput.value = `${activeCourse.title} - Final Assessment`;
  }

  try {
    const res = await API.get(`/quizzes/course/${activeCourse.id}`);
    currentQuiz = res.quiz;

    if (currentQuiz) {
      titleInput.value = currentQuiz.title;
      passInput.value = currentQuiz.passing_score || 70;
      timeInput.value = currentQuiz.time_limit_mins || 15;

      const qCount = (currentQuiz.questions || []).length;
      statusBadge.textContent = `${qCount} Question${qCount === 1 ? '' : 's'}`;
      document.getElementById('qq-seq').value = qCount + 1;

      renderQuestionsList(currentQuiz.questions || []);
    } else {
      currentQuiz = null;
      statusBadge.textContent = '0 Questions';
      renderQuestionsList([]);
    }
  } catch (err) {
    // Quiz not found yet
    currentQuiz = null;
    statusBadge.textContent = '0 Questions';
    renderQuestionsList([]);
  }
}

function renderQuestionsList(questions) {
  const container = document.getElementById('quiz-questions-list');
  if (!container) return;

  if (questions.length === 0) {
    container.innerHTML = '<p style="color: var(--text-muted); font-size: 0.9rem;">No test questions added yet. Use the form above to add MCQ or MSQ questions.</p>';
    return;
  }

  container.innerHTML = questions.map((q, idx) => {
    const isMsq = q.question_type === 'msq';
    const typeBadge = isMsq
      ? `<span class="badge" style="background:#fef3c7; color:#92400e; font-size:0.75rem;">MSQ (Multiple Select)</span>`
      : `<span class="badge" style="background:#e0f2fe; color:#0369a1; font-size:0.75rem;">MCQ (Single Choice)</span>`;

    const options = Array.isArray(q.options) ? q.options : [];
    const correctOptions = (q.correct_option || '').split(',').map(s => s.trim().toUpperCase());

    return `
      <div style="background: var(--bg-page); border: 1px solid var(--border); border-radius: var(--radius); padding: 14px; margin-bottom: 12px;">
        <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8px;">
          <div style="display: flex; align-items: center; gap: 8px;">
            <span style="font-weight: 700; font-size: 0.95rem;">Q${idx + 1}.</span>
            ${typeBadge}
          </div>
          <button class="btn btn-secondary btn-sm delete-q-btn" data-qid="${q.id}" style="color: var(--danger); padding: 2px 8px; font-size: 0.78rem;">
            🗑️ Delete Question
          </button>
        </div>

        <div style="font-weight: 600; font-size: 0.95rem; margin-bottom: 8px; color: var(--secondary);">
          ${q.question_text}
        </div>

        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 6px; font-size: 0.85rem; margin-bottom: 8px;">
          ${options.map(opt => {
            const isCorrect = correctOptions.includes(opt.id.toUpperCase());
            return `
              <div style="padding: 6px 10px; border-radius: 6px; background: ${isCorrect ? '#dcfce7' : 'var(--bg-card)'}; border: 1px solid ${isCorrect ? '#86efac' : 'var(--border)'}; font-weight: ${isCorrect ? '700' : 'normal'}; color: ${isCorrect ? '#166534' : 'inherit'};">
                ${isCorrect ? '✓ ' : ''}<strong>${opt.id}.</strong> ${opt.text}
              </div>
            `;
          }).join('')}
        </div>

        ${q.explanation ? `<div style="font-size: 0.8rem; color: var(--text-muted); font-style: italic;">Rationale: ${q.explanation}</div>` : ''}
      </div>
    `;
  }).join('');

  // Attach delete handlers
  container.querySelectorAll('.delete-q-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const qid = btn.dataset.qid;
      if (!(await UI.confirm({ title: 'Delete Question?', message: 'Are you sure you want to delete this test question?', type: 'danger', confirmText: 'Delete' }))) return;
      try {
        await API.delete(`/quizzes/questions/${qid}`);
        API.showToast('Question deleted!', 'success');
        loadQuizSection();
      } catch (err) {
        API.showToast(err.message, 'error');
      }
    });
  });
}

async function loadAssignSection() {
  const section = document.getElementById('assign-section');
  if (!section || !activeCourse) return;

  try {
    const res = await API.get('/departments/my-departments');
    const depts = res.departments || [];
    const deptSelect = document.getElementById('inline-dept-select');
    if (depts.length === 0) {
      deptSelect.innerHTML = '<option value="" disabled selected>No Departments available</option>';
      document.getElementById('inline-emp-select').innerHTML = '<option value="" disabled selected>No Employees available</option>';
    } else {
      deptSelect.innerHTML = depts.map(d => `<option value="${d.id}">${d.name} (${d.employee_count} employees)</option>`).join('');
      await loadDeptEmployees(depts[0].id);
    }

    deptSelect.addEventListener('change', () => loadDeptEmployees(deptSelect.value));
  } catch (err) {
    console.error('Could not load departments:', err);
  }

  // Target toggle
  document.querySelectorAll('input[name="inline-target"]').forEach(r => {
    r.addEventListener('change', (e) => {
      document.getElementById('inline-dept-group').style.display = e.target.value === 'department' ? 'block' : 'none';
      document.getElementById('inline-emp-group').style.display = e.target.value === 'employee' ? 'block' : 'none';
    });
  });

  // Submit handler
  document.getElementById('inline-assign-form').onclick = null;
}

async function loadDeptEmployees(deptId) {
  const empSelect = document.getElementById('inline-emp-select');
  try {
    const res = await API.get(`/departments/${deptId}/employees`);
    const emps = (res.employees || []).filter(e => e.role === 'employee');
    if (emps.length === 0) {
      empSelect.innerHTML = '<option value="" disabled selected>No Employees available</option>';
    } else {
      empSelect.innerHTML = emps.map(e => `<option value="${e.id}">${e.full_name} (${e.email})</option>`).join('');
    }
  } catch (err) {
    console.error(err);
    empSelect.innerHTML = '<option value="" disabled selected>No Employees available</option>';
  }
}

function renderCurriculumTree() {
  const container = document.getElementById('curriculum-tree-view');
  if (!activeCourse || !container) return;

  const chapters = activeCourse.chapters || [];
  if (chapters.length === 0) {
    container.innerHTML = '<p style="color: var(--text-muted); font-size: 0.85rem;">No chapters created yet. (You can create a final test below without any chapters!)</p>';
    return;
  }

  container.innerHTML = `
    <h3 style="font-size: 1.05rem; margin-bottom: 12px; color: var(--secondary);">Curriculum Overview</h3>
    <div style="display: flex; flex-direction: column; gap: 8px;">
      ${chapters.map(ch => `
        <div style="background: var(--bg-page); border: 1px solid var(--border); border-radius: var(--radius); padding: 10px 14px;">
          <div style="font-weight: 700; font-size: 0.9rem;">${ch.title}</div>
          <ul style="margin-left: 20px; font-size: 0.85rem; color: var(--text-muted); margin-top: 4px;">
            ${(ch.lessons || []).map(l => `
              <li>${l.content_type === 'video' ? '🎥' : '📄'} ${l.title} (${l.duration_mins} mins)</li>
            `).join('')}
          </ul>
        </div>
      `).join('')}
    </div>
  `;
}

function setupEventListeners() {
  // Toggle video input visibility
  document.getElementById('lesson-type-select').addEventListener('change', (e) => {
    const videoGroup = document.getElementById('lesson-video-group');
    videoGroup.style.display = e.target.value === 'video' ? 'block' : 'none';
  });

  // Toggle Question Type (MCQ vs MSQ selector)
  const qqTypeSelect = document.getElementById('qq-type');
  if (qqTypeSelect) {
    qqTypeSelect.addEventListener('change', (e) => {
      const isMsq = e.target.value === 'msq';
      document.getElementById('mcq-correct-container').style.display = isMsq ? 'none' : 'block';
      document.getElementById('msq-correct-container').style.display = isMsq ? 'block' : 'none';
    });
  }

  // Save/Update Course
  document.getElementById('create-course-form').addEventListener('submit', async (e) => {
    e.preventDefault();

    const title = document.getElementById('course-title').value.trim();
    const description = document.getElementById('course-description').value.trim();
    const category = document.getElementById('course-category').value;
    const estimated_duration_hours = parseFloat(document.getElementById('course-duration').value) || 1.0;
    const is_mandatory = document.getElementById('course-mandatory').checked;

    try {
      if (activeCourse) {
        const res = await API.put(`/courses/${activeCourse.id}`, {
          title, description, category, estimated_duration_hours, is_mandatory
        });
        activeCourse = { ...activeCourse, ...res.course };
        const heading = document.getElementById('page-heading');
        if (heading) heading.textContent = `Edit Course: ${activeCourse.title}`;
        API.showToast('Course updated successfully!', 'success');
      } else {
        const res = await API.post('/courses', {
          title, description, category, estimated_duration_hours, is_mandatory
        });
        activeCourse = res.course;
        activeCourse.chapters = [];
        const heading = document.getElementById('page-heading');
        if (heading) heading.textContent = `Edit Course: ${activeCourse.title}`;
        const submitBtn = document.getElementById('course-submit-btn');
        if (submitBtn) submitBtn.textContent = '💾 Update Course Details';
        API.showToast('Course created! You can now add tests and lessons.', 'success');
        activateCoursePanels();
      }
    } catch (err) {
      API.showToast(err.message, 'error');
    }
  });

  // Add Chapter
  document.getElementById('add-chapter-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!activeCourse) return;

    const title = document.getElementById('chapter-title-input').value.trim();
    try {
      const res = await API.post(`/courses/${activeCourse.id}/chapters`, { title });
      if (!activeCourse.chapters) activeCourse.chapters = [];
      activeCourse.chapters.push({ ...res.chapter, lessons: [] });

      document.getElementById('chapter-title-input').value = '';
      API.showToast('Module added successfully!', 'success');
      activateCoursePanels();
    } catch (err) {
      API.showToast(err.message, 'error');
    }
  });

  // Add Lesson
  document.getElementById('add-lesson-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!activeCourse) return;

    const chapterId = document.getElementById('lesson-chapter-select').value;
    const title = document.getElementById('lesson-title-input').value.trim();
    const content_type = document.getElementById('lesson-type-select').value;
    const duration_mins = parseInt(document.getElementById('lesson-duration-input').value, 10) || 10;
    const video_url = document.getElementById('lesson-video-input').value.trim();
    const content = document.getElementById('lesson-content-input').value.trim();

    try {
      const res = await API.post(`/courses/${activeCourse.id}/chapters/${chapterId}/lessons`, {
        title, content_type, duration_mins, video_url, content
      });

      const targetCh = activeCourse.chapters.find(c => c.id === parseInt(chapterId, 10));
      if (targetCh) {
        if (!targetCh.lessons) targetCh.lessons = [];
        targetCh.lessons.push(res.lesson);
      }

      document.getElementById('lesson-title-input').value = '';
      document.getElementById('lesson-content-input').value = '';
      API.showToast('Lesson added successfully!', 'success');
      renderCurriculumTree();
    } catch (err) {
      API.showToast(err.message, 'error');
    }
  });

  // Add Question to Final Test Form
  document.getElementById('add-quiz-question-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!activeCourse) return;

    const qType = document.getElementById('qq-type').value; // 'mcq' or 'msq'
    const question_text = document.getElementById('qq-text').value.trim();
    const optA = document.getElementById('qq-opt-a').value.trim();
    const optB = document.getElementById('qq-opt-b').value.trim();
    const optC = document.getElementById('qq-opt-c').value.trim();
    const optD = document.getElementById('qq-opt-d').value.trim();
    const explanation = document.getElementById('qq-explanation').value.trim();

    let correct_option = '';
    if (qType === 'mcq') {
      correct_option = document.getElementById('qq-correct-mcq').value;
    } else {
      // Collect MSQ checked checkboxes
      const checkedBoxes = Array.from(document.querySelectorAll('.msq-chk:checked')).map(cb => cb.value);
      if (checkedBoxes.length === 0) {
        API.showToast('Please select at least 1 correct option for MSQ question.', 'error');
        return;
      }
      correct_option = checkedBoxes.sort().join(',');
    }

    const options = [
      { id: 'A', text: optA },
      { id: 'B', text: optB },
    ];
    if (optC) options.push({ id: 'C', text: optC });
    if (optD) options.push({ id: 'D', text: optD });

    const newQuestion = {
      question_type: qType,
      question_text,
      options,
      correct_option,
      explanation,
    };

    // Get current questions array or initialize
    const existingQuestions = currentQuiz && Array.isArray(currentQuiz.questions)
      ? currentQuiz.questions.map(q => {
          // Normalize options: always send as a plain array
          // (guards against PGobject serialization artifact if options came back as a string)
          let opts = q.options;
          if (typeof opts === 'string') {
            try { opts = JSON.parse(opts); } catch { opts = []; }
          }
          if (!Array.isArray(opts)) opts = [];
          return {
            question_type: q.question_type || 'mcq',
            question_text: q.question_text,
            options: opts,
            correct_option: q.correct_option,
            explanation: q.explanation,
          };
        })
      : [];

    const updatedQuestions = [...existingQuestions, newQuestion];

    const quizTitle = document.getElementById('quiz-title-input').value.trim() || `${activeCourse.title} - Final Assessment`;
    const passingScore = parseInt(document.getElementById('quiz-passing-score').value, 10) || 70;
    const timeLimit = parseInt(document.getElementById('quiz-time-limit').value, 10) || 15;

    try {
      await API.post('/quizzes', {
        course_id: activeCourse.id,
        title: quizTitle,
        passing_score: passingScore,
        time_limit_mins: timeLimit,
        questions: updatedQuestions,
      });

      // Clear form inputs
      document.getElementById('qq-text').value = '';
      document.getElementById('qq-opt-a').value = '';
      document.getElementById('qq-opt-b').value = '';
      document.getElementById('qq-opt-c').value = '';
      document.getElementById('qq-opt-d').value = '';
      document.getElementById('qq-explanation').value = '';
      document.querySelectorAll('.msq-chk').forEach(cb => cb.checked = false);

      API.showToast('Question saved to final assessment!', 'success');
      loadQuizSection();
    } catch (err) {
      API.showToast(err.message, 'error');
    }
  });

  // Assign form
  document.getElementById('inline-assign-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!activeCourse) return;

    const target = document.querySelector('input[name="inline-target"]:checked').value;
    const payload = {
      course_id: activeCourse.id,
      due_date: document.getElementById('inline-due-date').value || null,
    };
    if (target === 'department') {
      payload.department_id = document.getElementById('inline-dept-select').value;
    } else {
      payload.user_id = document.getElementById('inline-emp-select').value;
    }
    const sel = (target === 'department' ? document.getElementById('inline-dept-select') : document.getElementById('inline-emp-select')).selectedOptions[0];
    const targetLabel = sel ? sel.textContent.trim() : 'the selected target';
    const items = [target === 'department'
      ? 'Everyone in that department gets it on their dashboard.'
      : 'This employee gets it on their dashboard.'];
    if (activeCourse.is_published === false) items.push('This course is a Draft and will be published so the assignees can open it.');
    if (payload.due_date) items.push(`Due by ${payload.due_date}.`);
    if (!(await UI.confirm({
      title: 'Assign Course?',
      message: `Assign “${activeCourse.title}” to ${targetLabel}?`,
      items, type: 'info', confirmText: 'Assign Course'
    }))) return;
    try {
      const res = await API.post('/assignments', payload);
      API.showToast(res.message, 'success');
    } catch (err) {
      API.showToast(err.message, 'error');
    }
  });
}

document.addEventListener('DOMContentLoaded', initCourseBuilder);
