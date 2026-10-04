const API_BASE = '/api';

export const API = {
  getToken() {
    return localStorage.getItem('lms_token');
  },

  setSession(token, user) {
    localStorage.setItem('lms_token', token);
    localStorage.setItem('lms_user', JSON.stringify(user));
  },

  getUser() {
    const raw = localStorage.getItem('lms_user');
    try {
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  },

  clearSession() {
    localStorage.removeItem('lms_token');
    localStorage.removeItem('lms_user');
  },

  logout() {
    this.clearSession();
    window.location.href = '/login.html';
  },

  async request(endpoint, options = {}) {
    const url = endpoint.startsWith('http') ? endpoint : `${API_BASE}${endpoint}`;
    const token = this.getToken();

    const headers = {
      'Content-Type': 'application/json',
      ...options.headers,
    };

    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    try {
      const response = await fetch(url, {
        ...options,
        headers,
      });

      const data = await response.json().catch(() => ({
        success: false,
        message: 'Invalid response from server.',
      }));

      if (!response.ok) {
        if (response.status === 401 && !url.includes('/auth/login')) {
          this.logout();
        }
        throw new Error(data.message || 'An error occurred during request.');
      }

      return data;
    } catch (error) {
      console.error(`API Error [${endpoint}]:`, error);
      throw error;
    }
  },

  get(endpoint) {
    return this.request(endpoint, { method: 'GET' });
  },

  post(endpoint, body) {
    return this.request(endpoint, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },

  put(endpoint, body) {
    return this.request(endpoint, {
      method: 'PUT',
      body: JSON.stringify(body),
    });
  },

  delete(endpoint) {
    return this.request(endpoint, { method: 'DELETE' });
  },

  async downloadFile(endpoint, filename) {
    const url = endpoint.startsWith('http') ? endpoint : `${API_BASE}${endpoint}`;
    const headers = {};
    const token = this.getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const response = await fetch(url, { headers });
    if (!response.ok) throw new Error('Download failed.');
    const blob = await response.blob();
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(link.href);
  },

  downloadCertificate(code) {
    return this.downloadFile(`/progress/certificates/${code}/pdf`, `Certificate-${code}.pdf`);
  },

  showToast(message, type = 'info') {
    const existing = document.getElementById('toast-notification');
    if (existing) existing.remove();

    const toast = document.createElement('div');
    toast.id = 'toast-notification';
    // Phones: full-width banner clear of the bottom nav. Larger screens: bottom-right card.
    const phone = window.matchMedia('(max-width: 640px)').matches;
    toast.style.position = 'fixed';
    toast.style.padding = phone ? '12px 16px' : '12px 20px';
    toast.style.borderRadius = '8px';
    toast.style.fontSize = '0.9rem';
    toast.style.fontWeight = '600';
    toast.style.color = '#ffffff';
    toast.style.boxShadow = '0 10px 15px -3px rgba(0,0,0,0.2)';
    toast.style.zIndex = '9999';
    toast.style.transition = 'opacity 0.3s ease';
    toast.style.maxWidth = phone ? 'none' : '360px';
    if (phone) {
      toast.style.left = '12px';
      toast.style.right = '12px';
      toast.style.bottom = `calc(var(--bottom-nav-height, 64px) + env(safe-area-inset-bottom, 0px) + 12px)`;
    } else {
      toast.style.right = '24px';
      toast.style.bottom = '24px';
    }

    if (type === 'success') {
      toast.style.background = '#16a34a';
    } else if (type === 'error') {
      toast.style.background = '#dc2626';
    } else if (type === 'warning') {
      toast.style.background = '#d97706';
    } else {
      toast.style.background = '#2563eb';
    }

    toast.textContent = message;
    document.body.appendChild(toast);

    setTimeout(() => {
      toast.style.opacity = '0';
      setTimeout(() => toast.remove(), 300);
    }, 4000);
  },
};

export default API;
