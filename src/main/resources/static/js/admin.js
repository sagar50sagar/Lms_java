import { API } from './api.js';
import { requireRole } from './auth.js';

let allCourses = [];
let allDepartments = [];
let allEmployees = [];

async function initAdmin() {
  const user = requireRole('admin');
  if (!user) return;

  setupTabs();
  setupModals();

  await loadComplianceMetrics();
  await loadDepartments();
  await loadCourses();
  await loadRoster();
  await loadTrainerDeptAssignments();
}

// =================== TABS ===================
function setupTabs() {
  document.querySelectorAll('.admin-tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.admin-tab').forEach(t => {
        t.className = 'btn btn-secondary btn-sm admin-tab';
      });
      tab.className = 'btn btn-primary btn-sm admin-tab active';

      document.querySelectorAll('.admin-tab-content').forEach(c => c.style.display = 'none');
      document.getElementById(`tab-${tab.dataset.tab}`).style.display = 'block';
    });
  });
}

// =================== MODALS ===================
function setupModals() {
  // Close modal buttons
  document.querySelectorAll('.close-modal').forEach(btn => {
    btn.addEventListener('click', () => {
      document.getElementById(btn.dataset.modal).classList.remove('open');
    });
  });
  // Click outside to close
  document.querySelectorAll('.modal-overlay').forEach(modal => {
    modal.addEventListener('click', (e) => {
      if (e.target === modal) modal.classList.remove('open');
    });
  });

  // Open buttons
  document.getElementById('btn-create-dept').addEventListener('click', () => {
    document.getElementById('create-dept-modal').classList.add('open');
  });
  document.getElementById('btn-create-user').addEventListener('click', () => {
    // Populate department dropdown
    const sel = document.getElementById('new-user-dept');
    sel.innerHTML = '<option value="">-- None --</option>' + allDepartments.map(d => `<option value="${d.id}">${d.name}</option>`).join('');
    document.getElementById('create-user-modal').classList.add('open');
  });
  document.getElementById('btn-assign-course').addEventListener('click', () => {
    document.getElementById('assignment-modal').classList.add('open');
  });

  // Target radio toggle
  document.querySelectorAll('input[name="assign-target-type"]').forEach(radio => {
    radio.addEventListener('change', (e) => {
      document.getElementById('target-department-group').style.display = e.target.value === 'department' ? 'block' : 'none';
      document.getElementById('target-employee-group').style.display = e.target.value === 'employee' ? 'block' : 'none';
    });
  });

  // Form submissions
  document.getElementById('create-dept-form').addEventListener('submit', handleCreateDept);
  document.getElementById('create-user-form').addEventListener('submit', handleCreateUser);
  document.getElementById('assign-course-form').addEventListener('submit', handleAssignCourse);
  document.getElementById('assign-trainer-form').addEventListener('submit', handleAssignTrainer);
  document.getElementById('add-member-form').addEventListener('submit', handleAddMember);
}

// =================== COMPLIANCE ===================
async function loadComplianceMetrics() {
  const container = document.getElementById('admin-metrics-row');
  const deptBody = document.getElementById('department-compliance-body');
  try {
    const res = await API.get('/admin/compliance');
    const m = res.metrics;
    container.innerHTML = `
      <div class="card" style="padding:16px;"><div style="font-size:0.8rem;font-weight:600;color:var(--text-muted);text-transform:uppercase;">Company Compliance</div><div style="font-size:1.8rem;font-weight:700;color:var(--primary);">${m.company_compliance_rate}%</div></div>
      <div class="card" style="padding:16px;"><div style="font-size:0.8rem;font-weight:600;color:var(--text-muted);text-transform:uppercase;">Total Employees</div><div style="font-size:1.8rem;font-weight:700;color:var(--secondary);">${m.total_employees}</div></div>
      <div class="card" style="padding:16px;"><div style="font-size:0.8rem;font-weight:600;color:var(--text-muted);text-transform:uppercase;">Overdue</div><div style="font-size:1.8rem;font-weight:700;color:var(--danger);">${m.overdue_assignments}</div></div>
      <div class="card" style="padding:16px;"><div style="font-size:0.8rem;font-weight:600;color:var(--text-muted);text-transform:uppercase;">Certificates</div><div style="font-size:1.8rem;font-weight:700;color:var(--success);">${m.total_certificates}</div></div>
    `;
    deptBody.innerHTML = res.department_stats.map(d => `
      <tr>
        <td><strong>${d.department_name}</strong></td>
        <td>${d.employee_count}</td>
        <td>${d.total_assignments}</td>
        <td><span style="color:var(--success);font-weight:600;">${d.completed_assignments}</span></td>
        <td><span style="color:${parseInt(d.overdue_assignments)>0?'var(--danger)':'var(--text-muted)'};font-weight:600;">${d.overdue_assignments}</span></td>
        <td><div style="display:flex;align-items:center;gap:8px;"><span style="font-weight:600;">${d.compliance_percent}%</span><div class="progress-bar-container" style="width:80px;margin:0;"><div class="progress-bar-fill ${d.compliance_percent>=80?'completed':''}" style="width:${d.compliance_percent}%;"></div></div></div></td>
      </tr>
    `).join('');
  } catch (err) { console.error('Compliance metrics error:', err); }
}

// =================== DEPARTMENTS ===================
async function loadDepartments() {
  try {
    const res = await API.get('/departments');
    allDepartments = res.departments || [];

    // Populate all department selects
    const assignDeptSelect = document.getElementById('assign-department-select');
    const atDeptSelect = document.getElementById('at-dept-select');
    const deptOptions = allDepartments.map(d => `<option value="${d.id}">${d.name}</option>`).join('');
    if (assignDeptSelect) assignDeptSelect.innerHTML = deptOptions;
    if (atDeptSelect) atDeptSelect.innerHTML = deptOptions;

    // Render departments list
    const listEl = document.getElementById('departments-list');
    if (allDepartments.length === 0) {
      listEl.innerHTML = '<p style="color:var(--text-muted);">No departments yet.</p>';
      return;
    }
    listEl.innerHTML = `
      <div class="table-responsive"><table class="table">
        <thead><tr><th>Department</th><th>Description</th><th>Employees</th><th>Actions</th></tr></thead>
        <tbody>${allDepartments.map(d => `
          <tr>
            <td><strong>${d.name}</strong></td>
            <td style="color:var(--text-muted);font-size:0.85rem;">${d.description || '-'}</td>
            <td>${d.employee_count}</td>
            <td style="display:flex;gap:6px;flex-wrap:wrap;">
              <button class="btn btn-secondary btn-sm add-member-btn" data-id="${d.id}" data-name="${d.name}">+ Employee</button>
              <button class="btn btn-secondary btn-sm assign-trainer-btn" data-id="${d.id}" data-name="${d.name}">+ Trainer</button>
              <button class="btn btn-secondary btn-sm delete-dept-btn" data-id="${d.id}" style="color:var(--danger);">Delete</button>
            </td>
          </tr>
        `).join('')}</tbody>
      </table></div>
    `;

    // Attach listeners
    document.querySelectorAll('.add-member-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        document.getElementById('am-dept-id').value = btn.dataset.id;
        document.getElementById('am-dept-name').value = btn.dataset.name;
        // Load employees not in this dept
        try {
          const empRes = await API.get('/admin/employees');
          const available = (empRes.employees || []).filter(e => String(e.department_id) !== btn.dataset.id && e.role !== 'admin');
          document.getElementById('am-employee-select').innerHTML = available.map(e => `<option value="${e.id}">${e.full_name} (${e.email}) - ${e.role}</option>`).join('');
        } catch(e) {}
        document.getElementById('add-member-modal').classList.add('open');
      });
    });

    document.querySelectorAll('.assign-trainer-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        document.getElementById('at-dept-select').value = btn.dataset.id;
        // Load trainers
        try {
          const empRes = await API.get('/admin/employees?role=trainer');
          // Actually load from the general roster filtered
          const allRes = await API.get('/admin/employees');
          const trainers = (allRes.employees || []).filter(e => e.role === 'trainer');
          document.getElementById('at-trainer-select').innerHTML = trainers.map(t => `<option value="${t.id}">${t.full_name} (${t.email})</option>`).join('');
        } catch(e) {}
        document.getElementById('assign-trainer-modal').classList.add('open');
      });
    });

    document.querySelectorAll('.delete-dept-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        if (!confirm('Delete this department? Employees will be unassigned.')) return;
        try {
          await API.delete(`/departments/${btn.dataset.id}`);
          API.showToast('Department deleted.', 'success');
          loadDepartments();
          loadComplianceMetrics();
        } catch(e) { API.showToast(e.message, 'error'); }
      });
    });
  } catch (err) { console.error('Departments error:', err); }
}

async function loadTrainerDeptAssignments() {
  const el = document.getElementById('trainer-dept-assignments');
  try {
    const res = await API.get('/departments/trainer-assignments');
    const assignments = res.assignments || [];
    if (assignments.length === 0) {
      el.innerHTML = '<p style="color:var(--text-muted);font-size:0.9rem;">No trainer assignments yet. Assign trainers to departments above.</p>';
      return;
    }
    el.innerHTML = `
      <div class="table-responsive"><table class="table">
        <thead><tr><th>Trainer</th><th>Email</th><th>Department</th><th>Actions</th></tr></thead>
        <tbody>${assignments.map(a => `
          <tr>
            <td><strong>${a.trainer_name}</strong></td>
            <td style="color:var(--text-muted);font-size:0.85rem;">${a.trainer_email}</td>
            <td>${a.department_name}</td>
            <td><button class="btn btn-secondary btn-sm remove-trainer-btn" data-dept="${a.department_id}" data-trainer="${a.trainer_id}" style="color:var(--danger);">Remove</button></td>
          </tr>
        `).join('')}</tbody>
      </table></div>
    `;
    document.querySelectorAll('.remove-trainer-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        try {
          await API.delete(`/departments/${btn.dataset.dept}/trainers/${btn.dataset.trainer}`);
          API.showToast('Trainer removed from department.', 'success');
          loadTrainerDeptAssignments();
        } catch(e) { API.showToast(e.message, 'error'); }
      });
    });
  } catch (err) { console.error('Trainer assignments error:', err); }
}

// =================== USERS ===================
async function loadRoster(search = '') {
  const tbody = document.getElementById('employee-roster-body');
  try {
    const res = await API.get(`/admin/employees?search=${encodeURIComponent(search)}`);
    allEmployees = res.employees || [];

    const empSelect = document.getElementById('assign-employee-select');
    empSelect.innerHTML = allEmployees.filter(e => e.role === 'employee').map(e => `
      <option value="${e.id}">${e.full_name} (${e.email}) - ${e.department_name || 'No Dept'}</option>
    `).join('');

    tbody.innerHTML = allEmployees.map(emp => `
      <tr>
        <td><code>${emp.employee_id || 'N/A'}</code></td>
        <td><strong>${emp.full_name}</strong><div style="font-size:0.8rem;color:var(--text-muted);">${emp.email}</div></td>
        <td><span class="role-pill role-${emp.role}">${emp.role}</span></td>
        <td>${emp.department_name || '<em style="color:var(--text-muted);">Unassigned</em>'}</td>
        <td>${emp.total_assigned_courses}</td>
        <td><span style="color:var(--success);font-weight:600;">${emp.completed_courses}</span></td>
        <td><span style="color:${parseInt(emp.overdue_courses)>0?'var(--danger)':'var(--text-muted)'};font-weight:600;">${emp.overdue_courses}</span></td>
        <td>
          <button class="btn btn-secondary btn-sm change-role-btn" data-id="${emp.id}" data-role="${emp.role}" data-name="${emp.full_name}">Edit Role</button>
        </td>
      </tr>
    `).join('');

    document.querySelectorAll('.change-role-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        const newRole = prompt(`Change role for ${btn.dataset.name} (admin, trainer, employee):`, btn.dataset.role);
        if (newRole && ['admin','trainer','employee'].includes(newRole.trim().toLowerCase())) {
          try {
            await API.put(`/admin/users/${btn.dataset.id}`, { role: newRole.trim().toLowerCase() });
            API.showToast('Role updated!', 'success');
            loadRoster();
          } catch(e) { API.showToast(e.message, 'error'); }
        }
      });
    });
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="8" style="color:var(--danger);">Error: ${err.message}</td></tr>`;
  }
}

// =================== COURSES ===================
async function loadCourses() {
  try {
    const res = await API.get('/courses');
    allCourses = res.courses || [];

    const select = document.getElementById('assign-course-select');
    select.innerHTML = allCourses.map(c => `<option value="${c.id}">[${c.category}] ${c.title}</option>`).join('');

    const listEl = document.getElementById('admin-courses-list');
    if (allCourses.length === 0) {
      listEl.innerHTML = '<p style="color:var(--text-muted);">No courses yet.</p>';
      return;
    }
    listEl.innerHTML = `
      <div class="table-responsive"><table class="table">
        <thead><tr><th>Title</th><th>Category</th><th>Duration</th><th>Status</th><th>Actions</th></tr></thead>
        <tbody>${allCourses.map(c => `
          <tr>
            <td><strong>${c.title}</strong>${c.is_mandatory?'<span class="badge badge-mandatory" style="margin-left:6px;font-size:0.7rem;">Mandatory</span>':''}</td>
            <td>${c.category}</td>
            <td>${c.estimated_duration_hours||1} hrs</td>
            <td><span style="color:${c.is_published?'var(--success)':'var(--text-muted)'};font-weight:600;font-size:0.85rem;">${c.is_published?'✅ Published':'⬜ Draft'}</span></td>
            <td style="display:flex;gap:6px;flex-wrap:wrap;"><a href="/manage-course.html?id=${c.id}" class="btn btn-secondary btn-sm">✏️ Edit</a><a href="/course-detail.html?id=${c.id}" class="btn btn-secondary btn-sm">👁 View</a><button class="btn btn-secondary btn-sm delete-course-btn" data-id="${c.id}" data-title="${c.title}" style="color:var(--danger);">Delete</button></td>
          </tr>
        `).join('')}</tbody>
      </table></div>
    `;
    document.querySelectorAll('.delete-course-btn').forEach(btn => btn.addEventListener('click', async () => {
      if (!confirm(`Delete “${btn.dataset.title}”? This permanently removes its lessons, assignments, progress, quizzes, and certificates.`)) return;
      try {
        await API.delete(`/courses/${btn.dataset.id}`);
        API.showToast('Course deleted.', 'success');
        loadCourses();
        loadComplianceMetrics();
      } catch (err) { API.showToast(err.message, 'error'); }
    }));
  } catch (err) { console.error('Courses error:', err); }
}

// =================== FORM HANDLERS ===================
async function handleCreateDept(e) {
  e.preventDefault();
  const name = document.getElementById('dept-name').value.trim();
  const description = document.getElementById('dept-desc').value.trim();
  try {
    await API.post('/departments', { name, description });
    API.showToast('Department created!', 'success');
    document.getElementById('create-dept-modal').classList.remove('open');
    document.getElementById('create-dept-form').reset();
    loadDepartments();
    loadComplianceMetrics();
  } catch (err) { API.showToast(err.message, 'error'); }
}

async function handleCreateUser(e) {
  e.preventDefault();
  const body = {
    full_name: document.getElementById('new-user-name').value.trim(),
    email: document.getElementById('new-user-email').value.trim(),
    role: document.getElementById('new-user-role').value,
    department_id: document.getElementById('new-user-dept').value || null,
    designation: document.getElementById('new-user-designation').value.trim() || null,
    employee_id: document.getElementById('new-user-empid').value.trim() || null,
  };
  try {
    await API.post('/admin/users', body);
    API.showToast('User created!', 'success');
    document.getElementById('create-user-modal').classList.remove('open');
    document.getElementById('create-user-form').reset();
    loadRoster();
  } catch (err) { API.showToast(err.message, 'error'); }
}

async function handleAssignCourse(e) {
  e.preventDefault();
  const course_id = document.getElementById('assign-course-select').value;
  const targetType = document.querySelector('input[name="assign-target-type"]:checked').value;
  const due_date = document.getElementById('assign-due-date').value || null;
  const payload = { course_id, due_date };
  if (targetType === 'department') {
    payload.department_id = document.getElementById('assign-department-select').value;
  } else {
    payload.user_id = document.getElementById('assign-employee-select').value;
  }
  try {
    const res = await API.post('/assignments', payload);
    API.showToast(res.message, 'success');
    document.getElementById('assignment-modal').classList.remove('open');
    loadComplianceMetrics();
    loadRoster();
  } catch (err) { API.showToast(err.message, 'error'); }
}

async function handleAssignTrainer(e) {
  e.preventDefault();
  const dept_id = document.getElementById('at-dept-select').value;
  const trainer_id = document.getElementById('at-trainer-select').value;
  try {
    await API.post(`/departments/${dept_id}/trainers`, { trainer_id });
    API.showToast('Trainer assigned to department!', 'success');
    document.getElementById('assign-trainer-modal').classList.remove('open');
    loadTrainerDeptAssignments();
  } catch (err) { API.showToast(err.message, 'error'); }
}

async function handleAddMember(e) {
  e.preventDefault();
  const dept_id = document.getElementById('am-dept-id').value;
  const user_id = document.getElementById('am-employee-select').value;
  try {
    await API.post(`/departments/${dept_id}/members`, { user_id });
    API.showToast('Employee added to department!', 'success');
    document.getElementById('add-member-modal').classList.remove('open');
    loadDepartments();
    loadRoster();
  } catch (err) { API.showToast(err.message, 'error'); }
}

// =================== INIT ===================
document.addEventListener('DOMContentLoaded', () => {
  initAdmin();

  // Roster search
  const rosterSearch = document.getElementById('roster-search');
  if (rosterSearch) {
    let timeout;
    rosterSearch.addEventListener('input', (e) => {
      clearTimeout(timeout);
      timeout = setTimeout(() => loadRoster(e.target.value.trim()), 300);
    });
  }
});
