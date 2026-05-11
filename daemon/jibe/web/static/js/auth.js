/**
 * Minimal session helpers — dashboard loads this before app logic.
 */
(function () {
  const KEYS = ['jibe_token', 'jibe_role', 'jibe_username', 'jibe_expires_at'];

  function parseExpires() {
    const raw = sessionStorage.getItem('jibe_expires_at');
    if (!raw) return null;
    const n = Number(raw);
    return Number.isFinite(n) ? n : null;
  }

  window.JibeAuth = {
    tokenPresent() {
      const t = sessionStorage.getItem('jibe_token');
      const exp = parseExpires();
      if (!t) return false;
      if (exp != null && Date.now() / 1000 > exp - 30) return false;
      return true;
    },
    redirectIfUnauthorized() {
      if (!this.tokenPresent()) {
        sessionStorage.removeItem('jibe_token');
        sessionStorage.removeItem('jibe_role');
        sessionStorage.removeItem('jibe_username');
        sessionStorage.removeItem('jibe_expires_at');
        window.location.href = '/web/index.html';
      }
    },
    logout() {
      KEYS.forEach((k) => sessionStorage.removeItem(k));
      window.location.href = '/web/index.html';
    },
    role() {
      return sessionStorage.getItem('jibe_role') || '';
    },
    username() {
      return sessionStorage.getItem('jibe_username') || '';
    },
    token() {
      return sessionStorage.getItem('jibe_token') || '';
    },
  };
})();
