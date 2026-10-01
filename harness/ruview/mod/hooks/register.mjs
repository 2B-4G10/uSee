// SPDX-License-Identifier: MIT
// ruview-live — a Claude Code mod (function hooks) shipped inside @ruvnet/ruview.
//
// `/ruview` opens a pane beside the transcript with the CSI nodes streaming to
// this machine and, optionally, an ESPHome radar kit; it refreshes on a timer
// and keeps a one-line status. Every reading comes from the @ruvnet/ruview CLI
// this mod ships beside (`--json`), so the pane shows exactly what the tested
// tools return. Read-only: it never flashes, provisions or writes to a device.

export const PANE_ID = 'ruview-live';
export const COMMAND = 'ruview';
const HOST_RE = /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/;
const HISTORY = 24;
const TICK_MS = 5000;

/**
 * The harness CLI beside this mod: the plugin root is <pkg>/mod, so the CLI is
 * <pkg>/bin/cli.js. Mods import nothing but their own files, so the engine's
 * `$.plugin.root` locates it.
 */
export function cliPathOf(pluginRoot) {
  return `${String(pluginRoot).replace(/[\\/]+$/, '')}/../bin/cli.js`;
}

/** Normalise plugin options (userConfig) into bounded settings. */
export function settingsOf(options = {}) {
  const num = (v, d, lo, hi) => {
    const n = Number(v);
    return Number.isFinite(n) ? Math.min(Math.max(Math.round(n), lo), hi) : d;
  };
  const host = typeof options.radarHost === 'string' ? options.radarHost.trim() : '';
  return {
    udpPort: num(options.udpPort, 5005, 1024, 65535),
    radarHost: HOST_RE.test(host) ? host : '',
    refreshMs: num(options.refreshSeconds, 15, 5, 3600) * 1000,
    captureSeconds: num(options.captureSeconds, 3, 1, 10),
  };
}

/** argv for one capture and (optionally) one radar read. */
export function commandsOf(settings) {
  const s = String(settings.captureSeconds);
  return {
    capture: ['esp32', '--seconds', s, '--udp-port', String(settings.udpPort), '--json'],
    radar: settings.radarHost ? ['mmwave', '--source', 'esphome', '--host', settings.radarHost, '--seconds', s, '--json'] : null,
  };
}

/** Parse a CLI run into its JSON result, or an honest failure. */
export function resultOf(run) {
  if (!run) return null;
  try {
    const parsed = JSON.parse(run.stdout);
    if (parsed && typeof parsed === 'object') return parsed;
  } catch { /* fall through */ }
  return { ok: false, reason: 'cli_error', detail: String(run.stderr || run.stdout || `exit ${run.exitCode}`).trim().slice(0, 300) };
}

const fixed = (v, d = 1) => (typeof v === 'number' && Number.isFinite(v) ? v.toFixed(d) : '—');
const pct = (v) => (typeof v === 'number' ? `${(v * 100).toFixed(1)}%` : '—');
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);

/** Resting ranges outside which a device-reported vital is flagged, not trusted. */
export const RANGES = Object.freeze({ heart: [40, 180], breathing: [4, 40] });

/** Why a device-reported vital looks implausible, or null. */
export function plausibilityOf(kind, value) {
  if (value == null || !RANGES[kind]) return null;
  const [lo, hi] = RANGES[kind];
  return value < lo || value > hi ? `outside ${lo}–${hi} bpm` : null;
}

const SPARK = '▁▂▃▄▅▆▇█';
/** Unicode sparkline of the last `width` finite values ('' below two points). */
export function sparkline(values, width = 16) {
  const v = (values || []).filter((x) => typeof x === 'number' && Number.isFinite(x)).slice(-width);
  if (v.length < 2) return '';
  const lo = Math.min(...v);
  const hi = Math.max(...v);
  return v.map((x) => SPARK[hi === lo ? 3 : Math.round(((x - lo) / (hi - lo)) * 7)]).join('');
}

/** "12s ago" / "3m ago" from a millisecond age, or '' when unknown. */
export function ageOf(ms) {
  if (!Number.isFinite(ms) || ms < 0) return '';
  const s = Math.round(ms / 1000);
  return s < 60 ? `${s}s ago` : `${Math.round(s / 60)}m ago`;
}

/** The pane's model: plain data, drawn by viewOf and summarised by statusOf. */
export function modelOf(capture, radar, at) {
  const alerts = [];
  // "No packets" is shown inside the nodes card; only real faults become alerts.
  if (capture && capture.ok === false && capture.reason !== 'no_packets') {
    alerts.push({ level: 'bad', text: `nodes: ${String(capture.reason).replace(/_/g, ' ')}${capture.remedy ? ` — ${capture.remedy}` : ''}` });
  }
  for (const hb of capture?.heartbeatOnlySenders || []) alerts.push({ level: 'warn', text: `${hb.address} sends heartbeats but no CSI (run \`ruview monitor --baud 1500000\` on it: "lack of csi buf" = buffer starvation)` });
  if (radar && radar.ok === false) alerts.push({ level: 'bad', text: `radar: ${String(radar.reason).replace(/_/g, ' ')}${radar.detail ? ` — ${radar.detail}` : ''}` });
  const nodes = (capture?.nodes || []).map((n) => ({
    key: `${n.source}:${n.nodeId}`,
    label: `${n.source === 'realtek' ? 'realtek' : 'esp32'} ${n.nodeId}`,
    rateHz: num(n.csiRateHz),
    rate: `${fixed(n.csiRateHz)} Hz`,
    loss: pct(n.csiLossFraction),
    rssi: `${fixed(n.rssiMean)} dBm`,
    shape: n.csi?.shape || '—',
    lossy: typeof n.csiLossFraction === 'number' && n.csiLossFraction > 0.05,
    synthetic: Boolean(n.csi?.synthetic),
  }));
  const radarRow = radar && radar.ok !== false ? {
    name: radar.device?.name || radar.host || 'radar',
    present: radar.presentNow ?? (radar.presentFraction == null ? null : radar.presentFraction >= 0.5),
    targets: num(radar.targetsMax),
    distanceCm: num(radar.distanceCmMean),
    heartBpm: num(radar.heartBpmMean),
    breathingBpm: num(radar.breathingBpmMean),
    distance: radar.distanceCmMean == null ? '—' : `${fixed(radar.distanceCmMean)} cm`,
    heart: radar.heartBpmMean == null ? '—' : `${fixed(radar.heartBpmMean)} bpm`,
    breathing: radar.breathingBpmMean == null ? '—' : `${fixed(radar.breathingBpmMean)} bpm`,
  } : null;
  return {
    at, packets: capture?.packets ?? 0, decoded: capture?.decodedPackets ?? 0,
    noPackets: capture?.reason === 'no_packets', nodes, radar: radarRow, alerts,
  };
}

/** Append one model to a bounded history of trend values. */
export function historyWith(history, model, max = HISTORY) {
  const h = history || { heart: [], breathing: [], distance: [], rates: {} };
  const cap = (arr, v) => [...arr, v].slice(-max);
  const rates = { ...h.rates };
  for (const n of model?.nodes || []) rates[n.key] = cap(rates[n.key] || [], n.rateHz);
  return {
    heart: cap(h.heart, model?.radar?.heartBpm ?? null),
    breathing: cap(h.breathing, model?.radar?.breathingBpm ?? null),
    distance: cap(h.distance, model?.radar?.distanceCm ?? null),
    rates,
  };
}

/** One line for the status bar. */
export function statusOf(model) {
  if (!model) return 'RuView · starting';
  const parts = [`RuView · ${model.nodes.length} node${model.nodes.length === 1 ? '' : 's'}`];
  if (model.radar) parts.push(`radar ${model.radar.present == null ? '?' : model.radar.present ? 'present' : 'clear'}`);
  if (model.alerts.length) parts.push(`${model.alerts.length} alert${model.alerts.length === 1 ? '' : 's'}`);
  return parts.join(' · ');
}

/**
 * Draw the pane with the surface's own elements ({ Box, Text, Button }).
 * opts: { refreshMs, busy, onRefresh, onClose, columns, now, history, udpPort, radarConfigured }.
 */
export function viewOf(ui, model, opts) {
  const { Box, Text, Button } = ui;
  const { refreshMs, busy, onRefresh, onClose } = opts;
  const columns = opts.columns ?? 80;
  const history = opts.history || historyWith(null, null, 0);
  const t = (children, props = {}) => Text({ wrap: 'truncate-end', ...props, children });
  const row = (children, props = {}) => Box({ flexDirection: 'row', gap: 1, ...props, children: children.filter(Boolean) });
  const every = `every ${Math.round(refreshMs / 1000)}s`;

  // Header: freshness badge, update time and age.
  const finiteAt = model && Number.isFinite(model.at);
  const age = finiteAt && Number.isFinite(opts.now) ? opts.now - model.at : NaN;
  const live = Number.isFinite(age) && age <= refreshMs * 2 + 5000;
  const when = finiteAt ? new Date(model.at).toLocaleTimeString() : '—';
  const badge = !model ? t('○ STARTING', { dimColor: true, bold: true })
    : live ? t('● LIVE', { color: 'green', bold: true }) : t('○ STALE', { color: 'yellow', bold: true });
  const header = row([
    t('RuView', { color: 'cyan', bold: true }),
    badge,
    t(model ? `updated ${when}${ageOf(age) ? ` (${ageOf(age)})` : ''} · ${every}` : `waiting for the first capture… · ${every}`, { dimColor: true }),
    busy ? t('⟳ refreshing', { color: 'cyan' }) : null,
  ], { justifyContent: 'flex-start' });

  const alerts = (model?.alerts || []).map((a) => Text({ color: a.level === 'bad' ? 'red' : 'yellow', wrap: 'wrap', children: `! ${a.text}` }));

  // Radar card.
  const vital = (label, value, raw, kind, series, unitWidth) => {
    const warn = kind ? plausibilityOf(kind, raw) : null;
    return row([
      t(label.padEnd(10), { dimColor: true }),
      t(value.padStart(unitWidth), { bold: raw != null && !warn, color: warn ? 'yellow' : undefined }),
      t(sparkline(series), { color: 'cyan' }),
      warn ? t(`⚠ ${warn}`, { color: 'yellow' }) : null,
    ]);
  };
  let radarCard = null;
  if (model?.radar) {
    const r = model.radar;
    const presence = r.present == null ? t('? presence unknown', { dimColor: true })
      : r.present ? t(`● PRESENCE DETECTED${r.targets ? ` · ${r.targets} target${r.targets === 1 ? '' : 's'}` : ''}`, { color: 'green', bold: true })
        : t('○ no presence', { dimColor: true });
    radarCard = card('60 GHz RADAR', r.name, r.present ? 'green' : 'gray', [
      presence,
      vital('distance', r.distance, r.distanceCm, null, history.distance, 9),
      vital('heart', r.heart, r.heartBpm, 'heart', history.heart, 9),
      vital('breathing', r.breathing, r.breathingBpm, 'breathing', history.breathing, 9),
      t('device-reported values, not validated against a reference', { dimColor: true, italic: true }),
    ]);
  } else if (model && !opts.radarConfigured) {
    radarCard = card('60 GHz RADAR', 'not configured', 'gray', [
      t('Set radarHost to an ESPHome radar kit:', { dimColor: true }),
      t('claude plugin configure ruview-live', { color: 'cyan' }),
    ]);
  }

  // Nodes card.
  let nodesCard = null;
  if (model) {
    const lines = model.nodes.length
      ? model.nodes.map((n) => row([
        t(n.label.padEnd(10), { bold: true }),
        t(n.rate.padStart(9)),
        t(sparkline(history.rates[n.key]), { color: 'cyan' }),
        t(`loss ${n.loss}`, n.lossy ? { color: 'yellow' } : { dimColor: true }),
        t(n.rssi, { dimColor: true }),
        t(n.shape, { dimColor: true }),
        n.synthetic ? t('SYNTHETIC', { color: 'yellow', bold: true }) : null,
      ]))
      : [
        t('none streaming to this machine', { dimColor: true }),
        model.noPackets ? t(`check node target IP/port · firewall UDP ${opts.udpPort ?? 5005}`, { dimColor: true }) : null,
      ].filter(Boolean);
    nodesCard = card('CSI NODES', `UDP ${opts.udpPort ?? 5005} · ${model.decoded}/${model.packets} decoded`, model.nodes.length ? 'cyan' : 'gray', lines);
  }

  function card(title, subtitle, borderColor, children) {
    return Box({
      flexDirection: 'column', borderStyle: 'round', borderColor, paddingX: 1, flexGrow: 1,
      children: [row([t(title, { bold: true }), t(subtitle, { dimColor: true })]), ...children],
    });
  }

  const wide = columns >= 100;
  const cards = [radarCard, nodesCard].filter(Boolean);
  const body = cards.length ? Box({ flexDirection: wide ? 'row' : 'column', gap: 1, children: cards }) : null;
  const footer = row([
    Button({ key: 'refresh', label: busy ? 'Refreshing…' : 'Refresh (r)', hotkey: 'r', onPress: onRefresh }),
    Button({ key: 'close', label: 'Close (c)', hotkey: 'c', onPress: onClose }),
  ], { gap: 2 });

  return Box({ flexDirection: 'column', gap: 1, paddingX: 1, children: [header, ...alerts, body, footer].filter(Boolean) });
}

/**
 * The mod entry: `/ruview [off|refresh]`, the pane, the timer, the status.
 * @param on the engine's registrar
 * @param options this plugin's userConfig values
 */
export function register(on, options = {}) {
  const settings = settingsOf(options);
  const commands = commandsOf(settings);
  let host = null;
  let isOpen = false;
  let busy = false;
  let model = null;
  let history = null;
  let stopTimer = null;
  let stopTick = null;
  let cliPath = null;

  const stop = () => {
    if (stopTimer) { stopTimer(); stopTimer = null; }
    if (stopTick) { stopTick(); stopTick = null; }
  };
  const cancelOf = (t) => (typeof t === 'function' ? t : () => t?.cancel?.());

  async function runCli(args) {
    if (!args) return null;
    try {
      return await host.run(['node', cliPath, ...args], { timeoutMs: (settings.captureSeconds + 30) * 1000 });
    } catch (error) {
      return { exitCode: -1, stdout: '', stderr: String(error?.message || error) };
    }
  }

  async function refresh() {
    if (!host || busy) return;
    busy = true;
    host.invalidate();
    try {
      const [capture, radar] = await Promise.all([runCli(commands.capture), runCli(commands.radar)]);
      // $.clock.now() resolves a promise of epoch milliseconds.
      model = modelOf(resultOf(capture), resultOf(radar), await host.now());
      history = historyWith(history, model);
      host.status(statusOf(model));
    } finally {
      busy = false;
      host.invalidate();
    }
  }

  /** Start the refresh timer (and the age tick) and fetch now, unless already polling. */
  function startPolling() {
    isOpen = true;
    if (stopTimer) return;
    stopTimer = cancelOf(host.every(settings.refreshMs, () => { void refresh(); }));
    stopTick = cancelOf(host.every(TICK_MS, () => host.invalidate()));
    void refresh();
  }

  async function open() {
    await host.open({ id: PANE_ID, title: 'RuView', closeOnEscape: true });
    stop();
    startPolling();
  }

  async function close() {
    stop();
    isOpen = false;
    await host.close({ id: PANE_ID }).catch(() => undefined);
  }

  on('session.start', async ($, e, next) => {
    cliPath = cliPathOf($.plugin.root);
    host = {
      run: (argv, init) => $.process.run(argv, init),
      every: (ms, fn) => $.clock.every(ms, fn),
      now: () => $.clock.now(),
      open: (pane) => $.ui.open(pane),
      close: (pane) => $.ui.close(pane),
      status: (text) => $.ui.status(text),
      invalidate: () => $.ui.invalidate('ui.render'),
    };
    await $.command.register({ name: COMMAND, description: 'RuView live sensing pane: CSI nodes and radar, refreshed', argumentHint: '[off|refresh]' }).catch(() => undefined);
    return next(e);
  });

  on('command.run', { command: COMMAND }, async ($, e, next) => {
    if (!host) return next(e);
    const arg = String(e.args || '').trim();
    if (arg === 'off') { await close(); host.status(undefined); return { text: 'RuView pane closed.' }; }
    if (arg === 'refresh') { await refresh(); return { text: statusOf(model) }; }
    if (isOpen) { await close(); return { text: 'RuView pane closed.' }; }
    await open();
    return { text: `RuView pane open: nodes on UDP ${settings.udpPort}${settings.radarHost ? `, radar ${settings.radarHost}` : ''}; refreshing every ${Math.round(settings.refreshMs / 1000)}s.` };
  });

  on('ui.render', { component: 'Pane' }, async ($, e, next) => {
    if (e.requestId !== PANE_ID) return next(e);
    // A reload (hot reload, or a resumed session) re-runs register with fresh
    // variables while the engine keeps the pane open: drawing it means it is
    // open, so resume polling instead of waiting for the first capture forever.
    if (host && !stopTimer) startPolling();
    const ui = await $.ui.resolve(e);
    const now = await $.clock.now();
    return viewOf(ui, model, {
      refreshMs: settings.refreshMs, busy, history, now,
      columns: e.props?.bodyColumns ?? e.viewport?.columns ?? 80,
      udpPort: settings.udpPort, radarConfigured: Boolean(settings.radarHost),
      onRefresh: () => { void refresh(); }, onClose: () => { void close(); },
    });
  });

  on('ui.close', { id: PANE_ID }, async ($, e, next) => {
    stop();
    isOpen = false;
    return next(e);
  });

  on('session.end', async ($, e, next) => {
    stop();
    return next(e);
  });
}
