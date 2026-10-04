// Reusable, theme-aware modal dialogs that replace the browser's native alert()/confirm().
// Styles are injected on first use so this module is portable across every page.

export const UI = (() => {
  let overlay = null;
  let keyHandler = null;

  const ICONS = { danger: '🗑️', warning: '⚠️', info: 'ℹ️', success: '✅', question: '❓' };

  function ensureStyles() {
    if (document.getElementById('ui-modal-styles')) return;
    const style = document.createElement('style');
    style.id = 'ui-modal-styles';
    style.textContent = `
      .ui-overlay{position:fixed;inset:0;background:rgba(2,6,23,.6);-webkit-backdrop-filter:blur(4px);backdrop-filter:blur(4px);display:flex;align-items:center;justify-content:center;z-index:10000;opacity:0;transition:opacity .18s ease;padding:20px;}
      .ui-overlay.open{opacity:1;}
      .ui-modal{background:var(--bg-card);border:1px solid var(--border);border-radius:16px;box-shadow:0 24px 60px -12px rgba(0,0,0,.55);max-width:440px;width:100%;padding:28px 26px;text-align:center;transform:translateY(14px) scale(.96);transition:transform .22s cubic-bezier(.16,1,.3,1);}
      .ui-overlay.open .ui-modal{transform:translateY(0) scale(1);}
      .ui-modal-icon{width:58px;height:58px;border-radius:50%;display:grid;place-items:center;font-size:1.7rem;margin:0 auto 16px;}
      .ui-modal-icon.danger{background:rgba(220,38,38,.14);}
      .ui-modal-icon.warning{background:rgba(217,119,6,.16);}
      .ui-modal-icon.info,.ui-modal-icon.question{background:rgba(37,99,235,.14);}
      .ui-modal-icon.success{background:rgba(22,163,74,.14);}
      .ui-modal-title{font-size:1.2rem;font-weight:700;color:var(--text-main);margin:0 0 8px;}
      .ui-modal-message{font-size:.95rem;line-height:1.55;color:var(--text-muted);margin:0;}
      .ui-modal-list{text-align:left;margin:14px auto 0;padding:0 6px 0 22px;max-width:360px;}
      .ui-modal-list li{font-size:.86rem;line-height:1.5;color:var(--text-muted);margin-top:6px;}
      .ui-modal-list li strong{color:var(--text-main);font-weight:600;}
      .ui-modal-actions{display:flex;gap:10px;margin-top:24px;}
      .ui-modal-actions .btn{flex:1;margin:0;}
      @media (max-width: 640px){
        .ui-overlay{align-items:flex-end;padding:0;}
        .ui-modal{max-width:none;border-radius:20px 20px 0 0;border-left:none;border-right:none;border-bottom:none;padding:22px 18px calc(22px + env(safe-area-inset-bottom,0px));max-height:90vh;overflow-y:auto;-webkit-overflow-scrolling:touch;transform:translateY(100%);transition:transform .26s cubic-bezier(.16,1,.3,1);}
        .ui-overlay.open .ui-modal{transform:translateY(0);}
        .ui-modal::before{content:"";display:block;width:44px;height:4px;border-radius:9999px;background:var(--border-focus);opacity:.65;margin:0 auto 16px;}
        .ui-modal-icon{width:52px;height:52px;font-size:1.5rem;margin-bottom:12px;}
        .ui-modal-title{font-size:1.12rem;}
        .ui-modal-list{max-width:none;margin-left:0;margin-right:0;padding-left:22px;}
        .ui-modal-actions{flex-direction:column-reverse;gap:8px;margin-top:22px;}
        .ui-modal-actions .btn{min-height:50px;font-size:1rem;}
      }
    `;
    document.head.appendChild(style);
  }

  function teardown() {
    if (!overlay) return;
    const el = overlay;
    overlay = null;
    if (keyHandler) { document.removeEventListener('keydown', keyHandler); keyHandler = null; }
    el.classList.remove('open');
    setTimeout(() => el.remove(), 180);
  }

  function open(opts) {
    ensureStyles();
    if (overlay) teardown();
    const { title = 'Are you sure?', message = '', items = [], type = 'question', confirmText = 'Confirm', cancelText = 'Cancel', showCancel = true } = opts;

    return new Promise((resolve) => {
      overlay = document.createElement('div');
      overlay.className = 'ui-overlay';
      overlay.setAttribute('role', 'dialog');
      overlay.setAttribute('aria-modal', 'true');

      const modal = document.createElement('div');
      modal.className = 'ui-modal';

      const icon = document.createElement('div');
      icon.className = 'ui-modal-icon ' + type;
      icon.textContent = ICONS[type] || ICONS.info;

      const h = document.createElement('h3');
      h.className = 'ui-modal-title';
      h.textContent = title;

      const p = document.createElement('p');
      p.className = 'ui-modal-message';
      p.textContent = message;

      const actions = document.createElement('div');
      actions.className = 'ui-modal-actions';

      modal.append(icon, h, p);
      const list = Array.isArray(items) ? items.filter(Boolean) : [];
      if (list.length) {
        const ul = document.createElement('ul');
        ul.className = 'ui-modal-list';
        list.forEach(item => {
          const li = document.createElement('li');
          if (typeof item === 'string') li.textContent = item;
          else {
            const strong = document.createElement('strong');
            strong.textContent = item.label || '';
            li.append(strong, document.createTextNode(item.text ? ' ' + item.text : ''));
          }
          ul.appendChild(li);
        });
        modal.appendChild(ul);
      }
      modal.appendChild(actions);

      let cancelBtn = null;
      if (showCancel) {
        cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'btn btn-secondary';
        cancelBtn.textContent = cancelText;
        actions.appendChild(cancelBtn);
      }
      const confirmBtn = document.createElement('button');
      confirmBtn.type = 'button';
      confirmBtn.className = 'btn ' + (type === 'danger' ? 'btn-danger' : 'btn-primary');
      confirmBtn.textContent = confirmText;
      actions.appendChild(confirmBtn);

      overlay.appendChild(modal);
      document.body.appendChild(overlay);
      requestAnimationFrame(() => overlay.classList.add('open'));

      const done = (val) => { teardown(); resolve(val); };
      confirmBtn.onclick = () => done(true);
      if (cancelBtn) cancelBtn.onclick = () => done(false);
      overlay.addEventListener('click', (e) => { if (e.target === overlay) done(!showCancel); });

      keyHandler = (e) => {
        if (e.key === 'Escape') done(false);
        else if (e.key === 'Enter') { e.preventDefault(); done(true); }
      };
      document.addEventListener('keydown', keyHandler);
      confirmBtn.focus();
    });
  }

  const normalize = (o) => (typeof o === 'string' ? { message: o } : (o || {}));

  return {
    confirm(o) {
      return open(Object.assign({ type: 'question', title: 'Are you sure?', confirmText: 'Confirm' }, normalize(o)));
    },
    alert(o) {
      return open(Object.assign({ type: 'info', title: 'Notice', confirmText: 'OK', showCancel: false }, normalize(o))).then(() => true);
    }
  };
})();

export default UI;
