import { API } from './api.js';

let activeCategory = '';
let searchQuery = '';
let currentUser = null;

async function fetchAndRenderCourses() {
  const container = document.getElementById('course-catalog-grid');
  let url = '/courses?';
  if (activeCategory) url += `category=${encodeURIComponent(activeCategory)}&`;
  if (searchQuery) url += `search=${encodeURIComponent(searchQuery)}&`;

  try {
    const res = await API.get(url);
    const courses = res.courses || [];

    if (courses.length === 0) {
      container.innerHTML = `
        <div class="card" style="grid-column: 1 / -1; text-align: center; padding: 40px;">
          <h3>No matching training courses found</h3>
          <p style="color: var(--text-muted); margin-top: 8px;">Try clearing filters or search terms.</p>
        </div>
      `;
      return;
    }

    const canEdit = currentUser && (currentUser.role === 'admin' || currentUser.role === 'trainer');

    container.innerHTML = courses.map(course => {
      return `
        <div class="card" style="display: flex; flex-direction: column;">
          <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px;">
            <span class="badge ${course.is_mandatory ? 'badge-mandatory' : 'badge-optional'}">
              ${course.is_mandatory ? '⚠️ Mandatory' : 'Elective'}
            </span>
            <span style="font-size: 0.8rem; font-weight: 600; color: var(--text-muted);">${course.category}</span>
          </div>
          <h3 style="font-size: 1.15rem; margin-bottom: 8px;">${course.title}</h3>
          <p style="color: var(--text-muted); font-size: 0.88rem; flex: 1; margin-bottom: 16px;">${course.description}</p>
          <div style="display: flex; justify-content: space-between; align-items: center; border-top: 1px solid var(--border); padding-top: 12px; font-size: 0.82rem; color: var(--text-muted);">
            <span>⏱ ${course.estimated_duration_hours || 1.0} hrs</span>
            <span>📚 ${course.total_lessons || 0} Lessons</span>
            <span>👥 ${course.enrolled_count || 0} Enrolled</span>
          </div>
          <div style="margin-top: 14px; display: flex; flex-direction: column; gap: 6px;">
            <a href="/course-detail.html?id=${course.id}" class="btn btn-primary btn-block btn-sm">View Course</a>
            ${canEdit ? `<a href="/manage-course.html?id=${course.id}" class="btn btn-secondary btn-block btn-sm">✏️ Edit Course</a>` : ''}
          </div>
        </div>
      `;
    }).join('');
  } catch (err) {
    container.innerHTML = `<p style="color: var(--danger); text-align: center; grid-column: 1 / -1;">Error: ${err.message}</p>`;
  }
}

document.addEventListener('DOMContentLoaded', () => {
  currentUser = API.getUser();

  // Employees can only see their assigned courses on dashboard
  if (currentUser && currentUser.role === 'employee') {
    window.location.href = '/dashboard.html';
    return;
  }

  fetchAndRenderCourses();

  const searchInput = document.getElementById('course-search-input');
  let timeout = null;
  if (searchInput) {
    searchInput.addEventListener('input', (e) => {
      clearTimeout(timeout);
      timeout = setTimeout(() => {
        searchQuery = e.target.value.trim();
        fetchAndRenderCourses();
      }, 300);
    });
  }

  document.querySelectorAll('.filter-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.filter-btn').forEach(b => b.className = 'btn btn-secondary btn-sm filter-btn');
      btn.className = 'btn btn-primary btn-sm filter-btn';
      activeCategory = btn.dataset.category;
      fetchAndRenderCourses();
    });
  });
});
