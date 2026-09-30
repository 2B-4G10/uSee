// SPDX-License-Identifier: MIT
// Shared JSON-ABI envelope handling for every ruview-kernel transport (ADR-368).

export const ABI_VERSION = 1;
export const MAX_INPUT_BYTES = 16 * 1024 * 1024;
export const OPERATIONS = Object.freeze([
  'info', 'validate_config', 'analyze', 'synthesize',
  'session_open', 'session_push', 'session_summary', 'session_close',
]);

/** Error raised for a `{ok:false}` kernel envelope or a transport failure. */
export class KernelError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = 'KernelError';
    this.code = code;
    Object.assign(this, details);
  }
}

/** Serialize a request, enforcing the ABI's operation allow-list and size bound. */
export function encodeRequest(op, request) {
  if (!OPERATIONS.includes(op)) throw new KernelError('unknown_operation', `unknown operation: ${String(op).slice(0, 64)}`);
  const json = typeof request === 'string' ? request : JSON.stringify(request ?? {});
  // UTF-8 needs at most 3 bytes per UTF-16 unit; only encode when it could matter.
  const bytes = json.length * 3 <= MAX_INPUT_BYTES ? json.length : new TextEncoder().encode(json).length;
  if (bytes > MAX_INPUT_BYTES) {
    throw new KernelError('limit_exceeded', `request exceeds ${MAX_INPUT_BYTES} bytes`);
  }
  return json;
}

/** Parse a kernel envelope; returns `result` or throws KernelError. */
export function decodeResponse(text) {
  let envelope;
  try {
    envelope = JSON.parse(text);
  } catch {
    throw new KernelError('internal', 'kernel returned malformed JSON');
  }
  if (envelope?.ok === true) return envelope.result;
  const error = envelope?.error || {};
  throw new KernelError(String(error.code || 'internal'), String(error.message || 'kernel error'));
}

/**
 * Wrap a raw transport `(op, json) => json` into the high-level kernel API.
 * Both backends go through here, so their behaviour differs only in transport.
 */
export function createKernel(transport, meta) {
  const call = (op, request) => decodeResponse(transport(op, encodeRequest(op, request)));
  const kernel = {
    ...meta,
    call,
    info: () => call('info', {}),
    validateConfig: (config = {}) => call('validate_config', config),
    analyze: (frames, config = {}, { includeReadings = false } = {}) =>
      call('analyze', { config, frames, include_readings: includeReadings }),
    synthesize: (options = {}) => call('synthesize', options),
    openSession(config = {}) {
      const { session } = call('session_open', { config });
      let open = true;
      const ensureOpen = () => { if (!open) throw new KernelError('unknown_session', 'session is closed'); };
      return {
        id: session,
        push(frames) { ensureOpen(); return call('session_push', { session, frames }).readings; },
        summary() { ensureOpen(); return call('session_summary', { session }).summary; },
        close() { if (!open) return false; open = false; return call('session_close', { session }).closed; },
      };
    },
  };
  return Object.freeze(kernel);
}
