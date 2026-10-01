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

/** The pane's model: plain data, drawn by viewOf and summarised by statusOf. */
export function modelOf(capture, radar, at) {
  const alerts = [];
  if (capture && capture.ok === false) alerts.push({ level: capture.reason === 'no_packets' ? 'warn' : 'bad', text: `nodes: ${String(capture.reason).replace(/_/g, ' ')}${capture.remedy ? ` — ${capture.remedy}` : ''}` });
  for (const hb of capture?.heartbeatOnlySenders || []) alerts.push({ level: 'warn', text: `${hb.address} sends heartbeats but no CSI (run \`ruview monitor --baud 1500000\` on it: "lack of csi buf" = buffer starvation)` });
  if (radar && radar.ok === false) alerts.push({ level: 'bad', text: `radar: ${String(radar.reason).replace(/_/g, ' ')}${radar.detail ? ` — ${radar.detail}` : ''}` });
  const nodes = (capture?.nodes || []).map((n) => ({
    label: `${n.source === 'realtek' ? 'realtek' : 'esp32'} ${n.nodeId}`,
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
    distance: radar.distanceCmMean == null ? '—' : `${fixed(radar.distanceCmMean)} cm`,
    heart: radar.heartBpmMean == null ? '—' : `${fixed(radar.heartBpmMean)} bpm`,
    breathing: radar.breathingBpmMean == null ? '—' : `${fixed(radar.breathingBpmMean)} bpm`,
  } : null;
  return { at, packets: capture?.packets ?? 0, decoded: capture?.decodedPackets ?? 0, nodes, radar: radarRow, alerts };
}

/** One line for the status bar. */
export function statusOf(model) {
  if (!model) return 'RuView · starting';
  const parts = [`RuView · ${model.nodes.length} node${model.nodes.length === 1 ? '' : 's'}`];
  if (model.radar) parts.push(`radar ${model.radar.present == null ? '?' : model.radar.present ? 'present' : 'clear'}`);
  if (model.alerts.length) parts.push(`${model.alerts.length} alert${model.alerts.length === 1 ? '' : 's'}`);
  return parts.join(' · ');
}

/** Draw the pane with the surface's own elements ({ Box, Text, Button }). */
export function viewOf(ui, model, { refreshMs, busy, onRefresh, onClose }) {
  const { Box, Text, Button } = ui;
  const line = (children, props = {}) => Text({ ...props, wrap: 'truncate-end', children });
  const rows = [];
  const when = model && Number.isFinite(model.at) ? new Date(model.at).toLocaleTimeString() : '—';
  rows.push(line(model ? `updated ${when} · every ${Math.round(refreshMs / 1000)}s · ${model.decoded}/${model.packets} packets decoded` : 'waiting for the first capture…', { dimColor: true }));
  for (const a of model?.alerts || []) rows.push(Text({ color: a.level === 'bad' ? 'red' : 'yellow', wrap: 'wrap', children: `! ${a.text}` }));
  if (model) {
    rows.push(line('NODES', { bold: true }));
    if (!model.nodes.length) rows.push(line('  none streaming to this machine', { dimColor: true }));
    for (const n of model.nodes) {
      rows.push(Box({ flexDirection: 'row', gap: 2, children: [
        line(n.label.padEnd(10)), line(n.rate.padStart(9)),
        line(`loss ${n.loss}`, n.lossy ? { color: 'yellow' } : {}), line(n.rssi), line(n.shape, { dimColor: true }),
        n.synthetic ? line('SYNTHETIC', { color: 'yellow' }) : null,
      ].filter(Boolean) }));
    }
    if (model.radar) {
      const r = model.radar;
      rows.push(line(`RADAR  ${r.name}`, { bold: true }));
      rows.push(line(`  presence ${r.present == null ? '—' : r.present ? '● detected' : '○ none'} · distance ${r.distance} · heart ${r.heart} · breathing ${r.breathing}`, r.present ? { color: 'green' } : {}));
      rows.push(line('  device-reported values, not validated against a reference', { dimColor: true }));
    }
  }
  rows.push(Box({ flexDirection: 'row', gap: 2, children: [
    Button({ key: 'refresh', label: busy ? 'Refreshing…' : 'Refresh', hotkey: 'r', onPress: onRefresh }),
    Button({ key: 'close', label: 'Close', hotkey: 'c', onPress: onClose }),
  ] }));
  return Box({ flexDirection: 'column', gap: 0, padding: 1, children: rows });
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
  let stopTimer = null;
  let cliPath = null;

  const stop = () => { if (stopTimer) { stopTimer(); stopTimer = null; } };
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
      host.status(statusOf(model));
    } finally {
      busy = false;
      host.invalidate();
    }
  }

  async function open() {
    await host.open({ id: PANE_ID, title: 'RuView', closeOnEscape: true });
    isOpen = true;
    stop();
    stopTimer = cancelOf(host.every(settings.refreshMs, () => { void refresh(); }));
    void refresh();
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
    const ui = await $.ui.resolve(e);
    return viewOf(ui, model, { refreshMs: settings.refreshMs, busy, onRefresh: () => { void refresh(); }, onClose: () => { void close(); } });
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
