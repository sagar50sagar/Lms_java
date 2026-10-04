import { API } from './api.js';
import { requireAuth } from './auth.js';
import { UI } from './ui.js';

let quizData = null;

async function initQuiz() {
  const user = requireAuth();
  if (!user) return;

  const urlParams = new URLSearchParams(window.location.search);
  const courseId = urlParams.get('courseId');

  if (!courseId) {
    await UI.alert({ title: 'No Course Selected', message: 'No course ID provided. Redirecting to your dashboard.', type: 'warning' });
    window.location.href = '/dashboard.html';
    return;
  }

  document.getElementById('back-to-course-link').href = `/course-player.html?id=${courseId}`;

  try {
    const res = await API.get(`/quizzes/course/${courseId}`);
    quizData = res.quiz;

    document.getElementById('quiz-title').textContent = quizData.title;
    document.getElementById('quiz-passing-info').textContent = `Passing Threshold: ${quizData.passing_score}% • Time Allocated: ${quizData.time_limit_mins || 15} mins`;

    renderQuestions(quizData.questions);
    document.getElementById('quiz-submit-box').style.display = 'block';

    // If user has previous submissions, show their latest attempt status
    if (quizData.user_submissions && quizData.user_submissions.length > 0) {
      const latest = quizData.user_submissions[0];
      const resCard = document.getElementById('quiz-results-card');
      const resContent = document.getElementById('quiz-results-content');
      resCard.style.display = 'block';

      resContent.innerHTML = `
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <div>
            <h3 style="color: ${latest.passed ? 'var(--success)' : 'var(--danger)'};">
              ${latest.passed ? '✓ Assessment Passed on Previous Attempt' : '⚠️ Previous Attempt: Not Passed'}
            </h3>
            <p style="font-size: 0.9rem; color: var(--text-muted); margin-top: 4px;">
              Score: <strong>${latest.score} / ${latest.total_questions}</strong> (${latest.percentage}%) • Required: ${quizData.passing_score}%
            </p>
          </div>
          <div>
            ${latest.passed ? `<a href="/dashboard.html" class="btn btn-secondary btn-sm">View Certificate on Dashboard</a>` : '<span class="badge badge-mandatory">Retake Available</span>'}
          </div>
        </div>
      `;
    }
  } catch (err) {
    document.getElementById('questions-container').innerHTML = `
      <div class="card alert alert-danger">
        Failed to load assessment: ${err.message}
      </div>
    `;
  }
}

function renderQuestions(questions) {
  const container = document.getElementById('questions-container');
  if (!questions || questions.length === 0) {
    container.innerHTML = '<p style="color: var(--text-muted);">No questions found for this quiz.</p>';
    return;
  }

  container.innerHTML = questions.map((q, idx) => {
    // options may arrive as a parsed array or (edge case) as a raw JSON string from JSONB
    let options = [];
    if (Array.isArray(q.options)) {
      options = q.options;
    } else if (typeof q.options === 'string' && q.options.trim().startsWith('[')) {
      try { options = JSON.parse(q.options); } catch { options = []; }
    }
    const isMsq = q.question_type === 'msq';
    const inputType = isMsq ? 'checkbox' : 'radio';
    const hintText = isMsq ? '<span class="badge" style="background:#fef3c7; color:#92400e; margin-left:8px; font-size:0.75rem;">MSQ: Select ALL correct options</span>' : '';

    return `
      <div class="quiz-card question-block" data-question-id="${q.id}" data-qtype="${q.question_type || 'mcq'}">
        <div style="font-size: 0.8rem; font-weight: 700; color: var(--text-muted); text-transform: uppercase; margin-bottom: 8px;">
          Question ${idx + 1} of ${questions.length} ${hintText}
        </div>
        <div class="question-text">${q.question_text}</div>
        <div class="options-group">
          ${options.map(opt => `
            <label class="option-label" data-qid="${q.id}">
              <input type="${inputType}" name="question_${q.id}" value="${opt.id}" style="accent-color: var(--primary);">
              <span><strong>${opt.id}.</strong> ${opt.text}</span>
            </label>
          `).join('')}
        </div>
      </div>
    `;
  }).join('');

  // Style option labels on selection (for both radio & checkbox)
  container.querySelectorAll('.options-group input').forEach(input => {
    input.addEventListener('change', () => {
      const qBlock = input.closest('.question-block');
      const isMsq = qBlock.dataset.qtype === 'msq';
      const qid = qBlock.dataset.questionId;

      if (!isMsq) {
        container.querySelectorAll(`.option-label[data-qid="${qid}"]`).forEach(lbl => {
          lbl.classList.remove('selected');
        });
      }
      if (input.checked) {
        input.closest('.option-label').classList.add('selected');
      } else {
        input.closest('.option-label').classList.remove('selected');
      }
    });
  });
}

// Handle submission
document.getElementById('quiz-form').addEventListener('submit', async (e) => {
  e.preventDefault();

  if (!quizData) return;

  const answers = {};

  // Collect answers for MCQ and MSQ
  quizData.questions.forEach(q => {
    const isMsq = q.question_type === 'msq';
    const inputs = Array.from(document.querySelectorAll(`input[name="question_${q.id}"]:checked`));

    if (isMsq) {
      answers[q.id] = inputs.map(i => i.value).sort().join(',');
    } else {
      answers[q.id] = inputs.length > 0 ? inputs[0].value : null;
    }
  });

  const blank = quizData.questions.filter(q => !answers[q.id] || String(answers[q.id]).trim() === '').length;
  if (!(await UI.confirm({
    title: 'Submit Test?',
    message: `Submit your ${quizData.questions.length} answer${quizData.questions.length === 1 ? '' : 's'} for review?`,
    items: [
      blank ? { label: `${blank} question${blank === 1 ? '' : 's'} left blank`, text: 'will be marked wrong.' } : null,
      'Your answers are saved to your record and cannot be edited afterwards.',
      'Passing with every lesson finished completes the course and issues your certificate.',
    ],
    type: 'question', confirmText: 'Submit Answers'
  }))) return;

  try {
    const submitBtn = e.target.querySelector('button[type="submit"]');
    submitBtn.disabled = true;
    submitBtn.textContent = 'Evaluating Answers...';

    const res = await API.post(`/quizzes/${quizData.id}/submit`, { answers });
    const result = res.result;

    // Display Results Card
    const resCard = document.getElementById('quiz-results-card');
    const resContent = document.getElementById('quiz-results-content');
    resCard.style.display = 'block';

    const passedHtml = result.passed
      ? `<div style="color: var(--success); font-weight: 800; font-size: 1.5rem; margin-bottom: 8px;">🎉 Congratulations! You Passed!</div>`
      : `<div style="color: var(--danger); font-weight: 800; font-size: 1.5rem; margin-bottom: 8px;">⚠️ Assessment Not Passed</div>`;

    let certLinkHtml = '';
    if (result.certificate_issued && result.certificate_code) {
      certLinkHtml = `
        <div style="margin-top: 16px; padding: 16px; background: var(--success-bg); border: 1px solid #86efac; border-radius: var(--radius);">
          <div style="font-weight: 700; color: #166534; font-size: 1.05rem;">📜 Official Certificate Awarded!</div>
          <p style="color: #15803d; font-size: 0.9rem; margin: 4px 0 12px 0;">Certificate Code: <strong>${result.certificate_code}</strong></p>
          <div style="display:flex;gap:8px;flex-wrap:wrap;">
          <a href="/certificate-view.html?code=${result.certificate_code}" target="_blank" class="btn btn-primary btn-sm">
            View Certificate 📜
          </a>
          <button type="button" class="btn btn-secondary btn-sm cert-pdf-btn" data-code="${result.certificate_code}">
            Download PDF ⬇️
          </button>
          </div>
        </div>
      `;
    }

    resContent.innerHTML = `
      ${passedHtml}
      <p style="font-size: 1.05rem; margin-bottom: 12px;">
        You answered <strong>${result.score}</strong> out of <strong>${result.total_questions}</strong> questions correctly 
        (Score: <strong>${result.percentage}%</strong>, Passing Threshold: <strong>${result.passing_score}%</strong>).
      </p>
      ${certLinkHtml}
      <div style="margin-top: 16px;">
        <a href="/dashboard.html" class="btn btn-secondary btn-sm">Return to Training Dashboard</a>
      </div>
    `;

    // Highlight question explanations
    if (result.evaluation) {
      result.evaluation.forEach(evalItem => {
        const block = document.querySelector(`.question-block[data-question-id="${evalItem.question_id}"]`);
        if (block) {
          const evalBox = document.createElement('div');
          evalBox.style.marginTop = '14px';
          evalBox.style.padding = '12px 16px';
          evalBox.style.borderRadius = 'var(--radius)';
          evalBox.style.fontSize = '0.88rem';

          if (evalItem.is_correct) {
            evalBox.style.background = 'var(--success-bg)';
            evalBox.style.border = '1px solid #bbf7d0';
            evalBox.style.color = '#15803d';
            evalBox.innerHTML = `<strong>✓ Correct:</strong> Option ${evalItem.correct_answer}. ${evalItem.explanation || ''}`;
          } else {
            evalBox.style.background = 'var(--danger-bg)';
            evalBox.style.border = '1px solid #fecaca';
            evalBox.style.color = '#991b1b';
            evalBox.innerHTML = `<strong>✗ Incorrect:</strong> You selected ${evalItem.submitted_answer || 'None'}. Correct answer: <strong>${evalItem.correct_answer}</strong>. ${evalItem.explanation || ''}`;
          }

          block.appendChild(evalBox);
        }
      });
    }

    // Scroll to results
    resCard.scrollIntoView({ behavior: 'smooth' });
    submitBtn.style.display = 'none';

  } catch (err) {
    await UI.alert({ title: 'Submission Failed', message: err.message, type: 'danger' });
    const submitBtn = e.target.querySelector('button[type="submit"]');
    submitBtn.disabled = false;
    submitBtn.textContent = 'Submit Assessment Answers';
  }
});

document.addEventListener('DOMContentLoaded', initQuiz);
