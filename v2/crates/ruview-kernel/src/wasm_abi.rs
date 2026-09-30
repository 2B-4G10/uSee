//! Zero-import WebAssembly C ABI (wasm32 only).
//!
//! Protocol (all lengths in bytes, UTF-8):
//! 1. `rvk_alloc(len)` twice — for the operation name and the request JSON —
//!    and copy the bytes into linear memory.
//! 2. `rvk_call(op_ptr, op_len, in_ptr, in_len)` returns a packed `u64`:
//!    `(out_ptr << 32) | out_len`. The input buffers are consumed (freed).
//! 3. Read the response, then `rvk_free(out_ptr, out_len)`.
//!
//! The module imports nothing from the host: no filesystem, network, clock,
//! or randomness. That is the least-authority property ADR-368 relies on.
#![allow(unsafe_code)]

/// ABI version exported for loader compatibility checks.
#[no_mangle]
pub extern "C" fn rvk_abi_version() -> u32 {
    crate::ABI_VERSION
}

fn layout(len: usize) -> std::alloc::Layout {
    // Zero-length requests still get a real 1-byte allocation so every
    // pointer handed out can be freed with the same layout.
    std::alloc::Layout::array::<u8>(len.max(1)).unwrap_or_else(|_| std::alloc::Layout::new::<u8>())
}

/// Allocate `len` bytes owned by the caller until passed back to `rvk_free`
/// or consumed by `rvk_call`. Returns null on allocation failure.
#[no_mangle]
pub extern "C" fn rvk_alloc(len: usize) -> *mut u8 {
    // SAFETY: the layout has non-zero size.
    unsafe { std::alloc::alloc(layout(len)) }
}

/// Free a buffer obtained from `rvk_alloc` or returned by `rvk_call`.
///
/// # Safety
/// `ptr`/`len` must describe a live allocation from this module.
#[no_mangle]
pub unsafe extern "C" fn rvk_free(ptr: *mut u8, len: usize) {
    if !ptr.is_null() {
        std::alloc::dealloc(ptr, layout(len));
    }
}

/// Copy a caller buffer out of linear memory and free it.
unsafe fn take(ptr: *mut u8, len: usize) -> Vec<u8> {
    if ptr.is_null() {
        return Vec::new();
    }
    let v = std::slice::from_raw_parts(ptr, len).to_vec();
    rvk_free(ptr, len);
    v
}

/// Run one operation. Consumes both input buffers.
///
/// # Safety
/// Each pointer must come from `rvk_alloc` with the given length, fully
/// initialised by the caller.
#[no_mangle]
pub unsafe extern "C" fn rvk_call(
    op_ptr: *mut u8,
    op_len: usize,
    in_ptr: *mut u8,
    in_len: usize,
) -> u64 {
    let op = take(op_ptr, op_len);
    let input = take(in_ptr, in_len);
    let response = match (std::str::from_utf8(&op), std::str::from_utf8(&input)) {
        (Ok(op), Ok(input)) => crate::call(op, input),
        _ => r#"{"ok":false,"error":{"code":"invalid_request","message":"operation and request must be UTF-8"}}"#.to_string(),
    };
    let bytes = response.as_bytes();
    let out = rvk_alloc(bytes.len());
    if out.is_null() {
        return 0;
    }
    std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len());
    ((out as u64) << 32) | bytes.len() as u64
}
