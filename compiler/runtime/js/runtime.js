"use strict";
// KotlinX JS runtime (newkotlinc --target=js): linear memory, 64-bit integer helpers and the libc subset that
// compiler/runtime/runtime.c and the GC call. Generated code follows; it ends with a call to _start.
// JsBackend wraps runtime, code and start call in one function, so these top-level `var`s are closure
// variables (no global object properties, no `let` hole checks).
// Conventions (see compiler/src/backend/js/JsGen.kt): the IR is wasm32's, so a pointer, size_t or long is one int32;
// an i64 is two int32 arguments (low, high); a function returning an i64 returns the low word and leaves the high
// word in H.
var buf, I8, U8, I16, U16, I32, F32, F64, S = 0, D = 0, BRK = 0, H = 0, H1 = 0, H2 = 0, H3 = 0, H4 = 0, H5 = 0, H6 = 0, H7 = 0;
// STACK exceeds runtime.c's 4 MB release room (REL_STACK), so the limit it derives from _fa() stays positive.
var STACK = 1 << 23, MAXMEM = 2147418112;
var _fs = typeof process !== "undefined" && process.getBuiltinModule ? process.getBuiltinModule("fs") : null;

function _views() {
  I8 = new Int8Array(buf); U8 = new Uint8Array(buf); I16 = new Int16Array(buf); U16 = new Uint16Array(buf);
  I32 = new Int32Array(buf); F32 = new Float32Array(buf); F64 = new Float64Array(buf);
}
function _grow(min) {
  let n = buf.byteLength;
  while (n < min) n = Math.min(n * 2, MAXMEM);
  if (n < min) return false;
  if (buf.transfer) buf = buf.transfer(n); else { const b = new ArrayBuffer(n); new Uint8Array(b).set(U8); buf = b; }
  _views();
  return true;
}
function $js_sbrk(n) {
  const p = BRK, e = BRK + (n >>> 0) + 15 & -16;
  if (e > MAXMEM || e < 0 || (e > buf.byteLength && !_grow(e))) return 0;
  BRK = e;
  return p;
}
function _U() { throw new Error("unreachable code reached"); }
// Frame address for runtime.c's release depth limit: 4 KB per probed frame, so its 4 MB room is 1024 nested releases.
function _fa() { return S - (D << 12) | 0; }

// ---- unaligned memory -----------------------------------------------------------------------------------
function _ld(p, n) { let v = 0; for (let k = n - 1; k >= 0; k--) v = v << 8 | U8[p + k]; return n === 2 ? v << 16 >> 16 : v | 0; }
function _st(p, v, n) { for (let k = 0; k < n; k++) { U8[p + k] = v; v >>= 8; } }
var _cv = new ArrayBuffer(8), _cd = new Float64Array(_cv), _ci = new Int32Array(_cv), _cf = new Float32Array(_cv), _cb = new Uint8Array(_cv);
function _ldf(p) { for (let k = 0; k < 4; k++) _cb[k] = U8[p + k]; return _cf[0]; }
function _ldd(p) { for (let k = 0; k < 8; k++) _cb[k] = U8[p + k]; return _cd[0]; }
function _stf(p, v) { _cf[0] = v; for (let k = 0; k < 4; k++) U8[p + k] = _cb[k]; }
function _std(p, v) { _cd[0] = v; for (let k = 0; k < 8; k++) U8[p + k] = _cb[k]; }

// ---- bit casts and float conversions ----------------------------------------------------------------------
function _i2d(a, ah) { _ci[0] = a; _ci[1] = ah; return _cd[0]; }
function _d2i(d) { _cd[0] = d; H = _ci[1]; return _ci[0]; }
function _i2f(a) { _ci[0] = a; return _cf[0]; }
function _f2i(f) { _cf[0] = f; return _ci[0]; }
// saturating, NaN -> 0 (Kotlin Double.toLong)
function _d2l(d) {
  if (d !== d) { H = 0; return 0; }
  if (d >= 9223372036854775807) { H = 2147483647; return -1; }
  if (d <= -9223372036854775808) { H = -2147483648; return 0; }
  d = Math.trunc(d); const h = Math.floor(d / 4294967296); H = h | 0; return d - h * 4294967296 | 0;
}
function _d2ul(d) {
  if (!(d > 0)) { H = 0; return 0; }
  if (d >= 18446744073709551615) { H = -1; return -1; }
  d = Math.trunc(d); const h = Math.floor(d / 4294967296); H = h | 0; return d - h * 4294967296 | 0;
}
function _d2i32(d) { return d !== d ? 0 : d >= 2147483647 ? 2147483647 : d <= -2147483648 ? -2147483648 : d | 0; }
function _d2u32(d) { return !(d > 0) ? 0 : d >= 4294967295 ? -1 : d >>> 0 | 0; }
function _round(x) { return x < 0 ? -Math.round(-x) : Math.round(x); }
function _rint(x) { const r = Math.round(x); return Math.abs(x % 1) === 0.5 ? 2 * Math.round(x / 2) : r; }
function _copysign(a, b) { return (b < 0 || Object.is(b, -0)) ? -Math.abs(a) : Math.abs(a); }

// ---- 64-bit integers as (low, high) int32 pairs ---------------------------------------------------------------
function _mulhu(a, b) {
  a >>>= 0; b >>>= 0;
  const a0 = a & 65535, a1 = a >>> 16, b0 = b & 65535, b1 = b >>> 16;
  const t = a1 * b0 + (a0 * b0 >>> 16);
  const w1 = a0 * b1 + (t & 65535);
  return a1 * b1 + (t >>> 16) + (w1 >>> 16) | 0;
}
function _mul(a, ah, b, bh) { H = Math.imul(a, bh) + Math.imul(ah, b) + _mulhu(a, b) | 0; return Math.imul(a, b); }
function _big(a, ah) { return BigInt(ah) << 32n | BigInt(a >>> 0); }
function _unbig(v) { H = Number(BigInt.asIntN(32, v >> 32n)); return Number(BigInt.asIntN(32, v)); }
function _sdiv(a, ah, b, bh) {
  if (ah === a >> 31 && bh === b >> 31) {
    if (a === -2147483648 && b === -1) { H = 0; return a; }
    const q = a / b | 0; H = q >> 31; return q;
  }
  if (ah >= -2097152 && ah < 2097152 && bh >= -2097152 && bh < 2097152) {
    const q = Math.trunc((ah * 4294967296 + (a >>> 0)) / (bh * 4294967296 + (b >>> 0)));
    H = Math.floor(q / 4294967296) | 0; return q | 0;
  }
  return _unbig(_big(a, ah) / _big(b, bh));
}
function _srem(a, ah, b, bh) {
  if (ah === a >> 31 && bh === b >> 31) { const r = b === -1 ? 0 : a % b | 0; H = r >> 31; return r; }
  if (ah >= -2097152 && ah < 2097152 && bh >= -2097152 && bh < 2097152) {
    const r = (ah * 4294967296 + (a >>> 0)) % (bh * 4294967296 + (b >>> 0));
    H = Math.floor(r / 4294967296) | 0; return r | 0;
  }
  return _unbig(_big(a, ah) % _big(b, bh));
}
function _udiv(a, ah, b, bh) {
  if ((ah | bh) === 0) { H = 0; return (a >>> 0) / (b >>> 0) >>> 0 | 0; }
  if ((ah >>> 0) < 2097152 && (bh >>> 0) < 2097152) {
    const q = Math.floor(((ah >>> 0) * 4294967296 + (a >>> 0)) / ((bh >>> 0) * 4294967296 + (b >>> 0)));
    H = Math.floor(q / 4294967296) | 0; return q | 0;
  }
  return _unbig(BigInt.asUintN(64, _big(a, ah)) / BigInt.asUintN(64, _big(b, bh)));
}
function _urem(a, ah, b, bh) {
  if ((ah | bh) === 0) { H = 0; return (a >>> 0) % (b >>> 0) | 0; }
  if ((ah >>> 0) < 2097152 && (bh >>> 0) < 2097152) {
    const r = ((ah >>> 0) * 4294967296 + (a >>> 0)) % ((bh >>> 0) * 4294967296 + (b >>> 0));
    H = Math.floor(r / 4294967296) | 0; return r | 0;
  }
  return _unbig(BigInt.asUintN(64, _big(a, ah)) % BigInt.asUintN(64, _big(b, bh)));
}
function _shl(a, ah, c) { c &= 63; if (c === 0) { H = ah; return a; } if (c < 32) { H = ah << c | a >>> 32 - c; return a << c; } H = a << c - 32; return 0; }
function _lshr(a, ah, c) { c &= 63; if (c === 0) { H = ah; return a; } if (c < 32) { H = ah >>> c | 0; return a >>> c | ah << 32 - c; } H = 0; return ah >>> c - 32 | 0; }
function _ashr(a, ah, c) { c &= 63; if (c === 0) { H = ah; return a; } if (c < 32) { H = ah >> c; return a >>> c | ah << 32 - c; } H = ah >> 31; return ah >> c - 32; }
function _slt(a, ah, b, bh) { return ah < bh || ah === bh && (a >>> 0) < (b >>> 0); }
function _ult(a, ah, b, bh) { return (ah >>> 0) < (bh >>> 0) || ah === bh && (a >>> 0) < (b >>> 0); }
function _smax(a, ah, b, bh) { if (_slt(a, ah, b, bh)) { H = bh; return b; } H = ah; return a; }
function _smin(a, ah, b, bh) { if (_slt(a, ah, b, bh)) { H = ah; return a; } H = bh; return b; }
function _umax(a, ah, b, bh) { if (_ult(a, ah, b, bh)) { H = bh; return b; } H = ah; return a; }
function _umin(a, ah, b, bh) { if (_ult(a, ah, b, bh)) { H = ah; return a; } H = bh; return b; }
function _scmp64(a, ah, b, bh) { return _slt(a, ah, b, bh) ? -1 : a === b && ah === bh ? 0 : 1; }
function _ucmp64(a, ah, b, bh) { return _ult(a, ah, b, bh) ? -1 : a === b && ah === bh ? 0 : 1; }
function _abs(a, ah) { if (ah >= 0) { H = ah; return a; } const r = -a | 0; H = ~ah + (a === 0) | 0; return r; }
function _ctlz(a, ah) { H = 0; return ah ? Math.clz32(ah) : 32 + Math.clz32(a); }
function _cttz32(x) { return x ? 31 - Math.clz32(x & -x) : 32; }
function _cttz(a, ah) { H = 0; return a ? _cttz32(a) : 32 + _cttz32(ah); }
function _popc(x) { x -= x >>> 1 & 0x55555555; x = (x & 0x33333333) + (x >>> 2 & 0x33333333); return Math.imul(x + (x >>> 4) & 0x0f0f0f0f, 0x01010101) >>> 24; }
function _ctpop(a, ah) { H = 0; return _popc(a) + _popc(ah); }
function _bswap32(x) { return (x & 255) << 24 | (x & 0xff00) << 8 | x >>> 8 & 0xff00 | x >>> 24; }
function _bswap64(a, ah) { H = _bswap32(a); return _bswap32(ah); }
function _fshl(a, b, c) { c &= 31; return c ? a << c | b >>> 32 - c : a; }
function _fshr(a, b, c) { c &= 31; return c ? b >>> c | a << 32 - c : b; }
function _saddo(a, ah, b, bh) { const r = a + b | 0, rh = ah + bh + ((r >>> 0) < (a >>> 0)) | 0; H = rh; H1 = ((ah ^ rh) & (bh ^ rh)) < 0; return r; }
function _uaddo(a, ah, b, bh) { const r = a + b | 0, rh = ah + bh + ((r >>> 0) < (a >>> 0)) | 0; H = rh; H1 = _ult(r, rh, a, ah); return r; }
function _ssubo(a, ah, b, bh) { const r = a - b | 0, rh = ah - bh - ((a >>> 0) < (b >>> 0)) | 0; H = rh; H1 = ((ah ^ bh) & (ah ^ rh)) < 0; return r; }
function _usubo(a, ah, b, bh) { const r = a - b | 0, rh = ah - bh - ((a >>> 0) < (b >>> 0)) | 0; H = rh; H1 = _ult(a, ah, b, bh); return r; }
function _smulo(a, ah, b, bh) { const p = _big(a, ah) * _big(b, bh); const r = _unbig(p); H1 = BigInt.asIntN(64, p) !== p; return r; }
function _umulo(a, ah, b, bh) { const p = BigInt.asUintN(64, _big(a, ah)) * BigInt.asUintN(64, _big(b, bh)); const r = _unbig(p); H1 = p >= 18446744073709551616n; return r; }

// ---- C strings ---------------------------------------------------------------------------------------------------
// bytes as Latin-1
function _cstr(p) { let e = p; while (U8[e]) e++; let s = ""; for (let k = p; k < e; k += 8192) s += String.fromCharCode.apply(null, U8.subarray(k, Math.min(e, k + 8192))); return s; }
// Strings handed to C (argv, getenv) live in memory from js_sbrk: the C allocator may be internalized away.
function _newcstr(s) { const b = new TextEncoder().encode(s); const p = $js_sbrk(b.length + 1); U8.set(b, p); U8[p + b.length] = 0; return p; }
// TypedArray indexOf, copyWithin and fill are C++ builtins, slow to enter: strlen loops (C strings are short),
// memcpy and memset loop up to 16 bytes.
function $strlen(p) { let e = p; while (U8[e]) e++; return e - p; }
function $strcmp(a, b) { for (;;) { const x = U8[a++], y = U8[b++]; if (x !== y) return x - y; if (!x) return 0; } }
function $strncmp(a, b, n) { for (; n > 0; n--) { const x = U8[a++], y = U8[b++]; if (x !== y) return x - y; if (!x) return 0; } return 0; }
function $memcmp(a, b, n) { for (let k = 0; k < n >>> 0; k++) { const x = U8[a + k], y = U8[b + k]; if (x !== y) return x - y; } return 0; }
// bcmp and memmove are functions, not aliases (var): JsBackend.usedRuntime keeps every var and what it names.
// (No `$` names in comments: usedRuntime also scans comments, in LL2JS_PRETTY output.)
function $bcmp(a, b, n) { return $memcmp(a, b, n); }
// memmove semantics (overlap allowed); n is a size_t
function $memcpy(d, s, n) { if (n >>> 0 > 16 || d > s && d < s + n) U8.copyWithin(d, s, s + n | 0); else for (let k = 0; k < n; k++) U8[d + k] = U8[s + k]; return d; }
function $memset(d, v, n) { if (n >>> 0 > 16) U8.fill(v, d, d + n | 0); else for (let k = 0; k < n; k++) U8[d + k] = v; return d; }
function $memmove(d, s, n) { return $memcpy(d, s, n); }
function $memchr(p, c, n) { const k = U8.subarray(p, p + (n >>> 0)).indexOf(c & 255); return k < 0 ? 0 : p + k; }
function $strchr(p, c) { c &= 255; for (;; p++) { const x = U8[p]; if (x === c) return p; if (!x) return 0; } }
function $atoi(p) { return parseInt(_cstr(p), 10) | 0; }
function _strtod(p, endp) {
  const s = _cstr(p), m = /^[ \t\n\r\f\v]*([+-]?(?:inf(?:inity)?|nan|(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?))/i.exec(s);
  if (endp) I32[endp >> 2] = m ? p + m[0].length : p;
  if (!m) return 0;
  const t = m[1].toLowerCase().replace(/^([+-]?)inf(inity)?$/, "$1Infinity");
  return t.endsWith("nan") ? NaN : Number(t);
}
function $strtod(p, e) { return _strtod(p, e); }
function $strtof(p, e) { return Math.fround(_strtod(p, e)); }
// The strings are cached in $getenv.m, so a program that does not call getenv carries no cache.
function $getenv(p) {
  const k = _cstr(p), m = $getenv.m || ($getenv.m = new Map()); if (m.has(k)) return m.get(k);
  const v = typeof process !== "undefined" ? process.env[k] : undefined, r = v === undefined ? 0 : _newcstr(v);
  m.set(k, r); return r;
}
var _t0 = typeof process !== "undefined" && process.hrtime ? null : performance.now();
function _nanos() {
  if (_t0 === null) { const t = process.hrtime.bigint(); H = Number(t >> 32n) | 0; return Number(t & 0xffffffffn) | 0; }
  const ns = Math.round((performance.now() - _t0) * 1e6); H = Math.floor(ns / 4294967296) | 0; return ns | 0;
}
// struct timespec { int64_t tv_sec; long tv_nsec; }
function $clock_gettime(c, p) { const lo = _nanos(), ns = (H >>> 0) * 4294967296 + (lo >>> 0); const s = Math.floor(ns / 1e9); I32[p >> 2] = s; I32[p + 4 >> 2] = 0; I32[p + 8 >> 2] = ns - s * 1e9; return 0; }
// runtime.c's rt_nanos (System.nanoTime) under RT_JS
function $rt_nanos() { return _nanos(); }

// ---- stdio -------------------------------------------------------------------------------------------------------
var _ob = new Uint8Array(65536), _on = 0;
var _files = new Map(), _fid = 4;
function _flush() { if (_on) { _write(1, _ob.subarray(0, _on)); _on = 0; } }
function _write(fd, bytes) {
  if (_fs) { let o = 0; while (o < bytes.length) o += _fs.writeSync(fd, bytes, o, bytes.length - o); }
  else (fd === 2 ? console.error : console.log)(new TextDecoder().decode(bytes).replace(/\n$/, ""));
}
// s: string of bytes (char codes 0..255)
function _put(f, s) {
  if (f === 1) {
    if (_on + s.length > _ob.length) { _flush(); if (s.length > _ob.length) _ob = new Uint8Array(s.length * 2); }
    for (let k = 0; k < s.length; k++) _ob[_on++] = s.charCodeAt(k);
  } else if (f === 2) { _flush(); const b = new Uint8Array(s.length); for (let k = 0; k < s.length; k++) b[k] = s.charCodeAt(k); _write(2, b); }
  else { const h = _files.get(f); if (!h) return -1; const b = new Uint8Array(s.length); for (let k = 0; k < s.length; k++) b[k] = s.charCodeAt(k); _fs.writeSync(h.fd, b); }
  return s.length;
}
function _putbytes(f, p, n) {
  if (f === 1 && n <= _ob.length) { if (_on + n > _ob.length) _flush(); _ob.set(U8.subarray(p, p + n), _on); _on += n; return n; }
  if (f === 1) _flush();
  const h = f > 2 ? _files.get(f) : null;
  _write(h ? h.fd : f, U8.slice(p, p + n));
  return n;
}
function $fputs(s, f) { return _putbytes(f, s, U8.indexOf(0, s) - s) >= 0 ? 0 : -1; }
function $puts(s) { $fputs(s, 1); _put(1, "\n"); return 0; }
function $fwrite(p, size, n, f) { _putbytes(f, p, (size >>> 0) * (n >>> 0)); return n; }
function $putchar(c) { _put(1, String.fromCharCode(c & 255)); return c; }
function $fputc(c, f) { _put(f, String.fromCharCode(c & 255)); return c; }
function $putc(c, f) { return $fputc(c, f); }
function $fflush(f) { _flush(); return 0; }
function $printf(fmt, ...a) { return _put(1, _format(fmt, a)); }
function $fprintf(f, fmt, ...a) { return _put(f, _format(fmt, a)); }
function $snprintf(p, n, fmt, ...a) {
  const s = _format(fmt, a), max = n >>> 0;
  if (max > 0) { const k = Math.min(s.length, max - 1); for (let i = 0; i < k; i++) U8[p + i] = s.charCodeAt(i); U8[p + k] = 0; }
  return s.length;
}
function $sprintf(p, fmt, ...a) { return $snprintf(p, 0x7fffffff, fmt, ...a); }
function $fopen(path, mode) {
  if (!_fs) return 0;
  try { const m = _cstr(mode).replace("b", ""); const fd = _fs.openSync(_cstr(path), m); const id = _fid++; _files.set(id, { fd, pos: 0 }); return id; } catch (e) { return 0; }
}
function $fclose(f) { const h = _files.get(f); if (h) { _fs.closeSync(h.fd); _files.delete(f); } return 0; }
function $fread(p, size, n, f) {
  const h = _files.get(f); if (!h) return 0;
  const want = (size >>> 0) * (n >>> 0); let got = 0;
  while (got < want) { const r = _fs.readSync(h.fd, U8, p + got, want - got, h.pos); if (r <= 0) break; got += r; h.pos += r; }
  return Math.floor(got / (size >>> 0)) | 0;
}
function $fseek(f, off, whence) { const h = _files.get(f); if (!h) return -1; h.pos = whence === 0 ? off : whence === 1 ? h.pos + off : _fs.fstatSync(h.fd).size + off; return 0; }
function $ftell(f) { const h = _files.get(f); return h ? h.pos | 0 : -1; }
function $fgetc(f) { const h = _files.get(f); if (!h) return -1; const b = new Uint8Array(1); if (_fs.readSync(h.fd, b, 0, 1, h.pos) !== 1) return -1; h.pos++; return b[0]; }

// printf-style formatting; integer conversions with ll/j/q take an i64 (two arguments), l/z/t one int32 (wasm32).
function _format(fp, a) {
  const f = _cstr(fp); let out = "", ai = 0;
  for (let i = 0; i < f.length; i++) {
    const ch = f[i];
    if (ch !== "%") { out += ch; continue; }
    let flags = "", width = -1, prec = -1, len = "";
    while ("-+ #0".includes(f[i + 1])) flags += f[++i];
    if (f[i + 1] === "*") { i++; width = a[ai++]; if (width < 0) { flags += "-"; width = -width; } } else { let w = ""; while (/\d/.test(f[i + 1])) w += f[++i]; if (w) width = +w; }
    if (f[i + 1] === ".") { i++; if (f[i + 1] === "*") { i++; prec = a[ai++]; } else { let p = ""; while (/\d/.test(f[i + 1])) p += f[++i]; prec = p ? +p : 0; } }
    while ("hlLqjzt".includes(f[i + 1])) len += f[++i];
    const c = f[++i];
    const wide = len === "ll" || len === "j" || len === "q";
    let s;
    switch (c) {
      case "%": out += "%"; continue;
      case "d": case "i": case "u": case "x": case "X": case "o": {
        let v;
        if (wide) { const lo = a[ai++], hi = a[ai++]; v = c === "d" || c === "i" ? _big(lo, hi) : BigInt.asUintN(64, _big(lo, hi)); }
        else { let x = a[ai++] | 0; if (len === "hh") x = c === "d" || c === "i" ? x << 24 >> 24 : x & 255; else if (len === "h") x = c === "d" || c === "i" ? x << 16 >> 16 : x & 65535; v = BigInt(c === "d" || c === "i" ? x : x >>> 0); }
        const neg = v < 0n; if (neg) v = -v;
        s = v.toString(c === "x" || c === "X" ? 16 : c === "o" ? 8 : 10);
        if (c === "X") s = s.toUpperCase();
        if (prec >= 0) { s = s.padStart(prec, "0"); if (prec === 0 && v === 0n) s = ""; }
        if (flags.includes("#") && v !== 0n) s = (c === "x" ? "0x" : c === "X" ? "0X" : c === "o" ? "0" : "") + s;
        const sign = neg ? "-" : flags.includes("+") && (c === "d" || c === "i") ? "+" : flags.includes(" ") && (c === "d" || c === "i") ? " " : "";
        if (flags.includes("0") && !flags.includes("-") && prec < 0 && width > 0) s = s.padStart(width - sign.length, "0");
        s = sign + s; break;
      }
      case "c": s = String.fromCharCode(a[ai++] & 255); break;
      case "s": { const p = a[ai++]; s = p ? _cstr(p) : "(null)"; if (prec >= 0) s = s.substring(0, prec); break; }
      case "p": s = "0x" + (a[ai++] >>> 0).toString(16); break;
      case "f": case "F": case "e": case "E": case "g": case "G": case "a": case "A": {
        const x = a[ai++]; s = _ffmt(x, c, prec < 0 ? 6 : prec, flags);
        if (flags.includes("0") && !flags.includes("-") && width > 0 && isFinite(x)) { const neg = s[0] === "-" || s[0] === "+" || s[0] === " "; s = neg ? s[0] + s.slice(1).padStart(width - 1, "0") : s.padStart(width, "0"); }
        break;
      }
      default: s = "%" + c;
    }
    if (width > s.length) s = flags.includes("-") ? s.padEnd(width) : s.padStart(width);
    out += s;
  }
  return out;
}
function _ffmt(x, c, p, flags) {
  const up = c === "F" || c === "E" || c === "G" || c === "A";
  let sign = x < 0 || Object.is(x, -0) ? "-" : flags.includes("+") ? "+" : flags.includes(" ") ? " " : "";
  x = Math.abs(x);
  let s;
  if (!isFinite(x)) s = x !== x ? "nan" : "inf";
  else {
    const lc = c.toLowerCase();
    if (lc === "f") s = _fixed(x, p);
    else if (lc === "e") s = _exp(x, p);
    else if (lc === "g") {
      const P = p === 0 ? 1 : p;
      const X = x === 0 ? 0 : +_exp(x, P - 1).split("e")[1];
      s = P > X && X >= -4 ? _fixed(x, P - 1 - X) : _exp(x, P - 1);
      if (!flags.includes("#") && s.includes(".")) { const [m, e] = s.split("e"); s = m.replace(/\.?0+$/, "") + (e !== undefined ? "e" + e : ""); }
    } else s = x.toString(16);
  }
  if (up) s = s.toUpperCase();
  return sign + s;
}
function _exp(x, p) { const s = x.toExponential(Math.min(p, 100)); const [m, e] = s.split("e"); const n = +e; return m + "e" + (n < 0 ? "-" : "+") + String(Math.abs(n)).padStart(2, "0"); }
function _fixed(x, p) { if (x < 1e21) return x.toFixed(Math.min(p, 100)); const i = BigInt(x).toString(); return p > 0 ? i + "." + "0".repeat(p) : i; }

// ---- runtime.c under RT_JS: number text and uncaught-exception messages without printf ----------------------------
// decimal text of the i64 (a, ah); u: unsigned
function _i64s(a, ah, u) {
  if (u ? (ah >>> 0) < 2097152 : ah >= -2097152 && ah < 2097152) return "" + (ah * 4294967296 + (a >>> 0));
  return "" + (u ? BigInt.asUintN(64, _big(a, ah)) : _big(a, ah));
}
// Kotlin's Double/Float toString as runtime.c's fmt_fp writes it: the fewest digits (correctly rounded, ties to even)
// that read back as x (f: the float reader for a float, else 0); plain for 1e-3 <= |x| < 1e7, else D.DDDE<exp>.
// toExponential() gives those digits for a double, except a power of two (its lower neighbour is nearer than its
// upper one); a power of two and a float take fmt_fp's loop over the precision p. toExponential(p) rounds ties up,
// so an exact tie (the exact digits t are p + 2 long and end in 5) is rounded to even here.
function _kfmt(x, f) {
  if (x !== x) return "NaN";
  if (x === 0) return 1 / x < 0 ? "-0.0" : "0.0";
  if (x === 1 / 0 || x === -1 / 0) return x < 0 ? "-Infinity" : "Infinity";
  let s = x.toExponential();
  _cd[0] = x;
  if (f || !_ci[0] && !(_ci[1] & 1048575)) {
    const T = x.toExponential(100), t = T.replace(/^-|\.|0*e.*/g, "");
    for (let p = 0; ; p++) {
      s = t.length === p + 2 && t[p + 1] === "5" && t[p] % 2 === 0 ? (x < 0 ? "-" : "") + t[0] + "." + t.slice(1, p + 1) + "e" + T.split("e")[1] : x.toExponential(p);
      if (p === 16 || (f ? f(s) : +s) === x) break; // fmt_fp stops at 17 digits too (x may not be a float)
    }
  }
  const e = s.indexOf("e"), n = +s.slice(e + 1), d = s.slice(0, e).replace(/^-|\./g, "").replace(/(.)0+$/, "$1"), g = x < 0 ? "-" : "";
  if (Math.abs(x) < 1e-3 || Math.abs(x) >= 1e7) return g + d[0] + "." + (d.slice(1) || "0") + "E" + n;
  return g + (n < 0 ? "0." + "0".repeat(-n - 1) + d : d.slice(0, n + 1).padEnd(n + 1, "0") + "." + (d.slice(n + 1) || "0"));
}
// The float nearest the decimal s = [-]D[.DDD]e<exp> (C's strtof, ties to even). Math.fround(+s) rounds twice, which
// goes wrong only when +s is exactly the midpoint between two floats r and o: then s is compared with it exactly.
function _strtof(s) {
  const v = +s, r = Math.fround(v), o = 2 * v - r;
  if (r === v || !isFinite(r) || Math.fround(o) !== o) return r;
  // |s| = d * 10^k and |v| = m * 2^z (a midpoint is a normal double)
  _cd[0] = Math.abs(v);
  const [, a, b, e] = /^-?(\d)\.?(\d*)e([-+]\d+)$/.exec(s), k = +e - b.length, z = (_ci[1] >>> 20) - 1075;
  let d = BigInt(a + b), m = BigInt(_ci[1] & 1048575 | 1048576) << 32n | BigInt(_ci[0] >>> 0);
  if (k > 0) d *= 10n ** BigInt(k); else m *= 10n ** BigInt(-k);
  if (z > 0) m <<= BigInt(z); else d <<= BigInt(-z);
  return d === m || (d > m) === (Math.abs(r) > Math.abs(o)) ? r : o;
}
// s (char codes 0..255) and a NUL at p; returns s.length
function _wstr(p, s) { for (let k = 0; k < s.length; k++) U8[p + k] = s.charCodeAt(k); U8[p + s.length] = 0; return s.length; }
function $js_i64_str(p, a, ah, u) { return _wstr(p, _i64s(a, ah, u)); }
function $js_f64_str(p, x) { return _wstr(p, _kfmt(x, 0)); }
function $js_f32_str(p, x) { return _wstr(p, _kfmt(x, _strtof)); }
function $rt_print_i64(a, ah) { _put(1, _i64s(a, ah, 0)); }
function $rt_print_u64(a, ah) { _put(1, _i64s(a, ah, 1)); }
function $rt_print_f64(x) { _put(1, _kfmt(x, 0)); }
function $rt_print_f32(x) { _put(1, _kfmt(x, _strtof)); }
// an uncaught exception: stdout flushed, the message on stderr, exit status 1
function _die(s) { _put(2, "Exception in thread \"main\" " + s + "\n"); $exit(1); }
function $rt_throw(c, m) { _die(_cstr(c) + (m ? ": " + _cstr(m) : "")); }
function $rt_panic(m) { _die("java.lang.IllegalStateException: " + _cstr(m)); }
function $rt_bad_step(a, ah) { _die("java.lang.IllegalArgumentException: Step must be positive, was: " + _i64s(a, ah, 0) + "."); }
function $rt_oob(a, ah, n, nh) { _die("kotlin.ArrayIndexOutOfBoundsException: Index " + _i64s(a, ah, 0) + " out of bounds for length " + _i64s(n, nh, 0)); }
function $js_list_oob(a, ah, n, nh) { _die("java.lang.IndexOutOfBoundsException: Index " + _i64s(a, ah, 0) + " out of bounds for length " + _i64s(n, nh, 0)); }
function $js_sb_len_oob(a, ah) { _die("java.lang.IndexOutOfBoundsException: length " + _i64s(a, ah, 0)); }
function $js_map_no_key(s, a, ah) { _die("java.lang.NullPointerException: key " + (s ? _cstr(a) : _i64s(a, ah, 0)) + " not in map"); }

// ---- process -------------------------------------------------------------------------------------------------------
class _Exit { constructor(c) { this.code = c; } }
function $exit(c) { _flush(); throw new _Exit(c); }
function $_exit(c) { $exit(c); }
function $abort() { _flush(); throw new Error("abort()"); }
function $atexit(f) { return 0; }

function _b64(s) { const b = atob(s), u = new Uint8Array(b.length); for (let i = 0; i < b.length; i++) u[i] = b.charCodeAt(i); return u; }
function _start(data, dataStart, dataEnd, ctors, main) {
  const top = dataEnd + STACK + 15 & -16;
  // 32 MB to start (pages are committed as they are touched): every _grow replaces the views, which throws away
  // V8's optimized code that embeds them, so the heap behind the 8 MB stack starts with room to spare.
  buf = new ArrayBuffer(Math.max(1 << 25, top + (1 << 22) + 65535 & -65536)); _views();
  U8.set(_b64(data), dataStart);
  S = top; BRK = top;
  let code = 0;
  try {
    for (const c of ctors) c();
    const args = typeof process !== "undefined" ? process.argv.slice(2) : [];
    const argv = $js_sbrk(4 * (args.length + 2)); U8.fill(0, argv, argv + 4 * (args.length + 2));
    I32[argv >> 2] = _newcstr("main");
    args.forEach((s, k) => { I32[(argv >> 2) + k + 1] = _newcstr(s); });
    code = main(args.length + 1, argv);
    _flush();
  } catch (e) {
    if (!(e instanceof _Exit)) { _flush(); throw e; }
    code = e.code;
  }
  if (typeof process !== "undefined") process.exitCode = code;
}
