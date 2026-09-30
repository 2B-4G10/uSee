//! ABI contract tests for ruview-kernel (ADR-368). Evidence: SYNTHETIC.

use ruview_kernel::{call, synthesize, Session, SessionConfig, SynthRequest, MAX_SESSIONS};
use serde_json::{json, Value};

fn ok(op: &str, req: Value) -> Value {
    let out: Value = serde_json::from_str(&call(op, &req.to_string())).unwrap();
    assert_eq!(out["ok"], true, "{op} failed: {out}");
    out["result"].clone()
}

fn err_code(op: &str, req: &str) -> String {
    let out: Value = serde_json::from_str(&call(op, req)).unwrap();
    assert_eq!(out["ok"], false, "{op} unexpectedly succeeded: {out}");
    out["error"]["code"].as_str().unwrap().to_string()
}

#[test]
fn info_reports_abi_and_operations() {
    let info = ok("info", json!({}));
    assert_eq!(info["abi"], 1);
    assert_eq!(info["target"], "native");
    assert!(info["operations"]
        .as_array()
        .unwrap()
        .iter()
        .any(|o| o == "analyze"));
}

#[test]
fn synthetic_breathing_and_heart_rates_are_recovered() {
    let req = SynthRequest {
        seconds: 90.0,
        ..SynthRequest::default()
    };
    let frames = synthesize(&req).unwrap();
    assert_eq!(frames.len(), 1800);
    let mut s = Session::new(SessionConfig::default()).unwrap();
    let readings = s.push(&frames).unwrap();
    assert_eq!(readings.len(), 90, "one reading per second at 20 Hz");
    let summary = s.summary();
    let last = summary.last.expect("a final reading");
    assert!(
        (last.respiratory.bpm - 15.0).abs() <= 3.0,
        "SYNTHETIC RR {}",
        last.respiratory.bpm
    );
    assert!(
        (last.heart.bpm - 72.0).abs() <= 8.0,
        "SYNTHETIC HR {}",
        last.heart.bpm
    );
}

#[test]
fn synthesize_is_deterministic() {
    let a = call("synthesize", r#"{"seconds":2,"seed":42}"#);
    let b = call("synthesize", r#"{"seconds":2,"seed":42}"#);
    let c = call("synthesize", r#"{"seconds":2,"seed":43}"#);
    assert_eq!(a, b);
    assert_ne!(a, c);
    let v: Value = serde_json::from_str(&a).unwrap();
    assert_eq!(v["result"]["evidence"], "SYNTHETIC");
}

#[test]
fn session_lifecycle_and_bounds() {
    analyze_matches_streaming_session();
    session_count_is_bounded();
}

fn analyze_matches_streaming_session() {
    let synth = ok("synthesize", json!({ "seconds": 40, "n_subcarriers": 16 }));
    let frames = synth["frames"].clone();
    let config = json!({ "n_subcarriers": 16 });
    let batch = ok(
        "analyze",
        json!({ "config": config, "frames": frames, "include_readings": true }),
    );

    let handle = ok("session_open", json!({ "config": config }))["session"].clone();
    let all = frames.as_array().unwrap();
    for chunk in all.chunks(137) {
        ok(
            "session_push",
            json!({ "session": handle, "frames": chunk }),
        );
    }
    let streamed = ok("session_summary", json!({ "session": handle }));
    assert_eq!(batch["summary"], streamed["summary"]);
    assert_eq!(batch["readings"].as_array().unwrap().len(), 40);
    assert_eq!(
        ok("session_close", json!({ "session": handle }))["closed"],
        true
    );
    assert_eq!(
        err_code("session_summary", &json!({ "session": handle }).to_string()),
        "unknown_session"
    );
}

#[test]
fn rejects_untrusted_input() {
    assert_eq!(err_code("nope", "{}"), "unknown_operation");
    assert_eq!(err_code("analyze", "not json"), "invalid_request");
    assert_eq!(
        err_code("analyze", r#"{"frames":[],"extra":1}"#),
        "invalid_request"
    );
    assert_eq!(
        err_code("validate_config", r#"{"n_subcarriers":0}"#),
        "invalid_request"
    );
    assert_eq!(
        err_code("validate_config", r#"{"n_subcarriers":513}"#),
        "invalid_request"
    );
    assert_eq!(
        err_code("validate_config", r#"{"sample_rate_hz":0.5}"#),
        "invalid_request"
    );
    // Wrong frame width and non-finite-by-magnitude values are rejected before any processing.
    assert_eq!(
        err_code(
            "analyze",
            r#"{"config":{"n_subcarriers":2},"frames":[{"amplitudes":[1]}]}"#
        ),
        "invalid_request"
    );
    assert_eq!(
        err_code(
            "analyze",
            r#"{"config":{"n_subcarriers":1},"frames":[{"amplitudes":[1e300]}]}"#
        ),
        "invalid_request"
    );
    assert_eq!(
        err_code(
            "analyze",
            r#"{"config":{"n_subcarriers":1},"frames":[{"amplitudes":[1],"phases":[1,2]}]}"#
        ),
        "invalid_request"
    );
    assert_eq!(
        err_code("synthesize", r#"{"seconds":3600,"sample_rate_hz":1000}"#),
        "limit_exceeded"
    );
    let huge = format!(
        "{{\"frames\":[],\"pad\":\"{}\"}}",
        "x".repeat(ruview_kernel::MAX_INPUT_BYTES)
    );
    assert_eq!(err_code("analyze", &huge), "limit_exceeded");
}

// The session registry is process-global, so the lifecycle and bound checks
// share one test to avoid racing parallel test threads.
fn session_count_is_bounded() {
    let mut opened = Vec::new();
    let mut limited = false;
    for _ in 0..=MAX_SESSIONS {
        let out: Value = serde_json::from_str(&call("session_open", "{}")).unwrap();
        if out["ok"] == true {
            opened.push(out["result"]["session"].clone());
        } else {
            assert_eq!(out["error"]["code"], "limit_exceeded");
            limited = true;
            break;
        }
    }
    assert!(limited, "registry must refuse more than MAX_SESSIONS");
    for id in opened {
        ok("session_close", json!({ "session": id }));
    }
}
