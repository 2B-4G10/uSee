// The ruview-live Claude Code mod (ADR-377): its pure parts run under plain
// Node; the engine-level behaviour is covered by `claude plugin test mod`.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { cliPathOf, commandsOf, modelOf, resultOf, settingsOf, statusOf, viewOf } from '../mod/hooks/register.mjs';

const capture = {
  ok: true, packets: 120, decodedPackets: 118,
  heartbeatOnlySenders: [{ address: '192.168.1.67', heartbeats: 30 }],
  nodes: [
    { source: 'esp32', nodeId: 42, csiRateHz: 4.73, csiLossFraction: 0, rssiMean: -63.1, csi: { shape: '1x64' } },
    { source: 'realtek', nodeId: 3, csiRateHz: 348, csiLossFraction: 0.08, rssiMean: -39, csi: { shape: '1x52', synthetic: true } },
  ],
};
const radar = { ok: true, host: '192.168.1.102', device: { name: 'mr60-kit' }, presentNow: true, distanceCmMean: 40.2, heartBpmMean: 75.3, breathingBpmMean: 10.7 };

test('settings are bounded and radar hosts are validated', () => {
  assert.deepEqual(settingsOf({}), { udpPort: 5005, radarHost: '', refreshMs: 15000, captureSeconds: 3 });
  const s = settingsOf({ udpPort: 80, radarHost: ' 192.168.1.102 ', refreshSeconds: 1, captureSeconds: 99 });
  assert.deepEqual(s, { udpPort: 1024, radarHost: '192.168.1.102', refreshMs: 5000, captureSeconds: 10 });
  assert.equal(settingsOf({ radarHost: 'evil host; rm -rf /' }).radarHost, '');
});

test('commands are fixed argv arrays for read-only CLI verbs', () => {
  const c = commandsOf(settingsOf({ radarHost: 'kit.local' }));
  assert.deepEqual(c.capture, ['esp32', '--seconds', '3', '--udp-port', '5005', '--json']);
  assert.deepEqual(c.radar, ['mmwave', '--source', 'esphome', '--host', 'kit.local', '--seconds', '3', '--json']);
  assert.equal(commandsOf(settingsOf({})).radar, null);
  for (const argv of [c.capture, c.radar]) assert.ok(!argv.some((a) => /flash|calibrate|train|confirm/.test(a)), 'never a write verb');
  assert.equal(cliPathOf('C:\\pkg\\mod\\'), 'C:\\pkg\\mod/../bin/cli.js');
  assert.equal(cliPathOf('/pkg/mod'), '/pkg/mod/../bin/cli.js');
});

test('CLI output parses to its result or an honest failure', () => {
  assert.deepEqual(resultOf({ exitCode: 0, stdout: '{"ok":true}', stderr: '' }), { ok: true });
  assert.deepEqual(resultOf({ exitCode: 1, stdout: '{"ok":false,"reason":"no_packets"}', stderr: '' }), { ok: false, reason: 'no_packets' });
  assert.equal(resultOf({ exitCode: 127, stdout: '', stderr: 'node: not found' }).reason, 'cli_error');
  assert.equal(resultOf(null), null);
});

test('model and status summarise nodes, radar and alerts', () => {
  const m = modelOf(capture, radar, Date.UTC(2026, 9, 1, 12));
  assert.deepEqual(m.nodes.map((n) => [n.label, n.rate, n.loss, n.lossy, n.synthetic]), [['esp32 42', '4.7 Hz', '0.0%', false, false], ['realtek 3', '348.0 Hz', '8.0%', true, true]]);
  assert.match(m.alerts[0].text, /192\.168\.1\.67 sends heartbeats but no CSI/);
  assert.deepEqual([m.radar.present, m.radar.distance, m.radar.heart], [true, '40.2 cm', '75.3 bpm']);
  assert.equal(statusOf(m), 'RuView · 2 nodes · radar present · 1 alert');
  const failed = modelOf({ ok: false, reason: 'port_in_use', remedy: 'stop the sensing server' }, { ok: false, reason: 'host_not_private', detail: '8.8.8.8' }, 0);
  assert.equal(failed.alerts.length, 2);
  assert.equal(statusOf(failed), 'RuView · 0 nodes · 2 alerts');
  assert.equal(statusOf(null), 'RuView · starting');
});

test('the pane draws with the surface elements and wires both buttons', () => {
  const el = (type) => (props) => ({ type, props });
  const presses = [];
  const tree = viewOf({ Box: el('Box'), Text: el('Text'), Button: el('Button') }, modelOf(capture, radar, 0), {
    refreshMs: 15000, busy: false, onRefresh: () => presses.push('refresh'), onClose: () => presses.push('close'),
  });
  const flat = [];
  const walk = (n) => { if (!n || typeof n !== 'object') return; flat.push(n); [].concat(n.props?.children ?? []).forEach(walk); };
  walk(tree);
  const texts = flat.filter((n) => n.type === 'Text').map((n) => String(n.props.children));
  assert.ok(texts.some((t) => t.includes('heartbeats but no CSI')));
  assert.ok(texts.some((t) => t.startsWith('RADAR')));
  assert.ok(texts.some((t) => t.includes('not validated')));
  assert.ok(texts.some((t) => t === 'SYNTHETIC'));
  const buttons = flat.filter((n) => n.type === 'Button');
  assert.deepEqual(buttons.map((b) => [b.props.key, b.props.hotkey]), [['refresh', 'r'], ['close', 'c']]);
  buttons.forEach((b) => b.props.onPress());
  assert.deepEqual(presses, ['refresh', 'close']);
  const waiting = viewOf({ Box: el('Box'), Text: el('Text'), Button: el('Button') }, null, { refreshMs: 15000, busy: true, onRefresh() {}, onClose() {} });
  assert.match(JSON.stringify(waiting), /waiting for the first capture/);
});

test('the pane never prints "Invalid Date" (live bug: $.clock.now() resolves a promise)', () => {
  const el = (type) => (props) => ({ type, props });
  const ui = { Box: el('Box'), Text: el('Text'), Button: el('Button') };
  const opts = { refreshMs: 15000, busy: false, onRefresh() {}, onClose() {} };
  const pending = JSON.stringify(viewOf(ui, modelOf(capture, null, Promise.resolve(0)), opts));
  assert.doesNotMatch(pending, /Invalid Date/);
  assert.match(pending, /updated — · every 15s/);
  const timed = JSON.stringify(viewOf(ui, modelOf(capture, null, Date.UTC(2026, 9, 1, 12, 0, 0)), opts));
  assert.doesNotMatch(timed, /Invalid Date|updated —/);
});

test('refresh awaits the clock before building the model', () => {
  const src = readFileSync(new URL('../mod/hooks/register.mjs', import.meta.url), 'utf8');
  assert.match(src, /model = modelOf\(.*await host\.now\(\)\);/);
});

test('the mod manifest and hooks module are a valid plugin shape', () => {
  const plugin = JSON.parse(readFileSync(new URL('../mod/.claude-plugin/plugin.json', import.meta.url), 'utf8'));
  const hooks = JSON.parse(readFileSync(new URL('../mod/hooks/hooks.json', import.meta.url), 'utf8'));
  assert.equal(plugin.name, 'ruview-live');
  assert.deepEqual(hooks.modules, ['./register.mjs']);
  const src = readFileSync(new URL('../mod/hooks/register.mjs', import.meta.url), 'utf8');
  assert.doesNotMatch(src, /^\s*import\s/m, 'a mod imports nothing but its own files');
});
