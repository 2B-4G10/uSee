#!/usr/bin/env python3
"""Upstream contract check for the uSee Android app.

uSee talks to RuView firmware and the RuView sensing server over wire
formats defined in this repository's upstream sources. This tool extracts
every value the app depends on, compares it with the committed lock file
(android/upstream-contract.lock.json) and, with --update, regenerates the
Kotlin constants the app compiles against
(app/src/main/java/com/usee/scanner/core/UpstreamContract.kt).

Exit codes for --check:
  0  no change
  3  changes the app absorbs automatically (constants / additive fields)
  4  changes that need a human to review app code (layouts, removed fields,
     values that could not be extracted)

Standard library only; paths are resolved relative to --repo.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ANDROID = Path(__file__).resolve().parent.parent
LOCK = ANDROID / "upstream-contract.lock.json"
KOTLIN = ANDROID / "app/src/main/java/com/usee/scanner/core/UpstreamContract.kt"

FW = "firmware/esp32-csi-node/main"
SRV = "v2/crates/wifi-densepose-sensing-server/src"

# name -> (file, regex, kind). The first capture group is the value.
VALUES = {
    "CSI_MAGIC": (f"{FW}/csi_collector.h", r"#define\s+CSI_MAGIC\s+(0x[0-9A-Fa-f]+)", "hex"),
    "CSI_HEADER_SIZE": (f"{FW}/csi_collector.h", r"#define\s+CSI_HEADER_SIZE\s+(\d+)", "int"),
    "VITALS_MAGIC": (f"{FW}/edge_processing.h", r"#define\s+EDGE_VITALS_MAGIC\s+(0x[0-9A-Fa-f]+)", "hex"),
    "FUSED_MAGIC": (f"{FW}/edge_processing.h", r"#define\s+EDGE_FUSED_MAGIC\s+(0x[0-9A-Fa-f]+)", "hex"),
    "ESP32_UDP_PORT": (f"{FW}/Kconfig.projbuild", r"config\s+CSI_TARGET_PORT\b[^\n]*\n(?:(?!\n\s*config\s)[\s\S])*?default\s+(\d+)", "int"),
    "ESP32_STATUS_PORT": (f"{FW}/ota_update.c", r"#define\s+OTA_PORT\s+(\d+)", "int"),
    "ESP32_STATUS_PATH": (f"{FW}/ota_update.c", r'\.uri\s*=\s*"([^"]+)",\s*\.method\s*=\s*HTTP_GET,\s*\.handler\s*=\s*ota_status_handler', "str"),
    "SERVER_HTTP_PORT": (f"{SRV}/main.rs", r'default_value\s*=\s*"(\d+)"\)\]\s*http_port:\s*u16', "int"),
    "SERVER_WS_PORT": (f"{SRV}/main.rs", r'default_value\s*=\s*"(\d+)"\)\]\s*ws_port:\s*u16', "int"),
    "SERVER_UDP_PORT": (f"{SRV}/main.rs", r'default_value\s*=\s*"(\d+)"\)\]\s*udp_port:\s*u16', "int"),
    "SERVER_WS_PATH": (f"{SRV}/main.rs", r'\.route\("([^"]+)",\s*get\(ws_sensing_handler\)\)', "str"),
    "SERVER_HEALTH_PATH": (f"{SRV}/main.rs", r'\.route\("([^"]+)",\s*get\(health\)\)', "str"),
    "MDNS_SERVICE_TYPE": (f"{SRV}/discovery.rs", r'SERVICE_TYPE:\s*&str\s*=\s*"([^"]+)"', "str"),
}

# Strings the app matches on; they must still exist upstream.
PRESENCE = {
    "esp32_status_key_running_partition": (f"{FW}/ota_update.c", r'\\"running_partition\\"'),
    "health_status_ok": (f"{SRV}/main.rs", r'"status":\s*"ok"'),
    "health_source_key": (f"{SRV}/main.rs", r'"source":\s*s\.effective_source\(\)'),
    "motion_present_moving": (f"{SRV}/main.rs", r'"present_moving"'),
    "motion_present_still": (f"{SRV}/main.rs", r'"present_still"'),
    "motion_absent": (f"{SRV}/main.rs", r'"absent"'),
    "host_guard_421": (f"{SRV}/host_validation.rs", r"StatusCode::MISDIRECTED_REQUEST"),
    "host_guard_marker": (f"{SRV}/host_validation.rs", r"DNS-rebinding"),
    "ws_bearer_header": (f"{SRV}/bearer_auth.rs", r"AUTHORIZATION"),
}

# Structures whose shape the app decodes.
#   kind "c"    packed C struct: any change is a layout change (manual)
#   kind "rust" serde struct: only removing a field the app reads is manual
#   kind "doc"  layout comment of the raw CSI frame (manual)
STRUCTS = {
    "edge_vitals_pkt_t": (f"{FW}/edge_processing.h", r"\{([^{}]*)\}\s*edge_vitals_pkt_t\s*;", "c", []),
    "edge_fused_vitals_pkt_t": (f"{FW}/edge_processing.h", r"\{([^{}]*)\}\s*edge_fused_vitals_pkt_t\s*;", "c", []),
    "csi_frame_layout": (f"{FW}/csi_collector.c", r"Layout:([\s\S]*?)\*/", "doc", []),
    "SensingUpdate": (f"{SRV}/main.rs", r"\bstruct\s+SensingUpdate\s*\{([^{}]*)\}", "rust",
                      ["source", "nodes", "features", "classification", "vital_signs", "estimated_persons"]),
    "ClassificationInfo": (f"{SRV}/main.rs", r"\bstruct\s+ClassificationInfo\s*\{([^{}]*)\}", "rust",
                           ["motion_level", "presence", "confidence"]),
    "FeatureInfo": (f"{SRV}/main.rs", r"\bstruct\s+FeatureInfo\s*\{([^{}]*)\}", "rust",
                    ["mean_rssi", "motion_band_power"]),
    "VitalSigns": (f"{SRV}/vital_signs.rs", r"\bstruct\s+VitalSigns\s*\{([^{}]*)\}", "rust",
                   ["breathing_rate_bpm", "heart_rate_bpm"]),
}


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*[\s\S]*?\*/", " ", text)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


def normalize(text: str) -> str:
    return " ".join(text.split())


def fields(kind: str, body: str) -> list[str]:
    body = strip_comments(body)
    if kind == "rust":
        body = re.sub(r"#\[[^\]]*\]", " ", body)
        # One field per line in these structs; `::` paths are not fields.
        return re.findall(r"^\s*(?:pub(?:\([^)]*\))?\s+)?(\w+)\s*:(?!:)", body, re.MULTILINE)
    if kind == "c":
        return re.findall(r"(\w+)\s*(?:\[[^\]]*\])?\s*;", body)
    return []


def read(repo: Path, rel: str) -> str | None:
    p = (repo / rel).resolve()
    if repo.resolve() not in p.parents or not p.is_file():
        return None
    return p.read_text(encoding="utf-8", errors="replace")


def extract(repo: Path) -> dict:
    out: dict = {"values": {}, "presence": {}, "structs": {}, "errors": []}
    for name, (rel, rx, kind) in VALUES.items():
        text = read(repo, rel)
        m = re.search(rx, text or "")
        if not m:
            out["errors"].append(f"value {name}: pattern not found in {rel}")
            continue
        raw = m.group(1)
        out["values"][name] = {"hex": raw.upper().replace("0X", "0x"), "int": int(raw) if raw.isdigit() else raw,
                               "str": raw}[kind]
    for name, (rel, rx) in PRESENCE.items():
        out["presence"][name] = bool(re.search(rx, read(repo, rel) or ""))
        if not out["presence"][name]:
            out["errors"].append(f"marker {name}: no longer found in {rel}")
    for name, (rel, rx, kind, _used) in STRUCTS.items():
        m = re.search(rx, read(repo, rel) or "")
        if not m:
            out["errors"].append(f"struct {name}: not found in {rel}")
            continue
        body = m.group(1)
        norm = normalize(body if kind == "doc" else strip_comments(body))
        out["structs"][name] = {
            "sha256": hashlib.sha256(norm.encode()).hexdigest(),
            "fields": fields(kind, body),
        }
    return out


def compare(old: dict, new: dict) -> tuple[int, list[str]]:
    lines: list[str] = []
    severity = 0
    for e in new["errors"]:
        lines.append(f"- :red_circle: {e}")
        severity = 4
    for k, v in new["values"].items():
        prev = old.get("values", {}).get(k)
        if prev != v:
            lines.append(f"- :large_blue_circle: `{k}`: `{prev}` → `{v}` (app constant regenerated)")
            severity = max(severity, 3)
    for k, (_rel, _rx, kind, used) in STRUCTS.items():
        n = new["structs"].get(k)
        o = old.get("structs", {}).get(k)
        if n is None or o is None or n["sha256"] == o["sha256"]:
            continue
        added = [f for f in n["fields"] if f not in o["fields"]]
        removed = [f for f in o["fields"] if f not in n["fields"]]
        if kind in ("c", "doc") or any(f in used for f in removed):
            lines.append(f"- :red_circle: `{k}` changed (added {added or '—'}, removed {removed or '—'}); "
                         "review the decoder in the app")
            severity = 4
        else:
            lines.append(f"- :white_circle: `{k}` changed (added {added or '—'}, removed {removed or '—'}); "
                         "no field the app reads was removed")
            severity = max(severity, 3)
    return severity, lines


def kotlin(values: dict) -> str:
    v = values
    service = v["MDNS_SERVICE_TYPE"]
    # Android NsdManager takes the type without the ".local." domain.
    nsd = service[: -len("local.")] if service.endswith("local.") else service
    return f"""package com.usee.scanner.core

// Generated by android/tools/upstream_contract.py from the RuView sources.
// Do not edit by hand: run `python3 android/tools/upstream_contract.py --update`.

/** Wire-level values shared with RuView firmware and the sensing server. */
object UpstreamContract {{
    const val CSI_MAGIC = {v["CSI_MAGIC"]}L
    const val CSI_HEADER_SIZE = {v["CSI_HEADER_SIZE"]}
    const val VITALS_MAGIC = {v["VITALS_MAGIC"]}L
    const val FUSED_MAGIC = {v["FUSED_MAGIC"]}L

    const val ESP32_UDP_PORT = {v["ESP32_UDP_PORT"]}
    const val ESP32_STATUS_PORT = {v["ESP32_STATUS_PORT"]}
    const val ESP32_STATUS_PATH = "{v["ESP32_STATUS_PATH"]}"

    const val SERVER_HTTP_PORT = {v["SERVER_HTTP_PORT"]}
    const val SERVER_WS_PORT = {v["SERVER_WS_PORT"]}
    const val SERVER_WS_PATH = "{v["SERVER_WS_PATH"]}"
    const val SERVER_HEALTH_PATH = "{v["SERVER_HEALTH_PATH"]}"

    const val MDNS_SERVICE_TYPE = "{nsd}"
}}
"""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", default=str(ANDROID.parent), help="repository root to read upstream sources from")
    ap.add_argument("--update", action="store_true", help="rewrite the lock file and Kotlin constants")
    ap.add_argument("--report", help="write a Markdown report to this path")
    args = ap.parse_args()

    repo = Path(args.repo)
    new = extract(repo)
    old = json.loads(LOCK.read_text()) if LOCK.exists() else {}
    severity, lines = compare(old, new)

    gen_ok = KOTLIN.exists() and not new["errors"] and KOTLIN.read_text() == kotlin(new["values"])
    if not gen_ok and not new["errors"] and severity == 0:
        lines.append("- :large_blue_circle: UpstreamContract.kt is out of date with the lock file")
        severity = 3

    status = {0: "unchanged", 3: "auto-updated", 4: "needs review"}[severity]
    report = "\n".join([f"### Upstream contract: {status}", ""] + (lines or ["No change in any value the app depends on."]))
    print(report)
    if args.report:
        Path(args.report).write_text(report + "\n", encoding="utf-8")

    if args.update:
        if new["errors"]:
            print("\nNot updating: some values could not be extracted (see above).", file=sys.stderr)
            return 4
        lock = {k: new[k] for k in ("values", "presence", "structs")}
        LOCK.write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        KOTLIN.write_text(kotlin(new["values"]), encoding="utf-8")
        return 4 if severity == 4 else 0
    return severity


if __name__ == "__main__":
    sys.exit(main())
