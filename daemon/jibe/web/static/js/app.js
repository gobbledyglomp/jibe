(function () {
  const POLL_MS = 5000;
  const STORAGE_DAEMON_EVENT_LOG = 'jibe_daemon_browser_log';
  const EVENT_LOG_DISPLAY_MAX = 25;

  function daemonEventLogEnabled() {
    if (localStorage.getItem(STORAGE_DAEMON_EVENT_LOG) === '1') return true;
    return localStorage.getItem('jibe_show_ping_card') === '1';
  }

  function setDaemonEventLogEnabled(on) {
    localStorage.setItem(STORAGE_DAEMON_EVENT_LOG, on ? '1' : '0');
    localStorage.removeItem('jibe_show_ping_card');
  }

  function T(key) {
    return window.JibeI18n ? window.JibeI18n.t(key) : key;
  }

  function fmtBytes(n) {
    if (n == null || n === '') return '—';
    const v = Number(n);
    if (!Number.isFinite(v)) return String(n);
    if (v < 1024) return v + ' B';
    if (v < 1024 * 1024) return (v / 1024).toFixed(1) + ' KB';
    if (v < 1024 * 1024 * 1024) return (v / (1024 * 1024)).toFixed(2) + ' MB';
    return (v / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

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

  function refreshChrome() {
    document.title = T('meta.docTitle');
    const lo = document.getElementById('btn-logout');
    if (lo) lo.textContent = T('common.logout');
    buildNav(window.JibeAuth.role());
    const userEl = document.getElementById('dash-user');
    if (userEl) userEl.textContent = window.JibeAuth.username();
  }

  function setNavActive(hash) {
    document.querySelectorAll('aside nav a').forEach((a) => {
      a.classList.toggle('active', a.dataset.hash === hash);
    });
  }

  function buildNav(role) {
    const nav = document.getElementById('nav');
    if (!nav) return;
    nav.innerHTML = '';
    const items = [
      { hash: 'devices', label: T('nav.devices') },
      { hash: 'history', label: T('nav.history') },
      { hash: 'stats', label: T('nav.stats') },
      { hash: 'settings', label: T('nav.settings') },
    ];
    if (role === 'admin') items.splice(3, 0, { hash: 'daemon', label: T('nav.daemon') });
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

  function clearPageTimers(root) {
    if (!root) return;
    if (root._daemonPollTimer) {
      clearInterval(root._daemonPollTimer);
      root._daemonPollTimer = null;
    }
    if (root._daemonUiTimer) {
      clearInterval(root._daemonUiTimer);
      root._daemonUiTimer = null;
    }
    if (root._eventLogPollTimer) {
      clearInterval(root._eventLogPollTimer);
      root._eventLogPollTimer = null;
    }
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
    if (sec < 60) return T('time.secAgo').replace('{n}', String(sec));
    const min = Math.floor(sec / 60);
    if (min < 60) return T('time.minAgo').replace('{n}', String(min));
    const h = Math.floor(min / 60);
    if (h < 48) return T('time.hourAgo').replace('{n}', String(h));
    return iso.slice(0, 16).replace('T', ' ');
  }

  async function renderDevices(root, role) {
    stopPollDevices();
    const devPerPage = 25;
    let devPage = 1;
    let devList = [];

    let batteryCache = {};

    root.innerHTML =
      '<h1>' +
      esc(T('devices.title')) +
      '</h1><div id="dev-wrap"><table class="data"><thead><tr><th></th><th>' +
      esc(T('devices.name')) +
      '</th><th>' +
      esc(T('devices.battery')) +
      '</th><th>' +
      esc(T('devices.lastSeen')) +
      '</th><th>' +
      esc(T('devices.paired')) +
      '</th>' +
      (role === 'admin' ? '<th></th>' : '') +
      '</tr></thead><tbody id="dev-body"></tbody></table></div>' +
      '<div style="margin-top:0.75rem;display:flex;gap:0.5rem;align-items:center;" id="dev-pager"></div>';

    const tbody = root.querySelector('#dev-body');
    const pagerEl = root.querySelector('#dev-pager');

    function paintDeviceRows() {
      const total = devList.length;
      const pages = Math.max(1, Math.ceil(total / devPerPage));
      devPage = Math.min(Math.max(1, devPage), pages);
      const start = (devPage - 1) * devPerPage;
      const slice = devList.slice(start, start + devPerPage);

      tbody.innerHTML = '';
      slice.forEach((d) => {
        const tr = document.createElement('tr');
        const dot = d.online ? 'dot-online' : 'dot-offline';
        const ol = d.online ? T('common.online') : T('common.offline');
        const bat = batteryCache[d.id];
        const batStr =
          bat != null ? bat.level + '% ' + (bat.charging ? '⚡' : '🔌') : '—';
        tr.innerHTML =
          '<td><span class="' +
          dot +
          '" title="' +
          esc(ol) +
          '"></span></td>' +
          '<td class="dev-name mono" data-id="' +
          esc(d.id) +
          '">' +
          esc(d.name) +
          '</td>' +
          '<td class="mono">' +
          esc(batStr) +
          '</td>' +
          '<td>' +
          relativeTime(d.last_seen) +
          '</td>' +
          '<td class="mono">' +
          esc(String(d.paired_at || '').slice(0, 16)) +
          '</td>';
        if (role === 'admin') {
          const td = document.createElement('td');
          td.style.cssText = 'display:flex;gap:0.35rem;';
          if (d.online) {
            const ringBtn = document.createElement('button');
            ringBtn.className = 'btn btn-sm';
            ringBtn.textContent = T('devices.ring');
            const ringDisabled = d.feat_find_phone === false;
            if (ringDisabled) {
              ringBtn.disabled = true;
              ringBtn.classList.add('btn-disabled-ring');
              ringBtn.setAttribute('aria-disabled', 'true');
              ringBtn.title = T('devices.ringDisabledTitle');
            } else {
              ringBtn.title = T('devices.ringTitle');
              ringBtn.addEventListener('click', async () => {
                ringBtn.disabled = true;
                try {
                  await window.JibeApi.request('/api/ring/' + encodeURIComponent(d.id), {
                    method: 'POST',
                  });
                } catch (e) {
                  alert(T('devices.ringFail') + ' ' + (e.message || e));
                } finally {
                  ringBtn.disabled = false;
                }
              });
            }
            td.appendChild(ringBtn);
          }
          const revokeBtn = document.createElement('button');
          revokeBtn.className = 'btn btn-danger btn-sm';
          revokeBtn.textContent = T('common.revoke');
          revokeBtn.addEventListener('click', async () => {
            if (!confirm(T('devices.revokeConfirm') + ' ' + d.name + '?')) return;
            try {
              await window.JibeApi.request('/api/devices/' + encodeURIComponent(d.id), {
                method: 'DELETE',
              });
              await fetchDevices();
            } catch (e) {
              alert(T('devices.revokeFail') + ' ' + (e.message || e));
            }
          });
          td.appendChild(revokeBtn);
          tr.appendChild(td);
        }
        tbody.appendChild(tr);
      });

      if (role === 'admin') {
        tbody.querySelectorAll('.dev-name').forEach((cell) => {
          cell.style.cursor = 'pointer';
          cell.title = T('devices.renameTitle');
          cell.addEventListener('click', async () => {
            const id = cell.dataset.id;
            const cur = cell.textContent;
            const nv = prompt(T('devices.renamePrompt'), cur);
            if (!nv || nv === cur) return;
            await window.JibeApi.json('/api/devices/' + encodeURIComponent(id), {
              method: 'PATCH',
              body: JSON.stringify({ name: nv }),
            });
            fetchDevices();
          });
        });
      }

      pagerEl.innerHTML =
        '<span style="color:var(--muted);font-size:0.85rem;">' +
        esc(T('common.page')) +
        ' ' +
        devPage +
        ' / ' +
        pages +
        ' (' +
        total +
        ')</span>' +
        '<button type="button" class="btn btn-sm" id="dev-prev">' +
        esc(T('common.prev')) +
        '</button>' +
        '<button type="button" class="btn btn-sm" id="dev-next">' +
        esc(T('common.next')) +
        '</button>';
      pagerEl.querySelector('#dev-prev').onclick = () => {
        devPage = Math.max(1, devPage - 1);
        paintDeviceRows();
      };
      pagerEl.querySelector('#dev-next').onclick = () => {
        devPage = Math.min(pages, devPage + 1);
        paintDeviceRows();
      };
    }

    async function fetchDevices() {
      const [devData, batData] = await Promise.all([
        window.JibeApi.json('/api/devices'),
        window.JibeApi.json('/api/battery').catch(() => ({ battery: {} })),
      ]);
      devList = devData.devices || [];
      batteryCache = batData.battery || {};
      paintDeviceRows();
    }

    await fetchDevices();
    pollDevicesTimer = setInterval(() => fetchDevices().catch(() => {}), POLL_MS);
  }

  async function renderHistory(root) {
    stopPollDevices();
    let tab = 'transfers';
    let page = 1;
    const perPage = 25;
    const any = esc(T('history.any'));

    root.innerHTML =
      '<h1>' +
      esc(T('history.title')) +
      '</h1>' +
      '<div class="tabs" id="hist-tabs">' +
      '<button type="button" data-t="transfers" class="active">' +
      esc(T('history.transfers')) +
      '</button>' +
      '<button type="button" data-t="clipboard">' +
      esc(T('history.clipboard')) +
      '</button>' +
      '<button type="button" data-t="notifications">' +
      esc(T('history.notifications')) +
      '</button>' +
      '</div>' +
      '<div class="toolbar">' +
      '<div class="field"><label class="small">' +
      esc(T('history.deviceFilter')) +
      '</label><input id="hf-device" class="mono" /></div>' +
      '<div class="field"><label class="small">' +
      esc(T('history.fromIso')) +
      '</label><input id="hf-from" placeholder="2026-01-01" /></div>' +
      '<div class="field"><label class="small">' +
      esc(T('history.toIso')) +
      '</label><input id="hf-to" /></div>' +
      '<div class="field" id="hf-status-wrap"><label class="small">' +
      esc(T('history.status')) +
      '</label><select id="hf-status"><option value="">' +
      any +
      '</option>' +
      '<option>in_progress</option><option>completed</option><option>cancelled</option><option>failed</option></select></div>' +
      '<div class="field" id="hf-dir-wrap"><label class="small">' +
      esc(T('history.direction')) +
      '</label><select id="hf-dir"><option value="">' +
      any +
      '</option>' +
      '<option>incoming</option><option>outgoing</option></select></div>' +
      '<div class="field" id="hf-app-wrap" style="display:none"><label class="small">' +
      esc(T('history.appContains')) +
      '</label><input id="hf-app" /></div>' +
      '<button type="button" class="btn btn-toolbar-apply" id="hf-go">' +
      esc(T('common.apply')) +
      '</button>' +
      '</div>' +
      '<div id="hist-table-wrap"></div>' +
      '<div style="margin-top:0.75rem;display:flex;gap:0.5rem;align-items:center;" id="hist-pager"></div>';

    function bindToolbarEnter() {
      function applyFilters(e) {
        if (e.key !== 'Enter') return;
        e.preventDefault();
        page = 1;
        loadAndCatch();
      }
      ['hf-device', 'hf-from', 'hf-to', 'hf-app'].forEach((id) => {
        const el = root.querySelector('#' + id);
        if (el) el.addEventListener('keydown', applyFilters);
      });
      ['hf-status', 'hf-dir'].forEach((id) => {
        const el = root.querySelector('#' + id);
        if (el) el.addEventListener('keydown', applyFilters);
      });
    }

    bindToolbarEnter();

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
        html +=
          '<th>' +
          esc(T('history.file')) +
          '</th><th>' +
          esc(T('history.size')) +
          '</th><th>' +
          esc(T('history.statusCol')) +
          '</th><th>' +
          esc(T('history.started')) +
          '</th></tr></thead><tbody>';
        data.items.forEach((it) => {
          rows.push(it);
          html +=
            '<tr data-i="' +
            rows.length +
            '"><td class="mono">' +
            esc(it.filename) +
            '</td><td class="mono">' +
            fmtBytes(it.size) +
            '</td><td>' +
            esc(it.status) +
            '</td><td>' +
            esc(it.started_at.slice(0, 19)) +
            '</td></tr>';
        });
      } else if (tab === 'clipboard') {
        html +=
          '<th>' +
          esc(T('history.source')) +
          '</th><th>' +
          esc(T('history.dir')) +
          '</th><th>' +
          esc(T('history.preview')) +
          '</th><th>' +
          esc(T('history.when')) +
          '</th></tr></thead><tbody>';
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
        html +=
          '<th>' +
          esc(T('history.app')) +
          '</th><th>' +
          esc(T('history.titleCol')) +
          '</th><th>' +
          esc(T('history.received')) +
          '</th></tr></thead><tbody>';
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
        '<span style="color:var(--muted);font-size:0.85rem;">' +
        esc(T('common.page')) +
        ' ' +
        data.page +
        ' / ' +
        data.pages +
        ' (' +
        data.total +
        ')</span>' +
        '<button type="button" class="btn btn-sm" id="hp-prev">' +
        esc(T('common.prev')) +
        '</button>' +
        '<button type="button" class="btn btn-sm" id="hp-next">' +
        esc(T('common.next')) +
        '</button>';
      pager.querySelector('#hp-prev').onclick = () => {
        page = Math.max(1, page - 1);
        loadAndCatch();
      };
      pager.querySelector('#hp-next').onclick = () => {
        page = Math.min(data.pages, page + 1);
        loadAndCatch();
      };
    }

    function loadAndCatch() {
      load().catch((e) => {
        root.querySelector('#hist-table-wrap').innerHTML =
          '<div class="error-banner">' + esc(String(e.message || e)) + '</div>';
      });
    }

    root.querySelectorAll('#hist-tabs button').forEach((b) => {
      b.addEventListener('click', () => {
        root.querySelectorAll('#hist-tabs button').forEach((x) =>
          x.classList.toggle('active', x === b),
        );
        tab = b.dataset.t;
        page = 1;
        loadAndCatch();
      });
    });
    root.querySelector('#hf-go').onclick = () => {
      page = 1;
      loadAndCatch();
    };
    await load();
  }

  function chartColors() {
    const cs = getComputedStyle(document.documentElement);
    return {
      t: cs.getPropertyValue('--chart-transfer').trim() || '#58a6ff',
      c: cs.getPropertyValue('--chart-clipboard').trim() || '#3fb950',
      ax: cs.getPropertyValue('--chart-axis').trim() || '#8b949e',
    };
  }

  function renderStatsChart(rootEl, activity) {
    const col = chartColors();
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
      r1.setAttribute('fill', col.t);
      svg.appendChild(r1);
      const r2 = document.createElementNS(svgNS, 'rect');
      r2.setAttribute('x', String(x0 + barW));
      r2.setAttribute('y', String(H - pad - h2));
      r2.setAttribute('width', String(barW - 1));
      r2.setAttribute('height', String(Math.max(h2, 1)));
      r2.setAttribute('fill', col.c);
      svg.appendChild(r2);
      const tx = document.createElementNS(svgNS, 'text');
      tx.setAttribute('x', String(x0 + barW));
      tx.setAttribute('y', String(H - 8));
      tx.setAttribute('text-anchor', 'middle');
      tx.setAttribute('fill', col.ax);
      tx.setAttribute('font-size', '10');
      tx.textContent = day.date.slice(5);
      svg.appendChild(tx);
    });
    const leg = document.createElement('div');
    leg.style.fontSize = '0.75rem';
    leg.style.color = 'var(--muted)';
    leg.innerHTML =
      '<span style="color:' +
      esc(col.t) +
      '">■</span> ' +
      esc(T('stats.legendTransfers')) +
      ' &nbsp; <span style="color:' +
      esc(col.c) +
      '">■</span> ' +
      esc(T('stats.legendClipboard'));
    rootEl.appendChild(leg);
    rootEl.appendChild(svg);
  }

  async function renderStats(root) {
    stopPollDevices();
    const data = await window.JibeApi.json('/api/stats');
    const t = data.totals;
    root.innerHTML =
      '<h1>' +
      esc(T('stats.title')) +
      '</h1>' +
      '<div class="summary-grid">' +
      '<div class="summary-card"><div class="lbl">' +
      esc(T('stats.transfersCompleted')) +
      '</div><div class="val">' +
      t.transfers_completed +
      '</div></div>' +
      '<div class="summary-card"><div class="lbl">' +
      esc(T('stats.bytesTransferred')) +
      '</div><div class="val mono">' +
      t.bytes_transferred +
      '</div></div>' +
      '<div class="summary-card"><div class="lbl">' +
      esc(T('stats.clipboardEvents')) +
      '</div><div class="val">' +
      t.clipboard_events +
      '</div></div>' +
      '<div class="summary-card"><div class="lbl">' +
      esc(T('stats.notifications')) +
      '</div><div class="val">' +
      t.notifications +
      '</div></div>' +
      '</div>' +
      '<div class="panel" style="margin-top:1rem;"><strong>' +
      esc(T('stats.topDevice')) +
      '</strong>' +
      '<div id="top-dev" style="margin-top:0.5rem;font-size:0.9rem;"></div></div>' +
      '<div class="panel chart-wrap"><strong>' +
      esc(T('stats.activity7d')) +
      '</strong><div id="chart"></div></div>';

    const td = data.top_device;
    root.querySelector('#top-dev').innerHTML = td
      ? esc(td.name || td.id) +
        ' — <span class="mono">' +
        td.events +
        ' ' +
        esc(T('stats.events')) +
        '</span>'
      : '<span style="color:var(--muted)">' + esc(T('stats.noData')) + '</span>';

    renderStatsChart(root.querySelector('#chart'), data.activity_last_7_days);
  }

  async function renderDaemon(root) {
    stopPollDevices();
    root.innerHTML =
      '<h1>' +
      esc(T('daemon.title')) +
      '</h1>' +
      '<div class="panel" style="margin-bottom:1rem;"><div id="daemon-info"></div></div>' +
      '<div class="panel" style="margin-bottom:1rem;">' +
      '<strong>' +
      esc(T('daemon.pairing')) +
      '</strong>' +
      '<div id="pair-pin" class="mono" style="font-size:2rem;margin:0.5rem 0;"></div>' +
      '<div id="pair-meta" style="font-size:0.85rem;color:var(--muted);margin-bottom:0.75rem;"></div>' +
      '<button type="button" class="btn btn-primary btn-sm" id="pair-start">' +
      esc(T('daemon.startPairing')) +
      '</button> ' +
      '<button type="button" class="btn btn-sm" id="pair-stop">' +
      esc(T('daemon.stopPairing')) +
      '</button>' +
      '</div>' +
      '<div class="panel" style="margin-bottom:1rem;">' +
      '<strong>' +
      esc(T('daemon.tlsSection')) +
      '</strong>' +
      '<div id="tls-fp" class="mono" style="margin:0.5rem 0;font-size:0.8rem;word-break:break-all;"></div>' +
      '<button type="button" class="btn btn-danger btn-sm" id="tls-regen">' +
      esc(T('daemon.regenCert')) +
      '</button>' +
      '<div id="tls-note" style="margin-top:0.5rem;display:none;" class="banner-warn"></div>' +
      '</div>' +
      '<div class="panel" id="event-log-panel" style="margin-bottom:1rem;display:none;">' +
      '<strong>' +
      esc(T('daemon.eventLogTitle')) +
      '</strong>' +
      '<p style="font-size:0.8rem;color:var(--muted);margin:0.35rem 0 0.65rem;">' +
      esc(T('daemon.eventLogHint')) +
      '</p>' +
      '<button type="button" class="btn btn-sm" id="ping-send">' +
      esc(T('daemon.sendPing')) +
      '</button>' +
      '<div id="event-log-list" style="margin-top:0.75rem;font-size:0.8rem;" class="mono"></div>' +
      '</div>';

    root._pairSnap = { active: false, pin: null, expires_at: null, failed_attempts: 0 };

    async function refreshInfo() {
      const st = await window.JibeApi.json('/api/daemon/status');
      root.querySelector('#daemon-info').innerHTML =
        esc(T('daemon.version')) +
        ' <span class="mono">' +
        esc(st.version) +
        '</span><br/>' +
        esc(T('daemon.uptime')) +
        ' <span class="mono">' +
        Math.floor(st.uptime_seconds) +
        's</span><br/>' +
        esc(T('daemon.connected')) +
        ': ' +
        st.connected_devices +
        '<br/>' +
        esc(T('daemon.pairingActive')) +
        ': ' +
        st.pairing_active +
        '<br/>' +
        esc(T('daemon.tls')) +
        ': ' +
        st.tls_enabled;
      root.querySelector('#tls-fp').textContent = st.tls_fingerprint || '(none)';
    }

    function paintPairingUi() {
      const pinEl = root.querySelector('#pair-pin');
      const meta = root.querySelector('#pair-meta');
      const p = root._pairSnap;
      if (!pinEl || !meta) return;
      if (p.active && p.pin) {
        pinEl.textContent = p.pin;
        if (p.expires_at != null) {
          const left = Math.max(0, Math.ceil(p.expires_at - Date.now() / 1000));
          meta.textContent = T('daemon.expiresIn')
            .replace('{n}', String(left))
            .replace('{f}', String(p.failed_attempts ?? 0));
        }
      } else {
        pinEl.textContent = '—';
        meta.textContent = T('daemon.inactive').replace('{f}', String(p.failed_attempts || 0));
      }
    }

    async function refreshPairing() {
      const p = await window.JibeApi.json('/api/daemon/pairing/status');
      root._pairSnap = p;
      paintPairingUi();
    }

    async function refreshEventLog() {
      const box = root.querySelector('#event-log-list');
      if (!box) return;
      const panel = root.querySelector('#event-log-panel');
      if (panel && panel.style.display === 'none') return;
      try {
        const data = await window.JibeApi.json('/api/daemon/event-log');
        if (!data.items || data.items.length === 0) {
          box.innerHTML =
            '<span style="color:var(--muted)">' + esc(T('daemon.noEvents')) + '</span>';
          return;
        }
        const tail = data.items.slice(-EVENT_LOG_DISPLAY_MAX).reverse();
        box.innerHTML = tail
          .map(function (it) {
            const levelColor =
              it.level === 'warn' ? 'var(--warn)' : 'var(--muted)';
            let line =
              '<span style="color:' +
              levelColor +
              '">' +
              esc(it.at.slice(11, 19)) +
              '</span>' +
              ' <span style="opacity:0.55">[' +
              esc(it.category) +
              ']</span> ' +
              esc(it.message);
            if (it.detail && Object.keys(it.detail).length > 0) {
              line +=
                ' <span style="opacity:0.5">' +
                esc(JSON.stringify(it.detail)) +
                '</span>';
            }
            return '<div>' + line + '</div>';
          })
          .join('');
      } catch (e) {
        box.innerHTML =
          '<span style="color:var(--danger)">' +
          esc(T('daemon.eventLogError') + ': ' + (e.message || e)) +
          '</span>';
      }
    }

    const eventLogPanel = root.querySelector('#event-log-panel');
    if (daemonEventLogEnabled()) {
      eventLogPanel.style.display = '';
      root._eventLogPollTimer = setInterval(() => refreshEventLog().catch(() => {}), 3000);
      refreshEventLog().catch(() => {});
    }

    root.querySelector('#pair-start').onclick = async () => {
      const res = await window.JibeApi.json('/api/daemon/pairing/start', {
        method: 'POST',
        body: '{}',
      });
      root._pairSnap = {
        active: true,
        pin: res.pin,
        expires_at: res.expires_at,
        failed_attempts: root._pairSnap ? (root._pairSnap.failed_attempts || 0) : 0,
      };
      paintPairingUi();
      await refreshInfo();
    };
    root.querySelector('#pair-stop').onclick = async () => {
      await window.JibeApi.json('/api/daemon/pairing/stop', { method: 'POST', body: '{}' });
      await refreshPairing();
      await refreshInfo();
    };
    root.querySelector('#tls-regen').onclick = async () => {
      if (!confirm(T('daemon.regenConfirm'))) return;
      const res = await window.JibeApi.json('/api/daemon/certs/regen', {
        method: 'POST',
        body: '{}',
      });
      const note = root.querySelector('#tls-note');
      note.style.display = 'block';
      note.textContent = res.note + ' · ' + res.fingerprint;
      await refreshInfo();
    };

    const pingBtn = root.querySelector('#ping-send');
    if (pingBtn) {
      pingBtn.onclick = async () => {
        pingBtn.disabled = true;
        const origText = pingBtn.textContent;
        try {
          const res = await window.JibeApi.json('/api/daemon/ping-send', {
            method: 'POST',
            body: '{}',
          });
          pingBtn.textContent = T('daemon.pingSent').replace('{n}', String(res.sent || 0));
          await refreshEventLog();
        } catch (e) {
          pingBtn.textContent = '✕ ' + (e.message || e);
        } finally {
          setTimeout(() => {
            pingBtn.textContent = origText;
            pingBtn.disabled = false;
          }, 2000);
        }
      };
    }

    root._daemonPollTimer = setInterval(() => {
      refreshPairing().catch(() => {});
    }, 1000);
    root._daemonUiTimer = setInterval(() => paintPairingUi(), 250);

    await refreshInfo();
    await refreshPairing();
  }

  async function renderSettings(root, role) {
    stopPollDevices();
    const admin = role === 'admin';

    let appearance =
      '<section class="panel settings-card">' +
      '<h2 class="settings-card-title">' +
      esc(T('settings.appearance')) +
      '</h2>' +
      '<div class="settings-card-body">' +
      '<div class="settings-field-row">' +
      '<label class="small" for="set-theme">' +
      esc(T('settings.theme')) +
      '</label>' +
      '<select id="set-theme">' +
      '<option value="dark">' +
      esc(T('settings.themeDark')) +
      '</option>' +
      '<option value="light">' +
      esc(T('settings.themeLight')) +
      '</option>' +
      '</select></div>' +
      '<div class="settings-field-row">' +
      '<label class="small" for="set-lang">' +
      esc(T('settings.language')) +
      '</label>' +
      '<select id="set-lang">' +
      '<option value="en">' +
      esc(T('settings.langEn')) +
      '</option>' +
      '<option value="es">' +
      esc(T('settings.langEs')) +
      '</option>' +
      '</select></div></div></section>';

    let account =
      '<section class="panel settings-card">' +
      '<h2 class="settings-card-title">' +
      esc(T('settings.account')) +
      '</h2>' +
      '<div class="settings-card-body">' +
      '<div class="settings-field-row">' +
      '<label class="small" for="pw-cur">' +
      esc(T('settings.currentPassword')) +
      '</label>' +
      '<input id="pw-cur" type="password" autocomplete="current-password" />' +
      '</div>' +
      '<div class="settings-field-row">' +
      '<label class="small" for="pw-new">' +
      esc(T('settings.newPassword')) +
      '</label>' +
      '<input id="pw-new" type="password" autocomplete="new-password" />' +
      '</div>' +
      '<button type="button" class="btn btn-primary btn-sm" id="pw-btn">' +
      esc(T('settings.changePassword')) +
      '</button>' +
      '<div id="pw-msg" style="margin-top:0.45rem;font-size:0.85rem;"></div>' +
      '<div id="recovery-path-row" style="margin-top:1rem;display:none;">' +
      '<label class="small">' +
      esc(T('settings.recoveryFilePath')) +
      '</label>' +
      '<code id="recovery-path-val" class="mono" style="font-size:0.78rem;opacity:0.75;overflow-wrap:anywhere;display:block;"></code>' +
      '</div>' +
      '</div></section>';

    let adminBlocks = '';
    if (admin) {
      adminBlocks =
        '<section class="panel settings-card">' +
        '<h2 class="settings-card-title">' +
        esc(T('settings.data')) +
        '</h2>' +
        '<p class="settings-help" style="margin-bottom:0.65rem;">' +
        esc(T('settings.adminOnly')) +
        '</p>' +
        '<div class="settings-actions">' +
        '<button type="button" class="btn btn-danger btn-sm" id="btn-clear-hist">' +
        esc(T('settings.clearHistory')) +
        '</button>' +
        '<button type="button" class="btn btn-danger btn-sm" id="btn-clear-stats">' +
        esc(T('settings.clearStats')) +
        '</button>' +
        '</div>' +
        '<p class="settings-help" style="margin-top:0.5rem;">' +
        esc(T('settings.clearHistoryHelp')) +
        '</p>' +
        '<p class="settings-help" style="margin-top:0.35rem;">' +
        esc(T('settings.clearStatsHelp')) +
        '</p>' +
        '</section>' +
        '<details class="settings-advanced">' +
        '<summary>' +
        esc(T('settings.advanced')) +
        '</summary>' +
        '<div class="settings-advanced-body">' +
        '<div class="checkbox-row" style="margin-top:0;">' +
        '<input type="checkbox" id="set-daemon-event-log" />' +
        '<label for="set-daemon-event-log">' +
        esc(T('settings.devEventLog')) +
        '<br/><span class="settings-help" style="display:inline-block;margin-top:0.25rem;">' +
        esc(T('settings.devEventLogHelp')) +
        '</span></label></div>' +
        '<hr class="settings-divider" />' +
        '<div class="settings-actions">' +
        '<button type="button" class="btn btn-sm" id="btn-recovery-rotate">' +
        esc(T('settings.recoveryRotate')) +
        '</button>' +
        '</div>' +
        '<p class="settings-help" style="margin-top:0.45rem;">' +
        esc(T('settings.recoveryRotateHelp')) +
        '</p>' +
        '<pre id="recovery-out" style="display:none;margin-top:0.5rem;font-size:0.75rem;white-space:pre-wrap;"></pre>' +
        '</div></details>';
    }

    root.innerHTML =
      '<h1 class="settings-page-title">' +
      esc(T('settings.title')) +
      '</h1>' +
      '<div class="settings-stack">' +
      appearance +
      account +
      adminBlocks +
      '</div>';

    const selTh = root.querySelector('#set-theme');
    const selLg = root.querySelector('#set-lang');
    selTh.value = window.JibeI18n.theme();
    selLg.value = window.JibeI18n.lang();

    selTh.onchange = () => {
      window.JibeI18n.setTheme(selTh.value);
    };
    selLg.onchange = () => {
      window.JibeI18n.setLang(selLg.value);
    };

    root.querySelector('#pw-btn').onclick = async () => {
      const msg = root.querySelector('#pw-msg');
      msg.textContent = '';
      const cur = root.querySelector('#pw-cur').value;
      const neu = root.querySelector('#pw-new').value;
      try {
        await window.JibeApi.json('/api/auth/change-password', {
          method: 'POST',
          body: JSON.stringify({ current_password: cur, new_password: neu }),
        });
        msg.style.color = 'var(--success)';
        msg.textContent = T('settings.passwordChanged');
        root.querySelector('#pw-cur').value = '';
        root.querySelector('#pw-new').value = '';
      } catch (e) {
        msg.style.color = 'var(--danger)';
        msg.textContent = T('settings.passwordFail') + ' ' + (e.message || e);
      }
    };

    window.JibeApi.json('/api/auth/recovery-status').then((d) => {
      if (d.recovery_file_path) {
        const row = root.querySelector('#recovery-path-row');
        const val = root.querySelector('#recovery-path-val');
        if (row && val) {
          val.textContent = d.recovery_file_path;
          row.style.display = 'block';
        }
      }
    }).catch(() => {});

    if (admin) {
      root.querySelector('#set-daemon-event-log').checked = daemonEventLogEnabled();
      root.querySelector('#set-daemon-event-log').onchange = (e) => {
        setDaemonEventLogEnabled(e.target.checked);
      };

      root.querySelector('#btn-clear-hist').onclick = async () => {
        if (!confirm(T('settings.clearHistoryConfirm'))) return;
        await window.JibeApi.json('/api/settings/data/clear-history', {
          method: 'POST',
          body: '{}',
        });
      };

      root.querySelector('#btn-clear-stats').onclick = async () => {
        if (!confirm(T('settings.clearStatsConfirm'))) return;
        await window.JibeApi.json('/api/settings/data/clear-statistics', {
          method: 'POST',
          body: '{}',
        });
      };

      root.querySelector('#btn-recovery-rotate').onclick = async () => {
        if (!confirm(T('settings.recoveryRotateConfirm'))) return;
        try {
          const res = await window.JibeApi.json('/api/settings/recovery/regenerate', {
            method: 'POST',
            body: '{}',
          });
          const pre = root.querySelector('#recovery-out');
          pre.style.display = 'block';
          pre.textContent =
            T('settings.recoveryNewToken') + '\n\n' + (res.recovery_token || '');
        } catch (e) {
          alert(String(e.message || e));
        }
      };
    }
  }

  async function route() {
    const role = window.JibeAuth.role();
    const hash = (location.hash || '#devices').slice(1) || 'devices';
    setNavActive(hash);
    const root = document.getElementById('view-root');
    clearPageTimers(root);
    try {
      if (hash === 'devices') await renderDevices(root, role);
      else if (hash === 'history') await renderHistory(root);
      else if (hash === 'stats') await renderStats(root);
      else if (hash === 'settings') await renderSettings(root, role);
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

  window.addEventListener('jibe-locale', () => {
    refreshChrome();
    route();
  });

  window.addEventListener('jibe-theme', () => {
    route();
  });

  refreshChrome();
  loadVersion();
  window.addEventListener('hashchange', route);
  route();
})();
