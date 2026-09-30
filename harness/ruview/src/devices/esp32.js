// SPDX-License-Identifier: MIT
// ESP32 CSI node network access (ADR-373): receive the node's UDP stream
// (ADR-018 CSI frames, vitals, and other edge packets) and summarize it.
// Receive-only: the socket never sends a byte to a node.

import dgram from 'node:dgram';

/** Packet magics (little-endian u32) from firmware/esp32-csi-node/main. */
export const PACKET_KINDS = Object.freeze({
  0xC5110001: 'csi',
  0xC5110002: 'vitals',
  0xC5110003: 'features',
  0xC5110004: 'fused-vitals',
  0xC5110005: 'compressed',
  0xC5110006: 'feature-state',
  0xC5110007: 'wasm-output',
});

/** Parse one datagram. Unknown or malformed input returns {kind:'unknown'|'malformed'}. */
export function parsePacket(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 4) return { kind: 'malformed', reason: 'short' };
  const magic = buf.readUInt32LE(0);
  const kind = PACKET_KINDS[magic];
  if (!kind) return { kind: 'unknown', magic: `0x${magic.toString(16)}` };
  if (kind === 'csi') {
    if (buf.length < 20) return { kind: 'malformed', reason: 'csi header' };
    const antennas = buf.readUInt8(5);
    const subcarriers = buf.readUInt16LE(6);
    const iqBytes = antennas * subcarriers * 2;
    if (antennas === 0 || subcarriers === 0 || buf.length < 20 + iqBytes) return { kind: 'malformed', reason: 'csi payload' };
    const flags = buf.readUInt8(19);
    // Mean |IQ| amplitude as a cheap liveness/level indicator (int8 I/Q pairs).
    let amp = 0;
    for (let i = 20; i < 20 + iqBytes; i += 2) amp += Math.hypot(buf.readInt8(i), buf.readInt8(i + 1));
    return {
      kind, nodeId: buf.readUInt8(4), antennas, subcarriers,
      freqMhz: buf.readUInt32LE(8), seq: buf.readUInt32LE(12),
      rssi: buf.readInt8(16), noiseFloor: buf.readInt8(17), ppdu: buf.readUInt8(18),
      bw40: Boolean(flags & 1), firstWordZeroed: Boolean(flags & 0x20),
      meanAmplitude: amp / (antennas * subcarriers),
    };
  }
  if (kind === 'vitals') {
    if (buf.length < 32) return { kind: 'malformed', reason: 'vitals length' };
    const flags = buf.readUInt8(5);
    return {
      kind, nodeId: buf.readUInt8(4),
      presence: Boolean(flags & 1), fall: Boolean(flags & 2), motion: Boolean(flags & 4),
      breathingBpm: buf.readUInt16LE(6) / 100, heartBpm: buf.readUInt32LE(8) / 10000,
      rssi: buf.readInt8(12), persons: buf.readUInt8(13),
      motionEnergy: buf.readFloatLE(16), presenceScore: buf.readFloatLE(20), uptimeMs: buf.readUInt32LE(24),
    };
  }
  return { kind, nodeId: buf.length > 4 ? buf.readUInt8(4) : null, bytes: buf.length };
}

/** Aggregate parsed packets into a per-node summary. */
export class NodeStats {
  constructor() { this.nodes = new Map(); this.unknown = 0; this.malformed = 0; this.senders = new Set(); }
  add(pkt, sender) {
    if (sender) this.senders.add(sender);
    if (pkt.kind === 'unknown') { this.unknown += 1; return; }
    if (pkt.kind === 'malformed') { this.malformed += 1; return; }
    const id = pkt.nodeId ?? -1;
    if (!this.nodes.has(id)) this.nodes.set(id, { nodeId: id, packets: {}, rssi: [], lastSeq: null, seqGaps: 0, csi: null, vitals: null });
    const n = this.nodes.get(id);
    n.packets[pkt.kind] = (n.packets[pkt.kind] || 0) + 1;
    if (pkt.kind === 'csi') {
      n.rssi.push(pkt.rssi);
      if (n.lastSeq !== null && pkt.seq > n.lastSeq + 1) n.seqGaps += pkt.seq - n.lastSeq - 1;
      n.lastSeq = pkt.seq;
      n.csi = { antennas: pkt.antennas, subcarriers: pkt.subcarriers, freqMhz: pkt.freqMhz, bw40: pkt.bw40, ppdu: pkt.ppdu, meanAmplitude: Number(pkt.meanAmplitude.toFixed(2)) };
    }
    if (pkt.kind === 'vitals') {
      n.rssi.push(pkt.rssi);
      const { kind, nodeId, ...rest } = pkt;
      n.vitals = rest;
    }
  }
  summary(seconds) {
    const nodes = [...this.nodes.values()].sort((a, b) => a.nodeId - b.nodeId).map((n) => {
      const csi = n.packets.csi || 0;
      const received = csi + n.seqGaps;
      return {
        nodeId: n.nodeId,
        packets: n.packets,
        csiRateHz: Number((csi / seconds).toFixed(2)),
        csiLossFraction: received ? Number((n.seqGaps / received).toFixed(4)) : null,
        rssiMean: n.rssi.length ? Number((n.rssi.reduce((a, b) => a + b, 0) / n.rssi.length).toFixed(1)) : null,
        csi: n.csi,
        lastVitals: n.vitals,
      };
    });
    return { nodes, unknownPackets: this.unknown, malformedPackets: this.malformed, senders: [...this.senders].slice(0, 32) };
  }
}

const BIND_HOSTS = new Set(['0.0.0.0', '127.0.0.1', '::', '::1']);

/**
 * Listen for node packets. args: { udp_port=5005, bind='0.0.0.0', seconds=10, max_packets=200000 }.
 * deps.createSocket is injectable for tests.
 */
export function captureEsp32(args = {}, deps = {}) {
  const port = args.udp_port ?? 5005;
  const bind = args.bind ?? '0.0.0.0';
  const seconds = Math.min(Math.max(args.seconds ?? 10, 1), 300);
  const maxPackets = Math.min(args.max_packets ?? 200_000, 1_000_000);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) return Promise.resolve({ ok: false, reason: 'invalid_port', detail: 'udp_port must be an integer in 1024..65535' });
  if (!BIND_HOSTS.has(bind)) return Promise.resolve({ ok: false, reason: 'invalid_bind', detail: `bind must be one of ${[...BIND_HOSTS].join(', ')}` });
  const createSocket = deps.createSocket || ((type) => dgram.createSocket({ type, reuseAddr: false }));
  const stats = new NodeStats();
  let packets = 0;
  return new Promise((resolve) => {
    const socket = createSocket(bind.includes(':') ? 'udp6' : 'udp4');
    let timer;
    const finish = (extra = {}) => {
      clearTimeout(timer);
      try { socket.close(); } catch { /* already closed */ }
      const summary = stats.summary(seconds);
      const csiNodes = summary.nodes.filter((n) => (n.packets.csi || 0) > 0).length;
      resolve({
        ok: packets > 0,
        ...(packets === 0 ? {
          reason: 'no_packets',
          remedy: `No node packets reached ${bind}:${port}. Check the node's target IP/port (provision.py --target-ip/--target-port), that this host is on the same network, and the firewall (UDP ${port}).`,
        } : {}),
        listen: `${bind}:${port}`, seconds, packets, csiNodes, ...summary,
        evidence: packets > 0 ? 'MEASURED: live UDP packets received on this host' : null,
        ...extra,
      });
    };
    socket.on('error', (error) => {
      if (error.code === 'EADDRINUSE') {
        clearTimeout(timer);
        try { socket.close(); } catch { /* ignore */ }
        resolve({ ok: false, reason: 'port_in_use', detail: `UDP ${port} is already bound (is the sensing server running?)`, remedy: 'Stop the sensing server or capture on another port the nodes target.' });
      } else finish({ ok: false, reason: 'socket_error', detail: error.message });
    });
    socket.on('message', (msg, rinfo) => {
      packets += 1;
      stats.add(parsePacket(msg), rinfo?.address);
      if (packets >= maxPackets) finish({ truncated: true });
    });
    socket.bind(port, bind, () => { timer = setTimeout(() => finish(), seconds * 1000); });
  });
}
