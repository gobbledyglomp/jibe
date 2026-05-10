/**
 * Authenticated fetch helper for dashboard REST.
 */
(function () {
  window.JibeApi = {
    async request(path, options = {}) {
      const token = window.JibeAuth.token();
      const headers = { ...(options.headers || {}) };
      if (token) headers.Authorization = `Bearer ${token}`;
      if (!(options.body instanceof FormData) && !headers['Content-Type']) {
        headers['Content-Type'] = 'application/json';
      }
      const r = await fetch(path, { ...options, headers });
      if (r.status === 401) {
        window.JibeAuth.logout();
        throw new Error('unauthorized');
      }
      return r;
    },
    async json(path, options = {}) {
      const r = await this.request(path, options);
      const data = await r.json().catch(() => ({}));
      if (!r.ok) {
        const msg = data.error || r.statusText;
        throw new Error(msg);
      }
      return data;
    },
  };
})();
