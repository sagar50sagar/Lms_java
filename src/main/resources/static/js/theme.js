// Global night-mode toggle — self-injects on every page that imports it
const THEME_KEY = 'lms_theme';

const ICONS = {
  dark: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4"></circle><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"></path></svg>',
  light: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"></path></svg>',
};

function currentTheme() {
  return localStorage.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light';
}

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
}

// Styles live here so the toggle also works on pages without style.css (certificate view)
document.head.insertAdjacentHTML('beforeend', `
<style>
.theme-toggle {
  width: 38px;
  height: 38px;
  border-radius: 9999px;
  border: 1px solid var(--border, #e2e8f0);
  background: var(--bg-card, #ffffff);
  color: var(--text-main, #1e293b);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  margin-left: 10px;
  padding: 0;
}
.theme-toggle:hover { border-color: var(--primary, #2563eb); }
.theme-toggle-floating {
  position: fixed;
  top: 11px;
  right: 14px;
  z-index: 60;
  background: var(--bg-card, #ffffff);
  box-shadow: var(--shadow-sm, 0 1px 2px rgba(0,0,0,.05));
}
</style>
`);

applyTheme(currentTheme());

document.addEventListener('DOMContentLoaded', () => {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'theme-toggle';
  btn.title = 'Toggle night mode';
  btn.setAttribute('aria-label', 'Toggle night mode');

  const sync = () => { btn.innerHTML = ICONS[currentTheme()]; };
  btn.addEventListener('click', () => {
    localStorage.setItem(THEME_KEY, currentTheme() === 'dark' ? 'light' : 'dark');
    applyTheme(currentTheme());
    sync();
  });
  sync();

  const nav = document.querySelector('.nav-container');
  const playerBar = document.querySelector('.player-topbar');
  if (nav) {
    nav.appendChild(btn);
  } else if (playerBar) {
    // Course player: keep the toggle with the progress/certificate group on the right
    playerBar.lastElementChild.appendChild(btn);
  } else {
    btn.classList.add('theme-toggle-floating');
    document.body.appendChild(btn);
  }
});
