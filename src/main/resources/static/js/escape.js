/**
 * Escapes a value for safe interpolation into an innerHTML template.
 * Every string that originates in the database must pass through here, including inside attributes.
 */
export function esc(value) {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * Renders a stored date-only value (due_date) as the day it says, not the day before it.
 * new Date('2026-10-12') means midnight UTC, so toLocaleDateString() shifts it back a day for
 * anyone west of Greenwich. Timestamps that carry an offset do not need this.
 */
export function formatDate(value) {
  if (value === null || value === undefined || value === '') return '';
  const text = String(value);
  const match = text.match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (!match) return new Date(text).toLocaleDateString();
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])).toLocaleDateString();
}

/**
 * Renders authored HTML after removing executable constructs.
 * Needed for lesson bodies saved before the server-side sanitiser existed.
 */
export function scrubHtml(html) {
  if (html === null || html === undefined) return '';
  const template = document.createElement('template');
  template.innerHTML = String(html);
  template.content.querySelectorAll('script, style, iframe, object, embed, form, base, link, meta, svg, math').forEach(node => node.remove());
  template.content.querySelectorAll('*').forEach(node => {
    for (const attribute of Array.from(node.attributes)) {
      const name = attribute.name.toLowerCase();
      if (name.startsWith('on')) {
        node.removeAttribute(attribute.name);
      } else if ((name === 'href' || name === 'src') && !/^(https?:|mailto:|#|\/)/i.test(attribute.value.trim())) {
        node.removeAttribute(attribute.name);
      }
    }
  });
  return template.innerHTML;
}
