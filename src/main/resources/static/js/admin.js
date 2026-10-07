import { esc } from './escape.js';
import { API } from './api.js';
import { requireRole } from './auth.js';
import { UI } from './ui.js';

let allCourses = [];
let allDepartments = [];
let allEmployees = [];
let complianceStats = [];
let mdOriginal = null;

// =================== CSV EXPORT HELPERS ===================
function csvCell(value) {
  let s = value == null ? '' : String(value);
  // Excel and Sheets evaluate a cell starting with = + - @ or a control character, so a course
  // titled '=HYPERLINK(...)' would execute for whoever opens the export. Numbers stay numbers.
  const looksLikeFormula = /^[=+\-@\t\r]/.test(s) && !/^[+-]?\d+(\.\d+)?$/.test(s);
  if (looksLikeFormula) s = `'${s}`;
  return `"${s.replace(/"/g, '""')}"`;
}

function downloadCsv(filename, headers, rows) {
  const lines = [headers.map(csvCell).join(',')];
  rows.forEach(r => lines.push(r.map(csvCell).join(',')));
  const blob = new Blob(['' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function stamp() {
  return new Date().toISOString().slice(0, 10);
}

function plural(n, word) {
  return `${n} ${word}${Number(n) === 1 ? '' : 's'}`;
}

// Re-run every data loader so a change in one place is reflected everywhere
// it is relevant (compliance metrics, department headcounts, roster + all
// modal dropdowns, trainer↔department list).
async function refreshAll() {
  await Promise.all([
    loadComplianceMetrics(),
    loadDepartments(),
    loadRoster(),
    loadTrainerDeptAssignments()
  ]);
}

async function initAdmin() {
  const user = requireRole('admin');
  if (!user) return;

  setupTabs();
  setupModals();
  setupFilters();

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
    if (allDepartments.length === 0) {
      sel.innerHTML = '<option value="" disabled selected>No Departments available</option>';
    } else {
      sel.innerHTML = '<option value="">-- None --</option>' + allDepartments.map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join('');
    }
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
  document.getElementById('manage-depts-form').addEventListener('submit', handleManageDepts);
  document.getElementById('md-dept-list').addEventListener('change', syncPrimaryEnabled);
}

// =================== FILTERS ===================
function setupFilters() {
  // Departments
  const deptSearch = document.getElementById('dept-search');
  deptSearch.addEventListener('input', () => renderDepartments());
  document.querySelector('.export-departments-btn').addEventListener('click', exportDepartmentsCsv);

  // Courses
  const courseSearch = document.getElementById('course-search');
  const courseCat = document.getElementById('course-category-filter');
  const courseStatus = document.getElementById('course-status-filter');
  [courseSearch, courseCat, courseStatus].forEach(el => el.addEventListener('input', () => renderCourses()));
  document.querySelector('.export-courses-btn').addEventListener('click', exportCoursesCsv);

  // Users / roster
  const rosterSearch = document.getElementById('roster-search');
  const rosterRole = document.getElementById('roster-role-filter');
  const rosterDept = document.getElementById('roster-dept-filter');
  const rosterStatus = document.getElementById('roster-status-filter');
  [rosterSearch, rosterDept, rosterStatus].forEach(el => el.addEventListener('input', () => renderRoster()));
  rosterRole.addEventListener('change', () => renderRoster());
  document.querySelector('.export-roster-btn').addEventListener('click', exportRosterCsv);

  // Compliance
  document.getElementById('compliance-overdue-only').addEventListener('change', () => renderCompliance());
  document.querySelector('.export-compliance-btn').addEventListener('click', exportComplianceCsv);
}

function getFilteredDepartments() {
  const q = document.getElementById('dept-search').value.trim().toLowerCase();
  if (!q) return allDepartments;
  return allDepartments.filter(d =>
    (d.name || '').toLowerCase().includes(q) || (d.description || '').toLowerCase().includes(q));
}

function getFilteredCourses() {
  const q = document.getElementById('course-search').value.trim().toLowerCase();
  const cat = document.getElementById('course-category-filter').value;
  const status = document.getElementById('course-status-filter').value;
  return allCourses.filter(c => {
    if (q && !(c.title || '').toLowerCase().includes(q)) return false;
    if (cat && c.category !== cat) return false;
    if (status === 'published' && !c.is_published) return false;
    if (status === 'draft' && c.is_published) return false;
    if (status === 'mandatory' && !c.is_mandatory) return false;
    return true;
  });
}

function getFilteredRoster() {
  const q = document.getElementById('roster-search').value.trim().toLowerCase();
  const role = document.getElementById('roster-role-filter').value;
  const dept = document.getElementById('roster-dept-filter').value;
  const status = document.getElementById('roster-status-filter').value;
  return allEmployees.filter(e => {
    if (role && e.role !== role) return false;
    if (dept === '__none__' && empDeptIds(e).length > 0) return false;
    if (dept && dept !== '__none__' && !empDeptIds(e).includes(Number(dept))) return false;
    if (status === 'overdue' && !(parseInt(e.overdue_courses) > 0)) return false;
    if (status === 'setup' && !e.password_setup_required) return false;
    if (status === 'inactive' && !(parseInt(e.completed_courses) === 0 && parseInt(e.total_assigned_courses) === 0)) return false;
    if (q) {
      const hay = `${e.full_name || ''} ${e.email || ''} ${e.employee_id || ''}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });
}

// =================== COMPLIANCE ===================
async function loadComplianceMetrics() {
  try {
    const res = await API.get('/admin/compliance');
    complianceStats = (res.department_stats || []).map(d => {
      const compPercent = d.compliance_percent != null
        ? d.compliance_percent
        : (parseInt(d.total_assignments) > 0 ? Math.round(parseInt(d.completed_assignments) * 100 / parseInt(d.total_assignments)) : 100);
      return { ...d, _compPercent: compPercent };
    });
    const m = res.metrics || {};
    document.getElementById('admin-metrics-row').innerHTML = `
      <div class="card" style="padding:16px;"><div style="font-size:0.8rem;font-weight:600;color:var(--text-muted);text-transform:uppercase;">Company Compliance</div><div style="font-size:1.8rem;font-weight:700;color:var(--primary);">${m.company_compliance_rate}%</div></div>
      <div class="card" style="padding:16px;"><div style="font-size:0.8rem;font-weight:600;color:var(--text-muted);text-transform:uppercase;">Total Employees</div><div style="font-size:1.8rem;font-weight:700;color:var(--secondary);">${m.total_employees}</div></div>
      <div class="card" style="padding:16px;"><div style="font-size:0.8rem;font-weight:600;color:var(--text-muted);text-transform:uppercase;">Overdue</div><div style="font-size:1.8rem;font-weight:700;color:var(--danger);">${m.overdue_assignments}</div></div>
      <div class="card" style="padding:16px;"><div style="font-size:0.8rem;font-weight:600;color:var(--text-muted);text-transform:uppercase;">Certificates</div><div style="font-size:1.8rem;font-weight:700;color:var(--success);">${m.total_certificates}</div></div>
    `;
    renderCompliance();
  } catch (err) { console.error('Compliance metrics error:', err); }
}

function renderCompliance() {
  const deptBody = document.getElementById('department-compliance-body');
  const overdueOnly = document.getElementById('compliance-overdue-only').checked;
  const rows = overdueOnly ? complianceStats.filter(d => parseInt(d.overdue_assignments) > 0) : complianceStats;
  if (rows.length === 0) {
    deptBody.innerHTML = `<tr class="table-placeholder"><td colspan="6" style="text-align:center;color:var(--text-muted);">${overdueOnly ? 'No departments with overdue assignments.' : 'No compliance data yet.'}</td></tr>`;
    return;
  }
  deptBody.innerHTML = rows.map(d => `
      <tr>
        <td data-label="Department"><strong>${esc(d.department_name)}</strong></td>
        <td data-label="Headcount">${d.employee_count}</td>
        <td data-label="Assigned">${d.total_assignments}</td>
        <td data-label="Completed"><span style="color:var(--success);font-weight:600;">${d.completed_assignments}</span></td>
        <td data-label="Overdue"><span style="color:${parseInt(d.overdue_assignments)>0?'var(--danger)':'var(--text-muted)'};font-weight:600;">${d.overdue_assignments}</span></td>
        <td data-label="Compliance %"><div style="display:flex;align-items:center;gap:8px;"><span style="font-weight:600;">${d._compPercent}%</span><div class="progress-bar-container" style="width:80px;margin:0;"><div class="progress-bar-fill ${d._compPercent>=80?'completed':''}" style="width:${d._compPercent}%;"></div></div></div></td>
      </tr>
    `).join('');
}

// =================== DEPARTMENTS ===================
async function loadDepartments() {
  try {
    const res = await API.get('/departments');
    allDepartments = res.departments || [];

    // Populate all department selects
    const assignDeptSelect = document.getElementById('assign-department-select');
    const atDeptSelect = document.getElementById('at-dept-select');
    const deptOptions = allDepartments.length === 0
      ? '<option value="" disabled selected>No Departments available</option>'
      : allDepartments.map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join('');
    if (assignDeptSelect) assignDeptSelect.innerHTML = deptOptions;
    if (atDeptSelect) atDeptSelect.innerHTML = deptOptions;

    renderDepartments();
  } catch (err) { console.error('Departments error:', err); }
}

function renderDepartments() {
  const listEl = document.getElementById('departments-list');
  const depts = getFilteredDepartments();
  if (allDepartments.length === 0) {
    listEl.innerHTML = '<p style="color:var(--text-muted);">No departments yet.</p>';
    return;
  }
  if (depts.length === 0) {
    listEl.innerHTML = '<p style="color:var(--text-muted);">No departments match your search.</p>';
    return;
  }
  listEl.innerHTML = `
      <div class="table-responsive"><table class="table">
        <thead><tr><th>Department</th><th>Description</th><th>Employees</th><th style="text-align:right">Actions</th></tr></thead>
        <tbody>${depts.map(d => `
          <tr>
            <td data-label="Department"><strong>${esc(d.name)}</strong></td>
            <td data-label="Description" style="color:var(--text-muted);font-size:0.85rem;">${d.description || '-'}</td>
            <td data-label="Employees">${d.employee_count}</td>
            <td data-label="Actions" class="cell-actions">
              <div class="actions">
                <button class="btn btn-secondary btn-sm add-member-btn" data-id="${d.id}" data-name="${esc(d.name)}">+ Employee</button>
                <button class="btn btn-secondary btn-sm assign-trainer-btn" data-id="${d.id}" data-name="${esc(d.name)}">+ Trainer</button>
                <button class="btn btn-secondary btn-sm delete-dept-btn" data-id="${d.id}" style="color:var(--danger);">Delete</button>
              </div>
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
        const available = (empRes.employees || []).filter(e => !empDeptIds(e).includes(Number(btn.dataset.id)) && e.role !== 'admin');
        const amSel = document.getElementById('am-employee-select');
        if (available.length === 0) {
          amSel.innerHTML = '<option value="" disabled selected>No Employees available</option>';
        } else {
          amSel.innerHTML = available.map(e => `<option value="${e.id}">${esc(e.full_name)} · ${esc(e.email)}</option>`).join('');
        }
      } catch(e) {}
      document.getElementById('add-member-modal').classList.add('open');
    });
  });

  document.querySelectorAll('.assign-trainer-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      document.getElementById('at-dept-select').value = btn.dataset.id;
      // Load candidates: any non-admin (promoting an employee here makes them a trainer of this dept)
      try {
        const allRes = await API.get('/admin/employees');
        const currentTrainers = new Set(((window.__trainerAssignments||[]).filter(x=>String(x.department_id)===String(btn.dataset.id)).map(x=>Number(x.trainer_id))));
        const candidates = (allRes.employees || []).filter(e => !currentTrainers.has(Number(e.id)));
        const atSel = document.getElementById('at-trainer-select');
        if (candidates.length === 0) {
          atSel.innerHTML = '<option value="" disabled selected>No users available</option>';
        } else {
          atSel.innerHTML = candidates.map(t => `<option value="${t.id}">${esc(t.full_name)} · ${esc(t.email)}</option>`).join('');
        }
      } catch(e) {}
      document.getElementById('assign-trainer-modal').classList.add('open');
    });
  });

  document.querySelectorAll('.delete-dept-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const id = Number(btn.dataset.id);
      const dept = allDepartments.find(d => Number(d.id) === id) || {};
      const members = Number(dept.employee_count || 0);
      const trainers = (window.__trainerAssignments || []).filter(x => Number(x.department_id) === id);
      if (!(await UI.confirm({
        title: 'Delete Department?',
        message: `Delete “${dept.name || 'this department'}”? This cannot be undone.`,
        items: [
          { label: `${plural(members, 'membership')} removed`, text: 'here; anyone left with no other department becomes unassigned.' },
          trainers.length ? {
            label: `${plural(trainers.length, 'trainer')} affected`,
            text: trainers.length === 1
              ? 'They stop managing this department, and if it was their only one they drop back to Employee.'
              : 'They stop managing this department, and anyone left managing nothing drops back to Employee.',
          } : null,
          'Courses and assignments already made here are kept.',
        ],
        type: 'danger', confirmText: 'Delete Department'
      }))) return;
      try {
        await API.delete(`/departments/${btn.dataset.id}`);
        API.showToast('Department deleted.', 'success');
        refreshAll();
      } catch(e) { API.showToast(e.message, 'error'); }
    });
  });
}

async function loadTrainerDeptAssignments() {
  const el = document.getElementById('trainer-dept-assignments');
  try {
    const res = await API.get('/departments/trainer-assignments');
    const assignments = res.assignments || [];
    window.__trainerAssignments = assignments;
    if (assignments.length === 0) {
      el.innerHTML = '<p style="color:var(--text-muted);font-size:0.9rem;">No trainer assignments yet. Use "+ Trainer" on a department to promote someone.</p>';
      return;
    }
    el.innerHTML = `
      <div class="table-responsive"><table class="table">
        <thead><tr><th>Trainer</th><th>Email</th><th>Department</th><th style="text-align:right">Actions</th></tr></thead>
        <tbody>${assignments.map(a => `
          <tr>
            <td data-label="Trainer"><strong>${esc(a.trainer_name)}</strong></td>
            <td data-label="Email" style="color:var(--text-muted);font-size:0.85rem;">${a.trainer_email}</td>
            <td data-label="Department">${esc(a.department_name)}</td>
            <td data-label="Actions" class="cell-actions"><div class="actions"><button class="btn btn-secondary btn-sm remove-trainer-btn" data-dept="${a.department_id}" data-trainer="${a.trainer_id}" style="color:var(--danger);">Remove</button></div></td>
          </tr>
        `).join('')}</tbody>
      </table></div>
    `;
    document.querySelectorAll('.remove-trainer-btn').forEach(btn => {
      btn.addEventListener('click', async () => {
        const rows = window.__trainerAssignments || [];
        const mine = rows.filter(x => Number(x.trainer_id) === Number(btn.dataset.trainer));
        const row = rows.find(x => Number(x.trainer_id) === Number(btn.dataset.trainer) && Number(x.department_id) === Number(btn.dataset.dept)) || {};
        if (!(await UI.confirm({
          title: 'Remove Trainer?',
          message: `Stop ${row.trainer_name || 'this trainer'} from managing “${row.department_name || 'this department'}”?`,
          items: [
            `They stay a member of ${row.department_name || 'the department'} and keep all other departments.`,
            mine.length === 1
              ? 'This is their only managed department, so they become an Employee and can no longer assign its courses.'
              : `They still manage ${plural(mine.length - 1, 'other department')}, so they stay a Trainer.`,
          ],
          type: 'warning', confirmText: 'Remove Trainer'
        }))) return;
        try {
          await API.delete(`/departments/${btn.dataset.dept}/trainers/${btn.dataset.trainer}`);
          API.showToast(`${row.trainer_name || 'Trainer'} no longer manages ${row.department_name || 'the department'}.`, 'success');
          refreshAll();
        } catch(e) { API.showToast(e.message, 'error'); }
      });
    });
  } catch (err) { console.error('Trainer assignments error:', err); }
}

// =================== USERS ===================
async function loadRoster() {
  try {
    const res = await API.get('/admin/employees');
    allEmployees = res.employees || [];
    populateRosterDeptFilter();
    renderRoster();
  } catch (err) {
    document.getElementById('employee-roster-body').innerHTML = `<tr class="table-placeholder"><td colspan="8" style="color:var(--danger);">Error: ${esc(err.message)}</td></tr>`;
  }
}

function populateRosterDeptFilter() {
  const sel = document.getElementById('roster-dept-filter');
  const current = sel.value;
  sel.innerHTML = '<option value="">All Departments</option>' +
    allDepartments.map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join('') +
    '<option value="__none__">Unassigned</option>';
  if (current && [...sel.options].some(o => o.value === current)) sel.value = current;
}

function empDeptIds(emp) {
  const ids = (emp.departments || []).map(d => Number(d.id));
  if (emp.department_id && !ids.includes(Number(emp.department_id))) ids.unshift(Number(emp.department_id));
  return [...new Set(ids)];
}

function deptName(id) {
  const d = allDepartments.find(x => Number(x.id) === Number(id));
  return d ? d.name : ('#' + id);
}

function deptChips(emp) {
  const primary = Number(emp.department_id);
  const ids = empDeptIds(emp);
  if (ids.length === 0) return '<span style="font-size:0.8rem;color:var(--text-muted);">Unassigned</span>';
  return ids.map(id => {
    const isPrimary = primary === Number(id);
    return `<span class="dept-chip ${isPrimary ? 'dept-chip-primary' : 'dept-chip-secondary'}">${isPrimary ? '★' : ''}${deptName(id)}</span>`;
  }).join('');
}

function openManageDeptsModal(userId) {
  const emp = allEmployees.find(e => Number(e.id) === Number(userId));
  if (!emp) return;
  const current = empDeptIds(emp);
  const primary = Number(emp.department_id);
  mdOriginal = { emp, ids: current };
  document.getElementById('md-user-id').value = userId;
  document.getElementById('md-user-name').value = emp.full_name;
  const holder = document.getElementById('md-dept-list');
  holder.innerHTML = allDepartments.length === 0
    ? '<div style="color:var(--text-muted);font-size:0.85rem;">No departments exist.</div>'
    : allDepartments.map(d => `
      <label class="opt-row">
        <input type="checkbox" class="md-dept-check" value="${d.id}" ${current.includes(Number(d.id)) ? 'checked' : ''}>
        <span style="flex:1;">${esc(d.name)}</span>
        <input type="radio" name="md-primary" value="${d.id}" ${Number(d.id) === primary ? 'checked' : ''} title="Set as primary department" aria-label="Set ${esc(d.name)} as primary department">
      </label>`).join('') +
      '<div style="font-size:0.75rem;color:var(--text-muted);margin-top:6px;">Tick to belong to a department. The radio sets the primary/home department.</div>';
  syncPrimaryEnabled();
  document.getElementById('manage-depts-modal').classList.add('open');
}

function syncPrimaryEnabled() {
  const checked = new Set([...document.querySelectorAll('.md-dept-check:checked')].map(c => Number(c.value)));
  document.querySelectorAll('input[name="md-primary"]').forEach(r => {
    r.disabled = !checked.has(Number(r.value));
    if (r.disabled && r.checked) r.checked = false;
  });
}

async function handleManageDepts(e) {
  e.preventDefault();
  const userId = document.getElementById('md-user-id').value;
  const departmentIds = [...document.querySelectorAll('.md-dept-check:checked')].map(c => Number(c.value));
  const primarySel = document.querySelector('input[name="md-primary"]:checked');
  const primary_department_id = primarySel ? Number(primarySel.value) : (departmentIds.length ? departmentIds[0] : null);
  const before = mdOriginal ? mdOriginal.ids : [];
  const removed = before.filter(id => !departmentIds.includes(id));
  const added = departmentIds.filter(id => !before.includes(id));
  if (removed.length) {
    const managed = (window.__trainerAssignments || []).filter(x => Number(x.trainer_id) === Number(userId));
    const lost = managed.filter(x => removed.includes(Number(x.department_id)));
    const keeps = managed.length - lost.length;
    const ok = await UI.confirm({
      title: 'Remove Departments?',
      message: `${mdOriginal && mdOriginal.emp ? mdOriginal.emp.full_name : 'This user'} will no longer belong to ${removed.map(deptName).join(', ')}.`,
      items: [
        lost.length === 0 ? 'They do not manage any of these departments, so their role is unchanged.' : null,
        lost.length && keeps
          ? { label: `No longer manages ${lost.map(x => x.department_name).join(', ')}.`, text: `Still a Trainer for ${plural(keeps, 'other department')}.` }
          : null,
        lost.length && !keeps
          ? { label: 'They become an Employee.', text: 'This was their only managed department, so they can no longer assign its courses.' }
          : null,
        added.length ? `They also gain ${added.map(deptName).join(', ')}.` : null,
      ],
      type: 'warning', confirmText: 'Save Changes'
    });
    if (!ok) return;
  }
  try {
    const result = await API.put(`/admin/users/${userId}`, { department_ids: departmentIds, primary_department_id });
    const parts = [];
    if (added.length) parts.push(`added to ${added.map(deptName).join(', ')}`);
    if (removed.length) parts.push(`removed from ${removed.map(deptName).join(', ')}`);
    API.showToast(parts.length ? `Departments updated — ${parts.join(' and ')}.` : (result.message || 'Departments updated!'), 'success');
    document.getElementById('manage-depts-modal').classList.remove('open');
    await refreshAll();
  } catch (err) {
    API.showToast(err.message, 'error');
  }
}

function renderRoster() {
  const tbody = document.getElementById('employee-roster-body');

  const empSelect = document.getElementById('assign-employee-select');
  const employeesOnly = allEmployees.filter(e => e.role === 'employee');
  if (empSelect) {
    if (employeesOnly.length === 0) {
      empSelect.innerHTML = '<option value="" disabled selected>No Employees available</option>';
    } else {
      empSelect.innerHTML = employeesOnly.map(e => `
        <option value="${e.id}">${esc(e.full_name)} (${esc(e.email)}) - ${e.department_name || 'No Dept'}</option>
      `).join('');
    }
  }

  const list = getFilteredRoster();
  if (list.length === 0) {
    tbody.innerHTML = '<tr class="table-placeholder"><td colspan="8" style="text-align:center;color:var(--text-muted);">No users match the selected filters.</td></tr>';
    return;
  }
  tbody.innerHTML = list.map(emp => `
      <tr class="${emp.is_active ? '' : 'row-inactive'}">
        <td data-label="ID"><code>${emp.employee_id || 'N/A'}</code></td>
        <td data-label="Name"><strong>${esc(emp.full_name)}</strong><div style="font-size:0.8rem;color:var(--text-muted);">${esc(emp.email)}</div>
          <span class="status-pill ${emp.is_active ? 'status-pill-active' : 'status-pill-inactive'}">${emp.is_active ? 'Active' : 'Inactive'}</span>
        </td>
        <td data-label="Role">
          <span class="role-badge role-badge-${emp.role === 'trainer' ? 'trainer' : 'employee'}">${emp.role}</span>
          ${emp.role==='trainer' ? '<div style="font-size:0.72rem;color:var(--text-muted);">via dept &rarr; Trainer</div>' : ''}
        </td>
        <td data-label="Department">
          <div style="display:flex;flex-wrap:wrap;gap:4px;align-items:center;">
            ${deptChips(emp)}
            <button class="btn btn-secondary btn-sm manage-depts-btn" data-id="${emp.id}">Manage</button>
          </div>
        </td>
        <td data-label="Assigned">${emp.total_assigned_courses}</td>
        <td data-label="Completed"><span style="color:var(--success);font-weight:600;">${emp.completed_courses}</span></td>
        <td data-label="Overdue"><span style="color:${parseInt(emp.overdue_courses)>0?'var(--danger)':'var(--text-muted)'};font-weight:600;">${emp.overdue_courses}</span></td>
        <td data-label="Actions" class="cell-actions">
          <div class="actions">
            <button class="btn btn-secondary btn-sm reset-password-btn" data-id="${emp.id}" data-name="${esc(emp.full_name)}">Reset Password</button>
            <button class="btn btn-secondary btn-sm toggle-active-btn" data-id="${emp.id}" data-name="${esc(emp.full_name)}" data-active="${emp.is_active ? '1' : '0'}">${emp.is_active ? 'Deactivate' : 'Activate'}</button>
            <button class="btn btn-secondary btn-sm delete-user-btn" data-id="${emp.id}" data-name="${esc(emp.full_name)}" style="color:var(--danger);">Delete</button>
          </div>
        </td>
      </tr>
    `).join('');

  document.querySelectorAll('.manage-depts-btn').forEach(btn => {
    btn.addEventListener('click', () => openManageDeptsModal(parseInt(btn.dataset.id)));
  });
  document.querySelectorAll('.reset-password-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const newPassword = await UI.prompt({
        title: 'Reset Password',
        message: `Set a new password for ${btn.dataset.name}. They will use it the next time they sign in.`,
        label: 'New password',
        inputType: 'password',
        placeholder: 'At least 8 characters',
        minLength: 8,
        showGenerate: true,
        confirmText: 'Reset Password'
      });
      if (!newPassword) return;
      try {
        const result = await API.post(`/admin/users/${btn.dataset.id}/reset-password`, { new_password: newPassword });
        API.showToast(result.message, 'success');
      } catch (e) { API.showToast(e.message, 'error'); }
    });
  });
  document.querySelectorAll('.toggle-active-btn').forEach(btn => {
    const active = btn.dataset.active === '1';
    btn.addEventListener('click', async () => {
      if (active && !(await UI.confirm({ title: 'Deactivate Account?', message: `${btn.dataset.name} will be signed out immediately and cannot log back in. Their assignments, progress and certificates are kept.`, type: 'warning', confirmText: 'Deactivate' }))) return;
      try {
        const result = await API.put(`/admin/users/${btn.dataset.id}`, { is_active: !active });
        API.showToast(result.message, 'success');
        loadRoster();
      } catch (e) { API.showToast(e.message, 'error'); }
    });
  });
  document.querySelectorAll('.delete-user-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const ok = await UI.confirm({
        title: 'Delete Account Permanently?',
        message: `This erases ${btn.dataset.name} along with their assignments, lesson progress and certificates. It cannot be undone. To keep the training history, deactivate the account instead.`,
        type: 'danger', confirmText: 'Delete Permanently'
      });
      if (!ok) return;
      try { const result = await API.delete(`/admin/users/${btn.dataset.id}`); API.showToast(result.message, 'success'); loadRoster(); }
      catch (e) { API.showToast(e.message, 'error'); }
    });
  });
}

// =================== COURSES ===================
async function loadCourses() {
  try {
    const res = await API.get('/courses');
    allCourses = res.courses || [];

    const select = document.getElementById('assign-course-select');
    if (allCourses.length === 0) {
      select.innerHTML = '<option value="" disabled selected>No Courses available</option>';
    } else {
      select.innerHTML = allCourses.map(c => `<option value="${c.id}">[${esc(c.category)}] ${esc(c.title)}</option>`).join('');
    }

    populateCourseCategoryFilter();
    renderCourses();
  } catch (err) { console.error('Courses error:', err); }
}

function populateCourseCategoryFilter() {
  const sel = document.getElementById('course-category-filter');
  const current = sel.value;
  const cats = [...new Set(allCourses.map(c => c.category).filter(Boolean))].sort();
  sel.innerHTML = '<option value="">All Categories</option>' + cats.map(c => `<option value="${c}">${c}</option>`).join('');
  if (current && cats.includes(current)) sel.value = current;
}

function renderCourses() {
  const listEl = document.getElementById('admin-courses-list');
  if (allCourses.length === 0) {
    listEl.innerHTML = '<p style="color:var(--text-muted);">No courses yet.</p>';
    return;
  }
  const courses = getFilteredCourses();
  if (courses.length === 0) {
    listEl.innerHTML = '<p style="color:var(--text-muted);">No courses match the selected filters.</p>';
    return;
  }
  listEl.innerHTML = `
      <div class="table-responsive"><table class="table">
        <thead><tr><th>Title</th><th>Category</th><th>Duration</th><th>Status</th><th style="text-align:right">Actions</th></tr></thead>
        <tbody>${courses.map(c => `
          <tr>
            <td data-label="Title"><strong>${esc(c.title)}</strong>${c.is_mandatory?'<span class="badge badge-mandatory" style="margin-left:6px;font-size:0.7rem;">Mandatory</span>':''}</td>
            <td data-label="Category">${esc(c.category)}</td>
            <td data-label="Duration">${c.estimated_duration_hours||1} hrs</td>
            <td data-label="Status"><span style="color:${c.is_published?'var(--success)':'var(--text-muted)'};font-weight:600;font-size:0.85rem;">${c.is_published?'✅ Published':'⬜ Draft'}</span></td>
            <td data-label="Actions" class="cell-actions"><div class="actions"><a href="/manage-course.html?id=${c.id}" class="btn btn-secondary btn-sm">✏️ Edit</a><a href="/course-detail.html?id=${c.id}" class="btn btn-secondary btn-sm">👁 View</a><button class="btn btn-secondary btn-sm delete-course-btn" data-id="${c.id}" data-title="${esc(c.title)}" style="color:var(--danger);">Delete</button></div></td>
          </tr>
        `).join('')}</tbody>
      </table></div>
    `;
  document.querySelectorAll('.delete-course-btn').forEach(btn => btn.addEventListener('click', async () => {
    if (!(await UI.confirm({ title: 'Delete Course?', message: `Delete “${btn.dataset.title}”? This permanently removes its lessons, assignments, progress, quizzes, and certificates.`, type: 'danger', confirmText: 'Delete Course' }))) return;
    try {
      await API.delete(`/courses/${btn.dataset.id}`);
      API.showToast('Course deleted.', 'success');
      loadCourses();
      loadComplianceMetrics();
    } catch (err) { API.showToast(err.message, 'error'); }
  }));
}

// =================== CSV EXPORTS ===================
function exportComplianceCsv() {
  downloadCsv(`compliance-${stamp()}.csv`,
    ['Department', 'Headcount', 'Assigned', 'Completed', 'Overdue', 'Compliance %'],
    complianceStats.map(d => [d.department_name, d.employee_count, d.total_assignments, d.completed_assignments, d.overdue_assignments, `${d._compPercent}%`]));
}

function exportDepartmentsCsv() {
  downloadCsv(`departments-${stamp()}.csv`,
    ['ID', 'Department', 'Description', 'Employees'],
    getFilteredDepartments().map(d => [d.id, d.name, d.description || '', d.employee_count]));
}

function exportRosterCsv() {
  downloadCsv(`users-${stamp()}.csv`,
    ['Employee ID', 'Name', 'Email', 'Role', 'Department', 'Assigned', 'Completed', 'Overdue', 'Setup Status'],
    getFilteredRoster().map(e => [
      e.employee_id || '', e.full_name, e.email, e.role, (empDeptIds(e).map(deptName).join('; ')) || 'Unassigned',
      e.total_assigned_courses, e.completed_courses, e.overdue_courses,
      e.password_setup_required ? 'Setup Pending' : 'Active'
    ]));
}

function exportCoursesCsv() {
  downloadCsv(`courses-${stamp()}.csv`,
    ['ID', 'Title', 'Category', 'Duration (hrs)', 'Mandatory', 'Status'],
    getFilteredCourses().map(c => [
      c.id, c.title, c.category || '', c.estimated_duration_hours || 1,
      c.is_mandatory ? 'Yes' : 'No', c.is_published ? 'Published' : 'Draft'
    ]));
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
    refreshAll();
  } catch (err) { API.showToast(err.message, 'error'); }
}

async function handleCreateUser(e) {
  e.preventDefault();
  const body = {
    full_name: document.getElementById('new-user-name').value.trim(),
    email: document.getElementById('new-user-email').value.trim(),
    password: document.getElementById('new-user-password').value,
    department_id: document.getElementById('new-user-dept').value || null,
    designation: document.getElementById('new-user-designation').value.trim() || null,
    employee_id: document.getElementById('new-user-empid').value.trim() || null,
  };
  try {
    await API.post('/admin/users', body);
    API.showToast('User created!', 'success');
    document.getElementById('create-user-modal').classList.remove('open');
    document.getElementById('create-user-form').reset();
    refreshAll();
  } catch (err) { API.showToast(err.message, 'error'); }
}

async function handleAssignCourse(e) {
  e.preventDefault();
  const course_id = document.getElementById('assign-course-select').value;
  if (!course_id) {
    API.showToast('Please select a course.', 'error');
    return;
  }
  const targetType = document.querySelector('input[name="assign-target-type"]:checked').value;
  const due_date = document.getElementById('assign-due-date').value || null;
  const payload = { course_id, due_date };
  if (targetType === 'department') {
    payload.department_id = document.getElementById('assign-department-select').value;
    if (!payload.department_id) {
      API.showToast('Please select a department.', 'error');
      return;
    }
    // The server only enrols role='employee' members, so a trainers-only department would fail.
    const target = allDepartments.find(d => Number(d.id) === Number(payload.department_id)) || {};
    if (Number(target.employee_count || 0) === 0) {
      API.showToast(`${target.name || 'This department'} has no employees to assign yet. Add employees to it first.`, 'error');
      return;
    }
  } else {
    payload.user_id = document.getElementById('assign-employee-select').value;
    if (!payload.user_id) {
      API.showToast('Please select an employee.', 'error');
      return;
    }
  }
  const course = allCourses.find(c => Number(c.id) === Number(course_id)) || {};
  const items = [];
  let targetLabel;
  if (targetType === 'department') {
    const dept = allDepartments.find(d => Number(d.id) === Number(payload.department_id)) || {};
    const n = Number(dept.employee_count || 0);
    targetLabel = `all ${plural(n, 'employee')} in ${dept.name || 'this department'}`;
    items.push({ label: `${plural(n, 'assignment')} created.`, text: 'Everyone in the department gets it on their dashboard.' });
  } else {
    const person = allEmployees.find(e => Number(e.id) === Number(payload.user_id)) || {};
    targetLabel = person.full_name || 'this employee';
    items.push('1 assignment created.');
  }
  if (course.is_published === false) items.push('This course is a Draft and will be published so the assignees can open it.');
  if (due_date) items.push(`Due by ${due_date}.`);
  items.push('Employees can see the course and it counts toward compliance.');
  if (!(await UI.confirm({
    title: 'Assign Course?',
    message: `Assign “${course.title || 'this course'}” to ${targetLabel}?`,
    items, type: 'info', confirmText: 'Assign Course'
  }))) return;
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
  if (!dept_id || !trainer_id) {
    API.showToast('Please select both department and trainer.', 'error');
    return;
  }
  const dept = allDepartments.find(d => Number(d.id) === Number(dept_id)) || {};
  const person = allEmployees.find(e => Number(e.id) === Number(trainer_id)) || {};
  const alreadyManaging = (window.__trainerAssignments || []).some(x => Number(x.trainer_id) === Number(trainer_id));
  if (!(await UI.confirm({
    title: 'Make Trainer?',
    message: `Give ${person.full_name || 'this user'} training rights over “${dept.name || 'this department'}”?`,
    items: [
      `They can assign courses in ${dept.name || 'this department'} to its employees.`,
      alreadyManaging
        ? `They are already a Trainer elsewhere, so their role stays Trainer.`
        : { label: 'Their role becomes Trainer.', text: 'It is derived from the departments they manage.' },
      `They also become a member of ${dept.name || 'this department'}.`,
    ],
    type: 'info', confirmText: 'Promote To Trainer'
  }))) return;
  try {
    await API.post(`/departments/${dept_id}/trainers`, { trainer_id });
    API.showToast(`${person.full_name || 'Trainer'} now manages ${dept.name || 'the department'}.`, 'success');
    document.getElementById('assign-trainer-modal').classList.remove('open');
    refreshAll();
  } catch (err) { API.showToast(err.message, 'error'); }
}

async function handleAddMember(e) {
  e.preventDefault();
  const dept_id = document.getElementById('am-dept-id').value;
  const user_id = document.getElementById('am-employee-select').value;
  if (!user_id) {
    API.showToast('Please select a valid employee.', 'error');
    return;
  }
  try {
    await API.post(`/departments/${dept_id}/members`, { user_id });
    const person = allEmployees.find(e => Number(e.id) === Number(user_id)) || {};
    const deptNameLabel = document.getElementById('am-dept-name').value;
    API.showToast(`${person.full_name || 'Employee'} added to ${deptNameLabel || 'the department'}.`, 'success');
    document.getElementById('add-member-modal').classList.remove('open');
    refreshAll();
  } catch (err) { API.showToast(err.message, 'error'); }
}

// =================== INIT ===================
document.addEventListener('DOMContentLoaded', () => {
  initAdmin();
});
