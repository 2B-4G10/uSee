import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildSshArgs, runRemote, saveHost, shellQuote, validateHost } from '../src/remote.js';
import { isRemoteReadOnlyTool, runTool, TOOLS } from '../src/tools.js';
import { validateArguments } from '../src/policy.js';

test('host entries reject ssh option injection and shell metacharacters', () => {
  for (const ssh of ['-oProxyCommand=id', 'pi;id', 'user@pi id', '$(id)@pi', 'user@-pi', 'pi`id`', '']) {
    assert.throws(() => validateHost({ name: 'pi', ssh }), /ssh must be/, ssh);
  }
  for (const name of ['Pi', '../x', 'a b', '-x']) assert.throws(() => validateHost({ name, ssh: 'pi' }), /name must/);
  assert.throws(() => validateHost({ name: 'pi', ssh: 'pi', version: 'latest' }), /semver/);
  assert.deepEqual(validateHost({ name: 'lab-pi', ssh: 'ruv@pi.local', port: 2222 }), { name: 'lab-pi', ssh: 'ruv@pi.local', port: 2222 });
});

test('remote command is fully shell-quoted and ssh options are fixed', () => {
  assert.equal(shellQuote("a'b"), `'a'\\''b'`);
  const argv = buildSshArgs({ ssh: 'ruv@pi.local', port: 2222 }, ['npx', '-y', '@ruvnet/ruview@0.7.0', 'call', 'ruview_doctor', '--read-only', '--args-json', '{"x":"$(id); `rm`"}']);
  assert.deepEqual(argv.slice(0, 11), ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'StrictHostKeyChecking=yes', '-T', '-p', '2222', '--', 'ruv@pi.local']);
  const remote = argv.at(-1);
  // Every token is single-quoted, so the remote shell sees literal text.
  const sh = spawnSync('sh', ['-c', `for a in ${remote}; do printf '%s\\n' "$a"; done`], { encoding: 'utf8' });
  assert.equal(sh.stdout.trim().split('\n').at(-1), '{"x":"$(id); `rm`"}');
});

async function withHosts(fn) {
  const dir = mkdtempSync(join(tmpdir(), 'ruview-hosts-'));
  const path = join(dir, 'ruview', 'hosts.json');
  try { return await fn(path); } finally { rmSync(dir, { recursive: true, force: true }); }
}

const baseDeps = (path, execResult) => {
  const calls = [];
  return {
    calls,
    hostsPath: path,
    which: (b) => (b === 'ssh' ? '/usr/bin/ssh' : null),
    version: '0.7.0',
    readOnlyTool: isRemoteReadOnlyTool,
    validate: (name, args) => (TOOLS[name] ? validateArguments(TOOLS[name].inputSchema, args) : ['unknown']),
    exec: async (cmd, args) => { calls.push([cmd, ...args]); return execResult; },
  };
};

test('hosts file is private and remote runs are read-only, validated, and version-pinned', async () => {
  await withHosts(async (path) => {
    saveHost({ name: 'pi', ssh: 'ruv@pi.local' }, path);
    assert.equal(statSync(path).mode & 0o777, 0o600);
    assert.equal(JSON.parse(readFileSync(path, 'utf8')).hosts[0].name, 'pi');

    const ok = baseDeps(path, { ok: true, code: 0, stdout: JSON.stringify({ ok: true, devices: [] }), stderr: '', error: null });
    const r = await runRemote({ host: 'pi', tool: 'ruview_devices_scan', args: {} }, ok);
    assert.equal(r.ok, true);
    assert.deepEqual(r.remote, { host: 'pi', ssh: 'ruv@pi.local', version: '0.7.0' });
    assert.match(ok.calls[0].at(-1), /'@ruvnet\/ruview@0\.7\.0' 'call' 'ruview_devices_scan' '--read-only'/);

    assert.equal((await runRemote({ host: 'pi', tool: 'ruview_node_flash', args: {} }, ok)).reason, 'remote_tool_not_allowed');
    assert.equal((await runRemote({ host: 'pi', tool: 'ruview_train', args: {} }, ok)).reason, 'remote_tool_not_allowed');
    assert.equal((await runRemote({ host: 'pi', tool: 'ruview_spaces_list', args: {} }, ok)).reason, 'remote_tool_not_allowed');
    assert.equal((await runRemote({ host: 'pi', tool: 'ruview_host_run', args: {} }, ok)).reason, 'remote_tool_not_allowed', 'no host hopping');
    assert.equal((await runRemote({ host: 'pi', tool: 'ruview_mmwave_read', args: { port: 'x;id' } }, ok)).reason, 'invalid_arguments');
    assert.equal((await runRemote({ host: 'nope', tool: 'ruview_doctor' }, ok)).reason, 'unknown_host');

    const keyFail = baseDeps(path, { ok: false, code: 255, stdout: '', stderr: 'Host key verification failed.', error: 'x' });
    const kr = await runRemote({ host: 'pi', tool: 'ruview_doctor', args: {} }, keyFail);
    assert.equal(kr.reason, 'host_key_unverified');
    assert.match(kr.remedy, /ssh ruv@pi\.local/);
    const auth = baseDeps(path, { ok: false, code: 255, stdout: '', stderr: 'Permission denied (publickey).', error: 'x' });
    assert.equal((await runRemote({ host: 'pi', tool: 'ruview_doctor', args: {} }, auth)).reason, 'ssh_auth_failed');
  });
});

test('MCP remote runs need remote-host plus the remote tool grant; list is read-only', async () => {
  assert.equal((await runTool('ruview_host_run', { host: 'pi', tool: 'ruview_doctor' }, { source: 'mcp', grants: [] })).requiredGrant, 'remote-host');
  const needsDevice = await runTool('ruview_host_run', { host: 'pi', tool: 'ruview_devices_scan' }, { source: 'mcp', grants: ['remote-host'] });
  assert.deepEqual([needsDevice.reason, needsDevice.requiredGrant], ['authority_denied', 'device-access']);
  const list = await runTool('ruview_host_list', {}, { source: 'mcp', grants: [] });
  assert.equal(typeof list.ok, 'boolean');
});

test('`call --read-only` refuses mutating tools (what remote hosts execute)', () => {
  const cli = join(import.meta.dirname, '..', 'bin', 'cli.js');
  const refused = spawnSync(process.execPath, [cli, 'call', 'ruview_node_flash', '--read-only', '--args-json', '{"port":"COM7","bundle":"/x","confirm":true}'], { encoding: 'utf8' });
  assert.equal(refused.status, 1);
  assert.equal(JSON.parse(refused.stdout).reason, 'remote_tool_not_allowed');
  const allowed = spawnSync(process.execPath, [cli, 'call', 'ruview_train_gate', '--read-only', '--args-json', '{"model_score":0.6}'], { encoding: 'utf8' });
  assert.equal(JSON.parse(allowed.stdout).verdict, 'FAIL');
});
