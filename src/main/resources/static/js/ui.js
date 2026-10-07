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
      .ui-field{margin-top:18px;text-align:left;}
      .ui-field-label{display:block;font-size:.82rem;font-weight:600;color:var(--text-main);margin-bottom:6px;}
      .ui-input-wrap{position:relative;display:flex;align-items:center;}
      .ui-input{width:100%;padding:11px 12px;border:1px solid var(--border);border-radius:10px;font-size:1rem;font-family:inherit;background:var(--bg-card);color:var(--text-main);transition:border-color .15s,box-shadow .15s;}
      .ui-input:focus{outline:none;border-color:var(--primary);box-shadow:0 0 0 3px rgba(37,99,235,.15);}
      .ui-input.has-toggle{padding-right:44px;}
      .ui-input-btn{position:absolute;right:6px;background:none;border:none;cursor:pointer;font-size:1.05rem;color:var(--text-muted);width:32px;height:32px;display:grid;place-items:center;border-radius:8px;transition:background .15s,color .15s;}
      .ui-input-btn:hover{background:var(--primary-light);color:var(--primary);}
      .ui-hint{font-size:.76rem;color:var(--text-muted);margin-top:6px;min-height:1em;}
      .ui-hint.error{color:var(--danger);}
      .ui-hint.ok{color:var(--success);}
      .ui-generate-row{display:flex;gap:8px;margin-top:10px;}
      .ui-generate-row .btn{flex:1;margin:0;}
      @media (max-width: 640px){
        .ui-input{font-size:16px;}
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

  function generatePassword(){
    const sets=['ABCDEFGHJKLMNPQRSTUVWXYZ','abcdefghijkmnopqrstuvwxyz','23456789','!@#$%^&*()-_=+'];
    const pick=s=>s[crypto.getRandomValues(new Uint32Array(1))[0]%s.length];
    const chars=[];
    sets.forEach(s=>chars.push(pick(s)));
    const all=sets.join('');
    while(chars.length<14) chars.push(pick(all));
    for(let i=chars.length-1;i>0;i--){const j=crypto.getRandomValues(new Uint32Array(1))[0]%(i+1);[chars[i],chars[j]]=[chars[j],chars[i]];}
    return chars.join('');
  }

  function promptModal(opts){
    ensureStyles();
    if(overlay) teardown();
    const { title='Enter a value', message='', label='', placeholder='', inputType='text',
            minLength=0, confirmText='Confirm', cancelText='Cancel', showGenerate=false, hint='' } = normalize(opts);

    return new Promise((resolve)=>{
      overlay=document.createElement('div');
      overlay.className='ui-overlay';
      overlay.setAttribute('role','dialog');
      overlay.setAttribute('aria-modal','true');

      const modal=document.createElement('div');
      modal.className='ui-modal';

      const icon=document.createElement('div');
      icon.className='ui-modal-icon info';
      icon.textContent=ICONS.info;

      const h=document.createElement('h3');
      h.className='ui-modal-title';
      h.textContent=title;

      const p=document.createElement('p');
      p.className='ui-modal-message';
      p.textContent=message;

      const field=document.createElement('div');
      field.className='ui-field';
      const lbl=document.createElement('label');
      lbl.className='ui-field-label';
      lbl.textContent=label;
      lbl.htmlFor='ui-prompt-input';
      const wrap=document.createElement('div');
      wrap.className='ui-input-wrap';
      const input=document.createElement('input');
      input.className='ui-input';
      input.id='ui-prompt-input';
      input.type=inputType;
      input.placeholder=placeholder;
      input.autocomplete='new-password';
      wrap.appendChild(input);

      const isPassword=inputType==='password';
      if(isPassword){
        input.classList.add('has-toggle');
        const toggle=document.createElement('button');
        toggle.type='button';
        toggle.className='ui-input-btn';
        toggle.textContent='👁';
        toggle.title='Show / hide password';
        toggle.onclick=()=>{ const s=input.type==='password'; input.type=s?'text':'password'; toggle.textContent=s?'🙈':''; };
        wrap.appendChild(toggle);
      }
      field.append(lbl,wrap);

      if(showGenerate){
        const row=document.createElement('div');
        row.className='ui-generate-row';
        const gen=document.createElement('button');
        gen.type='button';
        gen.className='btn btn-secondary btn-sm';
        gen.textContent='🎲 Generate strong';
        const copy=document.createElement('button');
        copy.type='button';
        copy.className='btn btn-secondary btn-sm';
        copy.textContent='📋 Copy';
        gen.onclick=()=>{ input.value=generatePassword(); input.type='text'; toggleEye(); validate(); };
        copy.onclick=async()=>{ if(!input.value) return; try{ await navigator.clipboard.writeText(input.value); copy.textContent='✅ Copied'; setTimeout(()=>copy.textContent='📋 Copy',1500);}catch(e){} };
        row.append(gen,copy);
        field.appendChild(row);
      }

      const hintEl=document.createElement('div');
      hintEl.className='ui-hint';
      hintEl.textContent=hint;
      field.appendChild(hintEl);

      function toggleEye(){
        const t=wrap.querySelector('.ui-input-btn');
        if(t) t.textContent=input.type==='password'?'👁':'';
      }

      const actions=document.createElement('div');
      actions.className='ui-modal-actions';
      const cancelBtn=document.createElement('button');
      cancelBtn.type='button';
      cancelBtn.className='btn btn-secondary';
      cancelBtn.textContent=cancelText;
      const confirmBtn=document.createElement('button');
      confirmBtn.type='button';
      confirmBtn.className='btn btn-primary';
      confirmBtn.textContent=confirmText;
      actions.append(cancelBtn,confirmBtn);

      modal.append(icon,h,p,field,actions);
      overlay.appendChild(modal);
      document.body.appendChild(overlay);
      requestAnimationFrame(()=>overlay.classList.add('open'));

      function validate(){
        const v=input.value;
        if(minLength && v.length && v.length<minLength){
          hintEl.className='ui-hint error';
          hintEl.textContent=`At least ${minLength} characters required.`;
          confirmBtn.disabled=true;
          return false;
        }
        hintEl.className='ui-hint';
        hintEl.textContent=hint;
        confirmBtn.disabled=!v;
        return true;
      }
      confirmBtn.disabled=true;
      input.addEventListener('input',validate);

      const done=(val)=>{ teardown(); resolve(val); };
      confirmBtn.onclick=()=>{ if(input.value && validate()) done(input.value); };
      cancelBtn.onclick=()=>done(null);
      overlay.addEventListener('click',(e)=>{ if(e.target===overlay) done(null); });

      keyHandler=(e)=>{
        if(e.key==='Escape') done(null);
        else if(e.key==='Enter'){ e.preventDefault(); if(input.value && validate()) done(input.value); }
      };
      document.addEventListener('keydown',keyHandler);
      setTimeout(()=>input.focus(),50);
    });
  }

  const normalize = (o) => (typeof o === 'string' ? { message: o } : (o || {}));

  return {
    confirm(o) {
      return open(Object.assign({ type: 'question', title: 'Are you sure?', confirmText: 'Confirm' }, normalize(o)));
    },
    alert(o) {
      return open(Object.assign({ type: 'info', title: 'Notice', confirmText: 'OK', showCancel: false }, normalize(o))).then(() => true);
    },
    prompt(o) {
      return promptModal(o);
    }
  };
})();

export default UI;
