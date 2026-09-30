//! napi-rs transport for `ruview-kernel` (ADR-368).
//!
//! Deliberately exposes only the kernel's single string ABI so the native
//! and WASM backends of `@ruvnet/ruview-kernel` stay behaviourally identical.
#![deny(clippy::all)]

use napi_derive::napi;

/// JSON ABI version; must equal the WASM module's `rvk_abi_version()`.
#[napi]
pub fn abi_version() -> u32 {
    ruview_kernel::ABI_VERSION
}

/// Run one kernel operation: operation name + JSON request → JSON envelope.
/// A Rust panic is converted into an `internal` error envelope.
#[napi]
pub fn call(op: String, input: String) -> String {
    std::panic::catch_unwind(|| ruview_kernel::call(&op, &input)).unwrap_or_else(|_| {
        r#"{"ok":false,"error":{"code":"internal","message":"kernel panicked"}}"#.to_string()
    })
}
