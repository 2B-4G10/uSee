// SPDX-License-Identifier: MIT
// Optional bridge to the `@ruvnet/ruview-kernel` compute package (ADR-368).
//
// @ruvnet/ruview stays free of runtime dependencies: the kernel package is
// resolved only when this tool runs, and its absence is an honest negative,
// never a fabricated pass. The importer is injectable for tests only; MCP
// arguments can never choose which module is loaded.

export const KERNEL_PACKAGE = '@ruvnet/ruview-kernel';
export const KERNEL_BACKENDS = Object.freeze(['wasm', 'napi', 'auto']);

const defaultImporter = (specifier) => import(specifier);

/** Run the kernel's SYNTHETIC end-to-end self-test on the requested backend. */
export async function kernelSelfTest(args = {}, { importer = defaultImporter } = {}) {
  const backend = args.backend || 'wasm';
  let mod;
  try {
    mod = await importer(KERNEL_PACKAGE);
  } catch (error) {
    return {
      ok: false,
      reason: 'kernel_not_installed',
      detail: String(error?.code || error?.message || error),
      remedy: `npm install ${KERNEL_PACKAGE}  (or, in a RuView checkout: cd harness/ruview-kernel && npm run build)`,
    };
  }
  if (typeof mod.loadKernel !== 'function' || typeof mod.selfTest !== 'function') {
    return { ok: false, reason: 'kernel_incompatible', detail: `${KERNEL_PACKAGE} does not export loadKernel/selfTest` };
  }
  let kernel;
  try {
    kernel = mod.loadKernel({ backend });
  } catch (error) {
    return { ok: false, reason: error?.code || 'kernel_unavailable', requestedBackend: backend, detail: String(error?.message || error) };
  }
  const result = mod.selfTest(kernel, { seconds: args.seconds ?? 60 });
  return {
    ...result,
    requestedBackend: kernel.requestedBackend,
    fallbackReason: kernel.fallbackReason,
    integrity: kernel.integrity,
    sha256: kernel.sha256,
    note: 'SYNTHETIC self-test of the signal pipeline; not evidence of real-world sensing accuracy.',
  };
}
