import { esc, formatDate } from './escape.js';
import { API } from './api.js';
import { requireAuth } from './auth.js';

async function initDashboard() {
  const user = requireAuth();
  if (!user) return;

  if (user.role !== 'employee') {
    const catLink = document.getElementById('catalog-link');
    if (catLink) catLink.style.display = 'inline-flex';
  }

  // Set personalized welcome
  const heading = document.getElementById('welcome-heading');
  const subtext = document.getElementById('welcome-subtext');
  if (heading) heading.textContent = `Welcome, ${user.full_name}`;
  if (subtext) subtext.textContent = `${user.designation || 'Staff'} • ${user.department_name || 'General Department'} • Role: ${user.role.toUpperCase()}`;

  // Quick actions based on role
  const quickActions = document.getElementById('role-quick-actions');
  if (quickActions) {
    if (user.role === 'admin') {
      quickActions.innerHTML = `
        <a href="/admin.html" class="btn btn-primary btn-sm">🛡️ Admin Compliance Hub</a>
        <a href="/manage-course.html" class="btn btn-secondary btn-sm">+ Build Course</a>
      `;
    } else if (user.role === 'trainer') {
      quickActions.innerHTML = `
        <a href="/manage-course.html" class="btn btn-primary btn-sm">+ Build Course</a>
      `;
    }
  }

  // Load employee's assignments and certificates
  await loadLearnerAssignments(user);
  await loadCertificates();

  // If Admin, render company-wide compliance section
  if (user.role === 'admin') {
    await loadAdminSummary();
  }
}

async function loadLearnerAssignments(user) {
  const container = document.getElementById('assigned-courses-grid');
  const metricsGrid = document.getElementById('metrics-grid');
  const overdueBanner = document.getElementById('overdue-banner-container');

  try {
    const res = await API.get('/assignments/my');
    const assignments = res.assignments || [];

    const total = assignments.length;
    const completed = assignments.filter(a => a.compliance_status === 'completed').length;
    const inProgress = assignments.filter(a => a.compliance_status === 'in_progress').length;
    const overdue = assignments.filter(a => a.compliance_status === 'overdue').length;
    const overdueMandatory = assignments.filter(a => a.compliance_status === 'overdue' && a.is_mandatory).length;

    // Overdue banner alert
    if (overdueMandatory > 0 && overdueBanner) {
      overdueBanner.innerHTML = `
        <div class="overdue-alert-banner">
          <div>
            <strong>⚠️ Immediate Action Required:</strong> You have <strong>${overdueMandatory}</strong> overdue mandatory compliance course(s). Please complete them immediately.
          </div>
          <a href="#assigned-courses-grid" class="btn btn-danger btn-sm">View Overdue</a>
        </div>
      `;
    }

    // Render Metrics Strip
    if (metricsGrid && user.role !== 'admin') {
      metricsGrid.innerHTML = `
        <div class="card" style="padding: 16px;">
          <div style="font-size: 0.8rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase;">Total Assigned</div>
          <div style="font-size: 1.8rem; font-weight: 700; color: var(--secondary);">${total}</div>
        </div>
        <div class="card" style="padding: 16px;">
          <div style="font-size: 0.8rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase;">In Progress</div>
          <div style="font-size: 1.8rem; font-weight: 700; color: var(--warning);">${inProgress}</div>
        </div>
        <div class="card" style="padding: 16px;">
          <div style="font-size: 0.8rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase;">Completed</div>
          <div style="font-size: 1.8rem; font-weight: 700; color: var(--success);">${completed}</div>
        </div>
        <div class="card" style="padding: 16px;">
          <div style="font-size: 0.8rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase;">Overdue</div>
          <div style="font-size: 1.8rem; font-weight: 700; color: var(--danger);">${overdue}</div>
        </div>
      `;
    }

    if (assignments.length === 0) {
      container.innerHTML = `
        <div class="card" style="grid-column: 1 / -1; text-align: center; padding: 40px;">
          <h3>No Courses Currently Assigned</h3>
          <p style="color: var(--text-muted);">Nothing is due from you right now. A trainer or administrator will assign your next course here when it is scheduled.</p>
        </div>
      `;
      return;
    }

    container.innerHTML = assignments.map(a => {
      let statusBadge = `<span class="badge badge-status-${a.compliance_status}">${a.compliance_status.replace('_', ' ')}</span>`;
      let dueDateHtml = '';

      if (a.due_date) {
        const formattedDate = formatDate(a.due_date);
        dueDateHtml = `
          <div style="font-size: 0.82rem; margin-top: 6px; color: ${a.is_overdue ? 'var(--danger)' : 'var(--text-muted)'}; font-weight: ${a.is_overdue ? '700' : '500'};">
            📅 Deadline: ${formattedDate} ${a.is_overdue ? '(OVERDUE)' : ''}
          </div>
        `;
      }

      return `
        <div class="card" style="display: flex; flex-direction: column; ${a.is_overdue ? 'border-color: #f87171;' : ''}">
          <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 10px;">
            <span class="badge ${a.is_mandatory ? 'badge-mandatory' : 'badge-optional'}">
              ${a.is_mandatory ? 'Mandatory' : 'Elective'}
            </span>
            ${statusBadge}
          </div>

          <h3 style="font-size: 1.15rem; margin-bottom: 6px;">${esc(a.course_title)}</h3>
          <p style="color: var(--text-muted); font-size: 0.85rem; flex: 1; margin-bottom: 12px;">
            ${a.course_description}
          </p>

          ${dueDateHtml}

          <!-- Progress bar -->
          <div style="margin-top: 14px;">
            <div style="display: flex; justify-content: space-between; font-size: 0.78rem; font-weight: 600; color: var(--text-muted);">
              <span>Progress</span>
              <span>${a.progress_percent}%</span>
            </div>
            <div class="progress-bar-container">
              <div class="progress-bar-fill ${a.compliance_status === 'completed' ? 'completed' : ''}" style="width: ${a.progress_percent}%;"></div>
            </div>
          </div>

          <div style="margin-top: 14px;">
            <a href="/course-player.html?id=${a.course_id}" class="btn ${a.compliance_status === 'completed' ? 'btn-secondary' : 'btn-primary'} btn-block btn-sm">
              ${a.compliance_status === 'completed' ? 'Review Training' : (a.progress_percent > 0 ? 'Resume Training' : 'Start Course')}
            </a>
          </div>
        </div>
      `;
    }).join('');
  } catch (err) {
    container.innerHTML = `<p style="color: var(--danger); grid-column: 1 / -1;">Failed to load assignments: ${esc(err.message)}</p>`;
  }
}

async function loadCertificates() {
  const section = document.getElementById('certificates-section');
  const container = document.getElementById('certificates-grid');

  try {
    const res = await API.get('/progress/certificates/my');
    const certificates = res.certificates || [];

    if (certificates.length > 0) {
      section.style.display = 'block';
      container.innerHTML = certificates.map(c => `
        <div class="card" style="border-left: 4px solid var(--success); padding: 18px;">
          <div style="font-size: 0.75rem; text-transform: uppercase; font-weight: 700; color: var(--success);">
            Verified Certificate
          </div>
          <h4 style="font-size: 1.05rem; margin: 6px 0;">${esc(c.course_title)}</h4>
          <p style="font-size: 0.82rem; color: var(--text-muted);">Code: ${esc(c.certificate_code)}</p>
          <p style="font-size: 0.8rem; color: var(--text-muted); margin-bottom: 12px;">
            Issued: ${new Date(c.issued_at).toLocaleDateString()}
          </p>
          <a href="/certificate-view.html?code=${esc(c.certificate_code)}" target="_blank" class="btn btn-secondary btn-sm btn-block">
            View Certificate 📜
          </a>
          <button type="button" class="btn btn-primary btn-sm btn-block cert-pdf-btn" data-code="${esc(c.certificate_code)}" style="margin-top:8px">
            Download PDF ⬇️
          </button>
        </div>
      `).join('');
    }
  } catch (err) {
    console.error('Failed to load certificates:', err);
  }
}

async function loadAdminSummary() {
  const metricsGrid = document.getElementById('metrics-grid');
  const roleSections = document.getElementById('role-specific-sections');

  try {
    const res = await API.get('/admin/compliance');
    const m = res.metrics;

    if (metricsGrid) {
      metricsGrid.innerHTML = `
        <div class="card" style="padding: 16px;">
          <div style="font-size: 0.8rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase;">Company Compliance</div>
          <div style="font-size: 1.8rem; font-weight: 700; color: var(--primary);">${m.company_compliance_rate}%</div>
        </div>
        <div class="card" style="padding: 16px;">
          <div style="font-size: 0.8rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase;">Active Employees</div>
          <div style="font-size: 1.8rem; font-weight: 700; color: var(--secondary);">${m.total_employees}</div>
        </div>
        <div class="card" style="padding: 16px;">
          <div style="font-size: 0.8rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase;">Overdue Compliance</div>
          <div style="font-size: 1.8rem; font-weight: 700; color: var(--danger);">${m.overdue_assignments}</div>
        </div>
        <div class="card" style="padding: 16px;">
          <div style="font-size: 0.8rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase;">Total Certificates</div>
          <div style="font-size: 1.8rem; font-weight: 700; color: var(--success);">${m.total_certificates}</div>
        </div>
      `;
    }

    if (roleSections) {
      roleSections.innerHTML = `
        <div class="card" style="margin-bottom: 32px;">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px;">
            <h3 style="font-size: 1.15rem; color: var(--secondary);">🏢 Department Compliance Snapshot</h3>
            <a href="/admin.html" class="btn btn-primary btn-sm">Manage & Assign Courses →</a>
          </div>
          <div class="table-responsive">
            <table class="table">
              <thead>
                <tr>
                  <th>Department</th>
                  <th>Employees</th>
                  <th>Total Assignments</th>
                  <th>Completed</th>
                  <th>Overdue</th>
                  <th>Compliance Rate</th>
                </tr>
              </thead>
              <tbody>
                ${res.department_stats.map(d => {
                  const compPercent = d.compliance_percent != null
                    ? d.compliance_percent
                    : (parseInt(d.total_assignments) > 0 ? Math.round(parseInt(d.completed_assignments) * 100 / parseInt(d.total_assignments)) : 100);
                  return `
                  <tr>
                    <td data-label="Department"><strong>${esc(d.department_name)}</strong></td>
                    <td data-label="Employees">${d.employee_count}</td>
                    <td data-label="Total Assignments">${d.total_assignments}</td>
                    <td data-label="Completed"><span style="color: var(--success); font-weight: 600;">${d.completed_assignments}</span></td>
                    <td data-label="Overdue"><span style="color: ${parseInt(d.overdue_assignments) > 0 ? 'var(--danger)' : 'var(--text-muted)'}; font-weight: 600;">${d.overdue_assignments}</span></td>
                    <td data-label="Compliance Rate">
                      <div style="display: flex; align-items: center; gap: 8px;">
                        <span>${compPercent}%</span>
                        <div class="progress-bar-container" style="width: 80px; margin: 0;">
                          <div class="progress-bar-fill ${compPercent >= 80 ? 'completed' : ''}" style="width: ${compPercent}%;"></div>
                        </div>
                      </div>
                    </td>
                  </tr>
                `;}).join('')}
              </tbody>
            </table>
          </div>
        </div>
      `;
    }
  } catch (err) {
    console.error('Failed to load admin summary:', err);
  }
}

document.addEventListener('DOMContentLoaded', initDashboard);
