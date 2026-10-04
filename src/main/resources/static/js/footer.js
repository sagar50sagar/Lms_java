// Global site footer — self-injects on every page that imports it
const SITE = 'qtconsultancy.in';

export function renderFooter() {
  if (document.querySelector('.site-footer')) return;

  const footer = document.createElement('footer');
  footer.className = 'site-footer';
  footer.innerHTML = `
    <div class="footer-top">
      <div class="container footer-grid">
        <div class="footer-col footer-brand">
          <a href="/index.html" class="footer-logo-wrap">
            <img class="footer-logo" src="/images/logo.jpeg" alt="QT Consultancy logo">
            <span class="footer-brand-name">QT Consultancy<div class="footer-brand-tag">Trusted Manpower Supplier</div></span>
          </a>
          <p class="footer-text">
            Corporate Training &amp; Compliance LMS — empowering our workforce with
            certified learning, measurable compliance, and career-ready skills.
          </p>
          <div class="footer-motto">Right Candidate &nbsp;|&nbsp; Right Time &nbsp;|&nbsp; Right Company</div>
        </div>

        <div class="footer-col">
          <h4 class="footer-heading">Quick Links</h4>
          <ul class="footer-links">
            <li><a href="/index.html">Overview</a></li>
            <li><a href="/courses.html">Training Catalog</a></li>
            <li><a href="/dashboard.html">My Dashboard</a></li>
            <li><a href="/login.html">Employee Sign In</a></li>
          </ul>
        </div>

        <div class="footer-col">
          <h4 class="footer-heading">Contact Us</h4>
          <ul class="footer-contact">
            <li><a href="https://${SITE}" target="_blank" rel="noopener">🌐 ${SITE}</a></li>
            <li><a href="mailto:hr@${SITE}">✉️ hr@${SITE}</a></li>
            <li><a href="tel:+917830899085">📞 +91 78308 99085</a></li>
            <li>📍 Plot no 5, New Shambhu Nagar Road, Delhi Road, Near Transport Nagar,<br>Mohokampur Phase 1, Meerut, UP – 250002</li>
          </ul>
        </div>
      </div>
    </div>
    <div class="footer-bottom">
      <div class="container footer-bottom-inner">
        <span>© ${new Date().getFullYear()} QT Consultancy (OPC) Pvt Ltd • CIN: U78100UP2025OPC218928</span>
        <a href="https://${SITE}" target="_blank" rel="noopener">${SITE}</a>
      </div>
    </div>
  `;
  document.body.appendChild(footer);
}

document.addEventListener('DOMContentLoaded', renderFooter);
