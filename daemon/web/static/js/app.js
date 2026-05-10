(function () {
  const POLL_MS = 5000;

  async function loadVersion() {
    try {
      const r = await fetch('/health');
      const d = await r.json();
      const elv = document.getElementById('dash-version');
      if (elv) elv.textContent = 'v' + (d.version || '');
    } catch {
      /* ignore */
    }
  }

  function setNavActive(hash) {
    document.querySelectorAll('aside nav a').forEach((a) => {
      a.classList.toggle('active', a.dataset.hash === hash);
    });
  }

  function buildNav(role) {
    const nav = document.getElementById('nav');
    nav.innerHTML = '';
    const items = [
      { hash: 'devices', label: 'Devices' },
      { hash: 'history', label: 'History' },
      { hash: 'stats', label: 'Statistics' },
    ];
    if (role === 'admin') items.push({ hash: 'daemon', label: 'Daemon' });
    items.forEach((item) => {
      const a = document.createElement('a');
      a.href = '#' + item.hash;
      a.textContent = item.label;
      a.dataset.hash = item.hash;
      nav.appendChild(a);
    });
  }

  let pollDevicesTimer = null;
  function stopPollDevices() {
    if (pollDevicesTimer) clearInterval(pollDevicesTimer);
    pollDevicesTimer = null;
  }

  function esc(s) {
    const d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
  }

  function relativeTime(iso) {
    if (!iso) return '—';
    const t = new Date(iso).getTime();
    if (Number.isNaN(t)) return iso;
    const sec = Math.floor((Date.now() - t) / 1000);
    if (sec < 60) return sec + 's ago';
    const min = Math.floor(sec / 60);
    if (min < 60) return min + 'm ago';
    const h = Math.floor(min / 60);
    if (h < 48) return h + 'h ago';
    return iso.slice(0, 16).replace('T', ' ');
  }

  async function renderDevices(root, role) {
    stopPollDevices();
    root.innerHTML =
      '<h1>Devices</h1><div id="dev-wrap"><table class="data"><thead><tr><th></th><th>Name</th><th>Last seen</th><th>Paired</th>' +
      (role === 'admin' ? '<th></th>' : '') +
      '</tr></thead><tbody id="dev-body"></tbody></table></div>';

    const tbody = root.querySelector('#dev-body');

    async function refresh() {
      const data = await window.JibeApi.json('/api/devices');
      tbody.innerHTML = '';
      data.devices.forEach((d) => {
        const tr = document.createElement('tr');
        const dot = d.online ? 'dot-online' : 'dot-offline';
        tr.innerHTML =
          '<td><span class="' +
          dot +
          '" title="' +
          (d.online ? 'online' : 'offline') +
          '"></span></td>' +
          '<td class="dev-name mono" data-id="' +
          esc(d.id) +
          '">' +
          esc(d.name) +
          '</td>' +
          '<td>' +
          relativeTime(d.last_seen) +
          '</td>' +
          '<td class="mono">' +
          esc(String(d.paired_at || '').slice(0, 16)) +
          '</td>';
        if (role === 'admin') {
          const td = document.createElement('td');
          const btn = document.createElement('button');
          btn.className = 'btn btn-danger btn-sm';
          btn.textContent = 'Revoke';
          btn.addEventListener('click', async () => {
            if (!confirm('Revoke pairing for ' + d.name + '?')) return;
            await window.JibeApi.request('/api/devices/' + encodeURIComponent(d.id), {
              method: 'DELETE',
            });
            refresh();
          });
          td.appendChild(btn);
          tr.appendChild(td);
        }
        tbody.appendChild(tr);
      });

      if (role === 'admin') {
        tbody.querySelectorAll('.dev-name').forEach((cell) => {
          cell.style.cursor = 'pointer';
          cell.title = 'Click to rename';
          cell.addEventListener('click', async () => {
            const id = cell.dataset.id;
            const cur = cell.textContent;
            const nv = prompt('New device name', cur);
            if (!nv || nv === cur) return;
            await window.JibeApi.json('/api/devices/' + encodeURIComponent(id), {
              method: 'PATCH',
              body: JSON.stringify({ name: nv }),
            });
            refresh();
          });
        });
      }
    }

    await refresh();
    pollDevicesTimer = setInterval(refresh, POLL_MS);
  }

  async function renderHistory(root) {
    stopPollDevices();
    let tab = 'transfers';
    let page = 1;
    const perPage = 25;

    root.innerHTML =
      '<h1>History</h1>' +
      '<div class="tabs" id="hist-tabs">' +
      '<button type="button" data-t="transfers" class="active">Transfers</button>' +
      '<button type="button" data-t="clipboard">Clipboard</button>' +
      '<button type="button" data-t="notifications">Notifications</button>' +
      '</div>' +
      '<div class="toolbar">' +
      '<div class="field"><label class="small">Device / source id</label><input id="hf-device" class="mono" /></div>' +
      '<div class="field"><label class="small">From (ISO)</label><input id="hf-from" placeholder="2026-01-01" /></div>' +
      '<div class="field"><label class="small">To (ISO)</label><input id="hf-to" /></div>' +
      '<div class="field" id="hf-status-wrap"><label class="small">Status</label><select id="hf-status"><option value="">any</option>' +
      '<option>in_progress</option><option>completed</option><option>cancelled</option><option>failed</option></select></div>' +
      '<div class="field" id="hf-dir-wrap"><label class="small">Direction</label><select id="hf-dir"><option value="">any</option>' +
      '<option>incoming</option><option>outgoing</option></select></div>' +
      '<div class="field" id="hf-app-wrap" style="display:none"><label class="small">App contains</label><input id="hf-app" /></div>' +
      '<button type="button" class="btn btn-sm" id="hf-go">Apply</button>' +
      '</div>' +
      '<div id="hist-table-wrap"></div>' +
      '<div style="margin-top:0.75rem;display:flex;gap:0.5rem;align-items:center;" id="hist-pager"></div>';

    function syncFilters() {
      root.querySelector('#hf-status-wrap').style.display =
        tab === 'transfers' ? '' : 'none';
      root.querySelector('#hf-dir-wrap').style.display =
        tab === 'clipboard' || tab === 'transfers' ? '' : 'none';
      root.querySelector('#hf-app-wrap').style.display =
        tab === 'notifications' ? '' : 'none';
    }

    async function load() {
      syncFilters();
      const dev = root.querySelector('#hf-device').value.trim();
      const from = root.querySelector('#hf-from').value.trim();
      const to = root.querySelector('#hf-to').value.trim();
      const qs = new URLSearchParams({ page: String(page), per_page: String(perPage) });
      if (from) qs.set('from', from);
      if (to) qs.set('to', to);

      let path = '';
      if (tab === 'transfers') {
        if (dev) qs.set('device_id', dev);
        const st = root.querySelector('#hf-status').value;
        if (st) qs.set('status', st);
        path = '/api/history/transfers?' + qs.toString();
      } else if (tab === 'clipboard') {
        if (dev) qs.set('device_id', dev);
        const dir = root.querySelector('#hf-dir').value;
        if (dir) qs.set('direction', dir);
        path = '/api/history/clipboard?' + qs.toString();
      } else {
        if (dev) qs.set('device_id', dev);
        const ap = root.querySelector('#hf-app').value.trim();
        if (ap) qs.set('app', ap);
        path = '/api/history/notifications?' + qs.toString();
      }

      const data = await window.JibeApi.json(path);
      const wrap = root.querySelector('#hist-table-wrap');
      let html = '<table class="data"><thead><tr>';
      const rows = [];
      if (tab === 'transfers') {
        html += '<th>File</th><th>Size</th><th>Status</th><th>Started</th></tr></thead><tbody>';
        data.items.forEach((it) => {
          rows.push(it);
          html +=
            '<tr data-i="' +
            rows.length +
            '"><td class="mono">' +
            esc(it.filename) +
            '</td><td>' +
            it.size +
            '</td><td>' +
            esc(it.status) +
            '</td><td>' +
            esc(it.started_at.slice(0, 19)) +
            '</td></tr>';
        });
      } else if (tab === 'clipboard') {
        html += '<th>Source</th><th>Dir</th><th>Preview</th><th>When</th></tr></thead><tbody>';
        data.items.forEach((it) => {
          rows.push(it);
          const prev =
            it.content.length > 80 ? it.content.slice(0, 80) + '…' : it.content;
          html +=
            '<tr data-i="' +
            rows.length +
            '"><td class="mono">' +
            esc(it.source) +
            '</td><td>' +
            esc(it.direction) +
            '</td><td>' +
            esc(prev) +
            '</td><td>' +
            esc(it.created_at.slice(0, 19)) +
            '</td></tr>';
        });
      } else {
        html += '<th>App</th><th>Title</th><th>Received</th></tr></thead><tbody>';
        data.items.forEach((it) => {
          rows.push(it);
          html +=
            '<tr data-i="' +
            rows.length +
            '"><td class="mono">' +
            esc(it.app) +
            '</td><td>' +
            esc(it.title) +
            '</td><td>' +
            esc(it.received_at.slice(0, 19)) +
            '</td></tr>';
        });
      }
      html += '</tbody></table>';
      wrap.innerHTML = html;

      wrap.querySelectorAll('tbody tr').forEach((tr) => {
        tr.style.cursor = 'pointer';
        tr.addEventListener('click', () => {
          const it = rows[Number(tr.dataset.i) - 1];
          const next = tr.nextElementSibling;
          if (next && next.classList.contains('hist-detail-row')) {
            next.remove();
            return;
          }
          wrap.querySelectorAll('.hist-detail-row').forEach((r) => r.remove());
          const nr = document.createElement('tr');
          nr.className = 'hist-detail-row';
          nr.innerHTML = '<td colspan="6"><div class="row-detail mono"></div></td>';
          nr.querySelector('.row-detail').textContent = JSON.stringify(it, null, 2);
          tr.after(nr);
        });
      });

      const pager = root.querySelector('#hist-pager');
      pager.innerHTML =
        '<span style="color:var(--muted);font-size:0.85rem;">Page ' +
        data.page +
        ' / ' +
        data.pages +
        ' (' +
        data.total +
        ')</span>' +
        '<button type="button" class="btn btn-sm" id="hp-prev">Prev</button>' +
        '<button type="button" class="btn btn-sm" id="hp-next">Next</button>';
      pager.querySelector('#hp-prev').onclick = () => {
        page = Math.max(1, page - 1);
        load();
      };
      pager.querySelector('#hp-next').onclick = () => {
        page = Math.min(data.pages, page + 1);
        load();
      };
    }

    root.querySelectorAll('#hist-tabs button').forEach((b) => {
      b.addEventListener('click', () => {
        root.querySelectorAll('#hist-tabs button').forEach((x) =>
          x.classList.toggle('active', x === b),
        );
        tab = b.dataset.t;
        page = 1;
        load();
      });
    });
    root.querySelector('#hf-go').onclick = () => {
      page = 1;
      load();
    };
    await load();
  }

  function renderStatsChart(rootEl, activity) {
    const W = 640;
    const H = 220;
    const pad = 36;
    const n = Math.max(activity.length, 1);
    const slot = (W - pad * 2) / n;
    const barW = Math.max(4, slot * 0.28);
    let maxV = 1;
    activity.forEach((d) => {
      maxV = Math.max(maxV, d.transfers + d.clipboard);
    });
    const svgNS = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(svgNS, 'svg');
    svg.setAttribute('width', String(W));
    svg.setAttribute('height', String(H));
    svg.style.display = 'block';
    activity.forEach((day, i) => {
      const x0 = pad + i * slot + (slot - barW * 2) / 2;
      const h1 = ((day.transfers / maxV) * (H - pad - 24)) | 0;
      const h2 = ((day.clipboard / maxV) * (H - pad - 24)) | 0;
      const r1 = document.createElementNS(svgNS, 'rect');
      r1.setAttribute('x', String(x0));
      r1.setAttribute('y', String(H - pad - h1));
      r1.setAttribute('width', String(barW - 1));
      r1.setAttribute('height', String(Math.max(h1, 1)));
      r1.setAttribute('fill', '#58a6ff');
      svg.appendChild(r1);
      const r2 = document.createElementNS(svgNS, 'rect');
      r2.setAttribute('x', String(x0 + barW));
      r2.setAttribute('y', String(H - pad - h2));
      r2.setAttribute('width', String(barW - 1));
      r2.setAttribute('height', String(Math.max(h2, 1)));
      r2.setAttribute('fill', '#3fb950');
      svg.appendChild(r2);
      const tx = document.createElementNS(svgNS, 'text');
      tx.setAttribute('x', String(x0 + barW));
      tx.setAttribute('y', String(H - 8));
      tx.setAttribute('text-anchor', 'middle');
      tx.setAttribute('fill', '#8b949e');
      tx.setAttribute('font-size', '10');
      tx.textContent = day.date.slice(5);
      svg.appendChild(tx);
    });
    const leg = document.createElement('div');
    leg.style.fontSize = '0.75rem';
    leg.style.color = 'var(--muted)';
    leg.innerHTML =
      '<span style="color:#58a6ff">■</span> transfers &nbsp; <span style="color:#3fb950">■</span> clipboard';
    rootEl.appendChild(leg);
    rootEl.appendChild(svg);
  }

  async function renderStats(root) {
    stopPollDevices();
    const data = await window.JibeApi.json('/api/stats');
    const t = data.totals;
    root.innerHTML =
      '<h1>Statistics</h1>' +
      '<div class="summary-grid">' +
      '<div class="summary-card"><div class="lbl">Transfers completed</div><div class="val">' +
      t.transfers_completed +
      '</div></div>' +
      '<div class="summary-card"><div class="lbl">Bytes transferred</div><div class="val mono">' +
      t.bytes_transferred +
      '</div></div>' +
      '<div class="summary-card"><div class="lbl">Clipboard events</div><div class="val">' +
      t.clipboard_events +
      '</div></div>' +
      '<div class="summary-card"><div class="lbl">Notifications</div><div class="val">' +
      t.notifications +
      '</div></div>' +
      '</div>' +
      '<div class="panel" style="margin-top:1rem;"><strong>Most active device</strong>' +
      '<div id="top-dev" style="margin-top:0.5rem;font-size:0.9rem;"></div></div>' +
      '<div class="panel chart-wrap"><strong>Activity (7 days)</strong><div id="chart"></div></div>';

    const td = data.top_device;
    root.querySelector('#top-dev').innerHTML = td
      ? esc(td.name || td.id) + ' — <span class="mono">' + td.events + ' events</span>'
      : '<span style="color:var(--muted)">No data yet</span>';

    renderStatsChart(root.querySelector('#chart'), data.activity_last_7_days);
  }

  async function renderDaemon(root) {
    stopPollDevices();
    root.innerHTML =
      '<h1>Daemon</h1>' +
      '<div class="panel" style="margin-bottom:1rem;"><div id="daemon-info"></div></div>' +
      '<div class="panel" style="margin-bottom:1rem;">' +
      '<strong>Pairing</strong>' +
      '<div id="pair-pin" class="mono" style="font-size:2rem;margin:0.5rem 0;"></div>' +
      '<div id="pair-meta" style="font-size:0.85rem;color:var(--muted);margin-bottom:0.75rem;"></div>' +
      '<button type="button" class="btn btn-primary btn-sm" id="pair-start">Start pairing</button> ' +
      '<button type="button" class="btn btn-sm" id="pair-stop">Stop pairing</button>' +
      '</div>' +
      '<div class="panel">' +
      '<strong>TLS</strong>' +
      '<div id="tls-fp" class="mono" style="margin:0.5rem 0;font-size:0.8rem;word-break:break-all;"></div>' +
      '<button type="button" class="btn btn-danger btn-sm" id="tls-regen">Regenerate certificate</button>' +
      '<div id="tls-note" style="margin-top:0.5rem;display:none;" class="banner-warn"></div>' +
      '</div>';

    async function refreshInfo() {
      const st = await window.JibeApi.json('/api/daemon/status');
      root.querySelector('#daemon-info').innerHTML =
        'Version <span class="mono">' +
        esc(st.version) +
        '</span><br/>Uptime <span class="mono">' +
        Math.floor(st.uptime_seconds) +
        's</span><br/>Connected devices: ' +
        st.connected_devices +
        '<br/>Pairing active: ' +
        st.pairing_active +
        '<br/>TLS: ' +
        st.tls_enabled +
        (st.tls_fingerprint ? '<br/>Fingerprint <span class="mono">' + esc(st.tls_fingerprint) + '</span>' : '');
      root.querySelector('#tls-fp').textContent = st.tls_fingerprint || '(none)';
    }

    async function refreshPairing() {
      const p = await window.JibeApi.json('/api/daemon/pairing/status');
      const pinEl = root.querySelector('#pair-pin');
      const meta = root.querySelector('#pair-meta');
      if (p.active && p.pin) {
        pinEl.textContent = p.pin;
        if (p.expires_at) {
          const left = Math.max(0, Math.floor(p.expires_at - Date.now() / 1000));
          meta.textContent = 'Expires in ' + left + 's · failed attempts (session): ' + p.failed_attempts;
        }
      } else {
        pinEl.textContent = '—';
        meta.textContent =
          'Inactive · failed attempts (session): ' + (p.failed_attempts || 0);
      }
    }

    root.querySelector('#pair-start').onclick = async () => {
      await window.JibeApi.json('/api/daemon/pairing/start', { method: 'POST', body: '{}' });
      await refreshPairing();
      await refreshInfo();
    };
    root.querySelector('#pair-stop').onclick = async () => {
      await window.JibeApi.json('/api/daemon/pairing/stop', { method: 'POST', body: '{}' });
      await refreshPairing();
      await refreshInfo();
    };
    root.querySelector('#tls-regen').onclick = async () => {
      if (!confirm('Delete TLS certs and generate new ones? Restart daemon to load them.')) return;
      const res = await window.JibeApi.json('/api/daemon/certs/regen', {
        method: 'POST',
        body: '{}',
      });
      const note = root.querySelector('#tls-note');
      note.style.display = 'block';
      note.textContent = res.note + ' · fingerprint ' + res.fingerprint;
      await refreshInfo();
    };

    const vr = document.getElementById('view-root');
    vr._daemonTimer = setInterval(() => {
      refreshPairing().catch(() => {});
    }, 1000);

    await refreshInfo();
    await refreshPairing();
  }

  async function route() {
    const role = window.JibeAuth.role();
    const hash = (location.hash || '#devices').slice(1) || 'devices';
    setNavActive(hash);
    const root = document.getElementById('view-root');
    if (root._daemonTimer) {
      clearInterval(root._daemonTimer);
      root._daemonTimer = null;
    }
    try {
      if (hash === 'devices') await renderDevices(root, role);
      else if (hash === 'history') await renderHistory(root);
      else if (hash === 'stats') await renderStats(root);
      else if (hash === 'daemon' && role === 'admin') await renderDaemon(root);
      else {
        location.hash = '#devices';
        return;
      }
    } catch (e) {
      root.innerHTML =
        '<div class="error-banner">' + esc(String(e.message || e)) + '</div>';
    }
  }

  document.getElementById('btn-logout').addEventListener('click', () => {
    window.JibeAuth.logout();
  });

  const userEl = document.getElementById('dash-user');
  if (userEl) userEl.textContent = window.JibeAuth.username();

  buildNav(window.JibeAuth.role());
  loadVersion();
  window.addEventListener('hashchange', route);
  route();
})();
