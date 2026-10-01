# ADR-373: Host device access layer — ESP32, mmWave, LiDAR

- **Status**: proposed
- **Date**: 2026-09-30
- **Deciders**: RuView maintainers; acceptance pending
- **Tags**: hardware, esp32, mmwave, lidar, serial, udp, mcp, least-authority
- **Related**: ADR-018 (CSI frame), ADR-063 (mmWave fusion), ADR-320
  (sensor HAL), ADR-369 (npm operator surface), ADR-370 (flashing),
  ADR-374 (remote hosts), `integrations/iphone-lidar`

## Context

Operators and agents need to answer basic hardware questions, such as "is the
node streaming?", "is the radar talking?" and "does the LiDAR see the room?",
before calibration, fusion or training. Until now each modality had its own
tool:

- the ESP32 serial monitor;
- `scripts/mmwave_fusion_bridge.py`;
- the iPhone LiDAR web relay;
- the sensing server itself.

None of these was reachable through the npm harness, its MCP server or its
SDK.

The package must stay free of runtime dependencies (ADR-263). Node has no
serial API. Hardware access is also authority: an MCP client should not get
it by default.

## Decision

Add `src/devices/` to `@ruvnet/ruview` (0.7.0) with four read-oriented tools.

| Tool | Transport | What it returns |
|---|---|---|
| `ruview_devices_scan` | pyserial port list | ports with USB VID:PID, bridge chip, likely roles (esp32 / mmwave / rplidar), and the command that confirms each role |
| `ruview_esp32_capture` | `node:dgram` UDP, receive-only | per node: packet counts by kind (magics `0xC5110001`–`0xC5110007`), CSI rate, sequence loss, RSSI, antennas/subcarriers/frequency, latest device vitals |
| `ruview_mmwave_read` | serial | MR60BHA2 (60 GHz, 115200) and LD2410 (24 GHz, 256000) frames, checksum errors, presence, distance, device-reported heart and breathing rate |
| `ruview_lidar_read` | serial or WebSocket | RPLIDAR SCAN summary (points, revolutions, range, angular coverage), or iPhone relay depth statistics |

### Rules

1. **Firmware-identical parsing.**
   - The MR60BHA2 and LD2410 parsers are ports of
     `firmware/esp32-csi-node/main/mmwave_sensor.c`: same SOF, `~XOR` header
     and data checksums, 30-byte payload cap, and F4F3F2F1…F8F7F6F5 framing.
   - ESP32 packets are parsed as the little-endian structs in `csi_collector`
     and `edge_processing.h`.
   - Host and node therefore agree on what a valid frame is.
2. **Serial without dependencies.** A fixed Python script (the pyserial
   "pump") reads the port for a bounded time and number of bytes.
   - Port, baud, duration and command bytes are passed as argv and validated
     first: the port regex, a fixed baud set, and command bytes of at most 64
     hex bytes.
   - Output is lowercase hex, so the secret-redaction layer cannot corrupt
     binary data.
   - DTR control is best-effort and reported when a port does not support it.
3. **Receive-only network.**
   - The ESP32 capture binds a local UDP port (bind address from a fixed set,
     port ≥ 1024) and never sends to a node.
   - `EADDRINUSE` is reported as "sensing server already bound".
4. **Minimal actuation.** An RPLIDAR scan sends `A5 20` (start) and `A5 25`
   (stop), and drives DTR low (the A1 motor). It spins the motor but writes no
   configuration. Nothing else is written to any device.
5. **No raw sensor data in results.**
   - iPhone depth frames are validated with the same rules as
     `integrations/iphone-lidar/web/codec.mjs` and reduced to statistics.
   - The relay token comes only from `RUVIEW_LIDAR_TOKEN`. It is rejected in
     the URL and never echoed.
6. **Authority.**
   - All four tools require the new `device-access` MCP grant.
   - The CLI and SDK run them directly, as trusted local code.
   - Serial port names are pattern-checked in the schema and again in the
     driver. `/dev/pts/*` is deliberately excluded: it could read another
     terminal's input.
7. **Evidence.**
   - A successful read is tagged `MEASURED` as a *reading* on this host.
   - mmWave vitals are labelled as the radar's own estimates.
   - None of these outputs is an accuracy claim.

## Evidence

Development container, Linux x64, Node 22. No physical sensors were attached.

- **ESP32 UDP**: a simulated node sent 60 ADR-018 CSI frames plus 3 vitals
  packets to `127.0.0.1:5005` through `ruview esp32`.
  - Result: 1 node, CSI rate 15 Hz, zero sequence loss, vitals decoded
    (15.2 bpm / 69 bpm, SYNTHETIC).
  - Unit test on real loopback: 2 dropped sequence numbers out of 7 produce a
    loss fraction of 0.2857.
- **MR60BHA2**, emulated on a pseudo-terminal and read through real pyserial:
  - auto-detected `mr60bha2`;
  - 200 frames, 0 checksum errors;
  - presence 1.0, distance 87 cm, 14.8 / 68.5 bpm (the emulator's values,
    SYNTHETIC).
- **RPLIDAR**, emulated on a pseudo-terminal that answers `A5 20`:
  - 3564 points, 99 revolutions, full angular coverage;
  - the missing DTR support was reported.
- **iPhone LiDAR**, the real relay (`integrations/iphone-lidar/web/relay.mjs`)
  with a simulated phone:
  - 30 frames at 10 fps, median depth 1.824 m (SYNTHETIC);
  - a wrong token gives `connect_failed` with a remedy.
- **Not yet done**: real ESP32-S3/C6, MR60BHA2, LD2410, RPLIDAR and iPhone
  captures. Each modality needs one real capture before its parser is called
  hardware-validated.

## Amendment 1 (2026-10-01): e2e suite and latency

- `harness/ruview/test/e2e/devices.e2e.mjs` (`npm run test:e2e:devices`, CI
  workflow `ruview-device-e2e.yml`) automates the emulated-device runs above
  through the real CLI:
  - MR60BHA2 and RPLIDAR on pseudo-terminals, read via real pyserial;
  - an ESP32 UDP node;
  - the real iPhone relay.
- The harness still refuses `/dev/pts/*` port names. The suite links each pty
  to `/dev/ttyRUVIEWE2E<n>` with root or `sudo -n`.
- `RUVIEW_E2E_REQUIRE=1` turns missing prerequisites into failures.
- mmWave auto-detect keeps its probe frames (≤ 2 s per model) and reads only
  the remainder of the window. A 5 s request previously cost about 9 s.
- `ruview_lidar_read` (iPhone) gains `max_frames` and returns immediately on
  a refused connection.
- MEASURED wall time for the device e2e suite on the development container:
  19.6 s before these changes, 5.6 s after (concurrent cases, no redundant
  re-reads).

## Consequences

- One command per modality works across CLI, MCP and SDK, with actionable
  failures (`pyserial_missing`, `port_open_failed`, `no_valid_frames`,
  `port_in_use`, `connect_failed`).
- Python and pyserial become the serial dependency. `doctor` reports both.
- The package's unpacked-size ceiling rises to 320 KiB (from 224 KiB) in
  both npm workflows, with the published package still free of runtime
  dependencies.
- Follow-up: feed these readers into `ruview-hal` observations (ADR-320)
  for fusion.
