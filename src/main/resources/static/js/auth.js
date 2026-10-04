import { API } from './api.js';
import { UI } from './ui.js';

// PDF certificate downloads (delegated; all pages import this module).
// Route to the certificate view so the downloaded file matches the on-screen design.
document.addEventListener('click', (e) => {
  const btn = e.target.closest('.cert-pdf-btn');
  if (!btn) return;
  e.preventDefault();
  const code = btn.dataset.code;
  window.location.href = `/certificate-view.html?code=${encodeURIComponent(code)}`;
});

export function renderNavbar() {
  const navContainer = document.querySelector('.navbar');
  if (!navContainer) return;

  const user = API.getUser();
  const currentPath = window.location.pathname;

  let desktopNavLinks = `
    <li><a href="/index.html" class="nav-link ${currentPath === '/' || currentPath.endsWith('index.html') ? 'active' : ''}">Overview</a></li>
    <li><a href="/courses.html" class="nav-link ${currentPath.includes('courses.html') ? 'active' : ''}">Training Catalog</a></li>
  `;

  let mobileUserAction = '';

  if (user) {
    desktopNavLinks += `
      <li><a href="/dashboard.html" class="nav-link ${currentPath.includes('dashboard.html') ? 'active' : ''}">Dashboard</a></li>
    `;

    // Trainers and Admins can access Course Builder
    if (user.role === 'trainer' || user.role === 'admin') {
      desktopNavLinks += `
        <li><a href="/manage-course.html" class="nav-link ${currentPath.includes('manage-course.html') ? 'active' : ''}">+ Course Builder</a></li>
      `;
    }

    // Admins only: Admin Compliance Hub
    if (user.role === 'admin') {
      desktopNavLinks += `
        <li><a href="/admin.html" class="nav-link ${currentPath.includes('admin.html') ? 'active' : ''}">Admin Compliance</a></li>
      `;
    }

    desktopNavLinks += `
      <li>
        <div class="user-profile-badge">
          <span>${user.full_name || user.email}</span>
          <span class="role-pill role-${user.role}">${user.role}</span>
          <a href="/password.html" class="btn btn-secondary btn-sm" style="margin-left: 6px; padding: 2px 8px; font-size: 0.78rem;">Password</a>
          <button id="logout-btn" class="btn btn-secondary btn-sm" style="margin-left: 6px; padding: 2px 8px; font-size: 0.78rem;">Sign Out</button>
        </div>
      </li>
    `;

    mobileUserAction = `
      <div style="display: flex; align-items: center; gap: 8px;">
        <span class="role-pill role-${user.role}">${user.role}</span>
        <button id="mobile-logout-btn" class="btn btn-secondary btn-sm" style="min-height: 34px; padding: 4px 8px; font-size: 0.75rem;">Exit</button>
      </div>
    `;
  } else {
    desktopNavLinks += `
      <li><a href="/login.html" class="btn btn-primary btn-sm">Employee Sign In</a></li>
    `;

    mobileUserAction = `
      <a href="/login.html" class="btn btn-primary btn-sm" style="min-height: 36px; padding: 6px 12px; font-size: 0.85rem;">Sign In</a>
    `;
  }

  // Render Top Header
  navContainer.innerHTML = `
    <div class="container nav-container">
      <a href="/index.html" class="nav-brand">
        <img class="nav-brand-logo" src="/images/logo.jpeg" alt="QT Consultancy logo">
        <span class="nav-brand-text">
          <span class="nav-brand-name">QT Consultancy</span>
          <span class="nav-brand-tag">Trusted Manpower Supplier</span>
        </span>
      </a>

      <!-- Desktop Links -->
      <ul class="nav-links">
        ${desktopNavLinks}
      </ul>

      <!-- Mobile Top Right Action -->
      <div class="mobile-only-action" style="display: none;">
        ${mobileUserAction}
      </div>
    </div>
  `;

  // Apply display rule for mobile-only-action
  const styleEl = document.createElement('style');
  styleEl.textContent = `
    @media (max-width: 768px) {
      .mobile-only-action { display: block !important; }
    }
  `;
  document.head.appendChild(styleEl);

  // Render Mobile Bottom Navigation Bar (if not in player)
  if (!window.location.pathname.includes('course-player.html')) {
    renderMobileBottomNav(user, currentPath);
  }

  // Attach logout listeners
  const logoutBtn = document.getElementById('logout-btn');
  if (logoutBtn) logoutBtn.addEventListener('click', () => API.logout());

  const mobileLogoutBtn = document.getElementById('mobile-logout-btn');
  if (mobileLogoutBtn) mobileLogoutBtn.addEventListener('click', () => API.logout());
}

function renderMobileBottomNav(user, currentPath) {
  let existing = document.querySelector('.mobile-bottom-nav');
  if (existing) existing.remove();

  const nav = document.createElement('nav');
  nav.className = 'mobile-bottom-nav';

  const isHome = currentPath === '/' || currentPath.endsWith('index.html');
  const isCatalog = currentPath.includes('courses.html') || currentPath.includes('course-detail.html');
  const isDashboard = currentPath.includes('dashboard.html');
  const isStudio = currentPath.includes('manage-course.html');
  const isAdmin = currentPath.includes('admin.html');

  let fourthTab = '';
  if (user && user.role === 'admin') {
    fourthTab = `
      <a href="/admin.html" class="mobile-nav-item ${isAdmin ? 'active' : ''}">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>
        <span>Admin</span>
      </a>
    `;
  } else if (user && user.role === 'trainer') {
    fourthTab = `
      <a href="/manage-course.html" class="mobile-nav-item ${isStudio ? 'active' : ''}">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg>
        <span>Studio</span>
      </a>
    `;
  }

  nav.innerHTML = `
    <a href="/index.html" class="mobile-nav-item ${isHome ? 'active' : ''}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path><polyline points="9 22 9 12 15 12 15 22"></polyline></svg>
      <span>Home</span>
    </a>

    <a href="/courses.html" class="mobile-nav-item ${isCatalog ? 'active' : ''}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z"></path><path d="M6 6h10"></path><path d="M6 10h10"></path></svg>
      <span>Catalog</span>
    </a>

    <a href="${user ? '/dashboard.html' : '/login.html'}" class="mobile-nav-item ${isDashboard ? 'active' : ''}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect width="7" height="9" x="3" y="3" rx="1"></rect><rect width="7" height="5" x="14" y="3" rx="1"></rect><rect width="7" height="9" x="14" y="12" rx="1"></rect><rect width="7" height="5" x="3" y="16" rx="1"></rect></svg>
      <span>Dashboard</span>
    </a>

    ${fourthTab}
  `;

  document.body.appendChild(nav);
}

export function requireAuth() {
  const user = API.getUser();
  if (!user || !API.getToken()) {
    window.location.href = `/login.html?redirect=${encodeURIComponent(window.location.pathname)}`;
    return null;
  }
  return user;
}

export function requireRole(...allowedRoles) {
  const user = requireAuth();
  if (!user) return null;

  if (user.role === 'admin') return user;

  if (!allowedRoles.includes(user.role)) {
    UI.alert({ title: 'Access Restricted', message: `This section requires one of [${allowedRoles.join(', ')}] role. Redirecting to your dashboard.`, type: 'warning' })
      .then(() => { window.location.href = '/dashboard.html'; });
    return null;
  }
  return user;
}

document.addEventListener('DOMContentLoaded', () => {
  renderNavbar();
});
