// Runs a wasm32-wasi module built by `newkotlinc --target=wasm` under Node's WASI (preview1).
// Usage: node run.mjs <module.wasm> [args...]; the current directory is preopened (bench/data for the file case).
import { WASI } from 'node:wasi';
import { readFileSync } from 'node:fs';

const [file, ...args] = process.argv.slice(2);
const wasi = new WASI({ version: 'preview1', args: [file, ...args], env: process.env, preopens: { '.': '.' }, returnOnExit: true });
const { instance } = await WebAssembly.instantiate(readFileSync(file), wasi.getImportObject());
process.exitCode = wasi.start(instance);
