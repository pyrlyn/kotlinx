// Java runtime of the KotlinX jvm target (newkotlinc --target jvm).
//
// The translated program (one class, see compiler/src/backend/jvm/JvmBackend.kt) keeps the C memory model: pointers
// are native addresses (long) accessed through jdk.internal.misc.Unsafe. This class provides what the C runtime
// takes from libc: an allocator, stdio (printf family, FILE streams), strings, time, strtod, libm, plus the helpers
// the translator calls for LLVM intrinsics. Every `public static` method named like a C function is callable from the
// program; the compiler reads that list from this file. Single-threaded: the program runs on one thread.
//
// Build: javac --add-exports java.base/jdk.internal.misc=ALL-UNNAMED KxRt.java (done by newkotlinc; the jar
// manifest carries Add-Exports, so `java -jar` needs no flags).

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import jdk.internal.misc.Unsafe;

public final class KxRt {
    public static final Unsafe U = Unsafe.getUnsafe();

    // ------------------------------------------------------------------ program start, native stack, globals

    /** Native stack for allocas (grows down). */
    public static long SP;
    private static final long STACK = 64L << 20;
    private static int argc;
    private static long argv;

    static {
        long base = U.allocateMemory(STACK);
        SP = (base + STACK) & ~15L;
    }

    public static void start(Runnable program, String[] args) throws InterruptedException {
        argc = args.length + 1;
        argv = U.allocateMemory(8L * (argc + 1));
        U.putLong(argv, cstr("main"));
        for (int i = 0; i < args.length; i++) U.putLong(argv + 8L * (i + 1), cstr(args[i]));
        U.putLong(argv + 8L * argc, 0);
        // Kotlin code recurses on the JVM stack: give it as much room as a native main thread has (or more).
        Thread t = new Thread(null, () -> {
            try { program.run(); }
            catch (Throwable e) { flushAll(); System.err.print("Exception in thread \"main\" "); e.printStackTrace(); Runtime.getRuntime().halt(1); }
        }, "main", 1L << 30);
        t.start();
        t.join();
    }

    public static int argc() { return argc; }
    public static long argv() { return argv; }

    public static long globals(long size) { long g = U.allocateMemory(size); U.setMemory(g, size, (byte) 0); return g; }
    /** Copies initial bytes (one char per byte) to [at]. */
    public static void data(long at, String s) { for (int i = 0, n = s.length(); i < n; i++) U.putByte(at + i, (byte) s.charAt(i)); }
    /** Adds the globals base to the 8-byte words at the given offsets (two chars per 32-bit offset). */
    public static void reloc(long base, String offs) {
        for (int i = 0; i < offs.length(); i += 2) {
            long at = base + (((long) offs.charAt(i) << 16) | offs.charAt(i + 1));
            U.putLong(at, U.getLong(at) + base);
        }
    }

    public static RuntimeException unreachable() { flushAll(); return new IllegalStateException("unreachable code reached"); }
    public static RuntimeException trap() { flushAll(); return new IllegalStateException("trap"); }
    public static RuntimeException badCall(long fn) { flushAll(); return new IllegalStateException("bad indirect call to function id " + fn); }

    // ------------------------------------------------------------------ allocator
    // Segregated free lists (16-byte classes up to 1 KiB) carved from 1 MiB chunks; larger blocks come from the
    // system allocator. An 8-byte header before each block holds its class (or -1 for a system block); user pointers
    // are 16-byte aligned like malloc's.

    private static final int SMALL = 1024;
    private static final long[] free = new long[SMALL / 16 + 1];
    private static long bump, bumpEnd;

    public static long malloc(long n) {
        if (n <= SMALL - 8) {
            int cls = (int) ((n + 8 + 15) >>> 4);
            long p = free[cls];
            if (p != 0) { free[cls] = U.getLong(p); return p; }
            long need = (long) cls << 4;
            if (bump + need > bumpEnd) refill();
            long blk = bump; bump += need;
            U.putLong(blk, cls);
            return blk + 8;
        }
        long raw = U.allocateMemory(n + 16);
        U.putLong(raw + 8, -1L);
        return raw + 16;
    }

    private static void refill() {
        long chunk = U.allocateMemory(1L << 20);
        bump = ((chunk + 15) & ~15L) + 8; // header at 8 mod 16, user pointer 16-aligned
        bumpEnd = chunk + (1L << 20);
    }

    public static void free(long p) {
        if (p == 0) return;
        long cls = U.getLong(p - 8);
        if (cls < 0) { U.freeMemory(p - 16); return; }
        U.putLong(p, free[(int) cls]);
        free[(int) cls] = p;
    }

    private static long usable(long p) { long cls = U.getLong(p - 8); return cls < 0 ? Long.MAX_VALUE : (cls << 4) - 8; }

    public static long calloc(long n, long size) { long b = n * size; long p = malloc(b); U.setMemory(p, b, (byte) 0); return p; }

    public static long realloc(long p, long n) {
        if (p == 0) return malloc(n);
        long cls = U.getLong(p - 8);
        if (cls < 0) {
            if (n <= SMALL - 8) { long q = malloc(n); U.copyMemory(p, q, n); free(p); return q; }
            long raw = U.reallocateMemory(p - 16, n + 16);
            return raw + 16;
        }
        long cap = usable(p);
        if (n <= cap) return p;
        long q = malloc(n);
        U.copyMemory(p, q, cap);
        free(p);
        return q;
    }

    public static long aligned_alloc(long align, long n) {
        if (align <= 16) return malloc(n);
        long raw = U.allocateMemory(n + align + 16);
        long user = (raw + 16 + align - 1) & -align;
        U.putLong(user - 8, -1L);
        // free(user) releases raw: keep raw at user - 16 (user - 16 >= raw since user >= raw + 16)
        U.putLong(user - 16, raw);
        return user;
    }

    public static int posix_memalign(long out, long align, long n) { U.putLong(out, aligned_alloc(align, n)); return 0; }

    // ------------------------------------------------------------------ memory and strings

    public static long memcpy(long d, long s, long n) { U.copyMemory(s, d, n); return d; }
    public static long memmove(long d, long s, long n) { U.copyMemory(s, d, n); return d; }
    public static long memset(long d, int c, long n) { U.setMemory(d, n, (byte) c); return d; }
    public static int memcmp(long a, long b, long n) {
        for (long i = 0; i < n; i++) { int x = U.getByte(a + i) & 0xFF, y = U.getByte(b + i) & 0xFF; if (x != y) return x - y; }
        return 0;
    }
    public static long memchr(long s, int c, long n) { for (long i = 0; i < n; i++) if ((U.getByte(s + i) & 0xFF) == (c & 0xFF)) return s + i; return 0; }
    public static long strlen(long s) { long i = 0; while (U.getByte(s + i) != 0) i++; return i; }
    public static int strcmp(long a, long b) {
        for (long i = 0; ; i++) { int x = U.getByte(a + i) & 0xFF, y = U.getByte(b + i) & 0xFF; if (x != y) return x - y; if (x == 0) return 0; }
    }
    public static int strncmp(long a, long b, long n) {
        for (long i = 0; i < n; i++) { int x = U.getByte(a + i) & 0xFF, y = U.getByte(b + i) & 0xFF; if (x != y) return x - y; if (x == 0) return 0; }
        return 0;
    }
    public static long strchr(long s, int c) { for (long i = 0; ; i++) { int x = U.getByte(s + i) & 0xFF; if (x == (c & 0xFF)) return s + i; if (x == 0) return 0; } }
    public static long strrchr(long s, int c) { long r = 0; for (long i = 0; ; i++) { int x = U.getByte(s + i) & 0xFF; if (x == (c & 0xFF)) r = s + i; if (x == 0) return r; } }
    public static long strcpy(long d, long s) { long n = strlen(s); U.copyMemory(s, d, n + 1); return d; }
    public static long strncpy(long d, long s, long n) {
        long i = 0; for (; i < n; i++) { byte b = U.getByte(s + i); U.putByte(d + i, b); if (b == 0) break; }
        for (; i < n; i++) U.putByte(d + i, (byte) 0);
        return d;
    }
    public static long strcat(long d, long s) { strcpy(d + strlen(d), s); return d; }
    public static long strdup(long s) { long n = strlen(s); long p = malloc(n + 1); U.copyMemory(s, p, n + 1); return p; }
    public static long strstr(long h, long n) {
        long m = strlen(n); if (m == 0) return h;
        for (long i = 0; U.getByte(h + i) != 0; i++) if (memcmp(h + i, n, m) == 0) return h + i;
        return 0;
    }

    static String jstr(long p) { int n = (int) strlen(p); byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = U.getByte(p + i); return new String(b, StandardCharsets.UTF_8); }
    static long cstr(String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); long p = malloc(b.length + 1); for (int i = 0; i < b.length; i++) U.putByte(p + i, b[i]); U.putByte(p + b.length, (byte) 0); return p; }

    // ------------------------------------------------------------------ stdio

    static final long STDIN = 1, STDOUT = 2, STDERR = 3;
    private static final byte[] obuf = new byte[1 << 16];
    private static int on;
    private static final FileOutputStream OUT = new FileOutputStream(FileDescriptor.out);
    private static final FileOutputStream ERR = new FileOutputStream(FileDescriptor.err);
    private static final HashMap<Long, RandomAccessFile> files = new HashMap<>();
    private static final HashMap<Long, Boolean> eof = new HashMap<>();
    private static long nextFile = 4;

    private static void flushOut() { if (on > 0) { try { OUT.write(obuf, 0, on); } catch (IOException e) { } on = 0; } }
    static void flushAll() { flushOut(); }

    private static void outByte(int b) { if (on == obuf.length) flushOut(); obuf[on++] = (byte) b; }
    private static void out(long stream, byte[] b, int n) {
        if (stream == STDOUT) { if (n > obuf.length - on) { flushOut(); if (n > obuf.length) { try { OUT.write(b, 0, n); } catch (IOException e) { } return; } } System.arraycopy(b, 0, obuf, on, n); on += n; }
        else if (stream == STDERR) { try { ERR.write(b, 0, n); } catch (IOException e) { } }
        else { RandomAccessFile f = files.get(stream); if (f != null) try { f.write(b, 0, n); } catch (IOException e) { } }
    }

    public static int putchar(int c) { outByte(c); return c & 0xFF; }
    public static int puts(long s) { long n = strlen(s); for (long i = 0; i < n; i++) outByte(U.getByte(s + i)); outByte('\n'); return 1; }
    public static int fputs(long s, long stream) { int n = (int) strlen(s); out(stream, bytes(s, n), n); return 1; }
    public static int fputc(int c, long stream) { if (stream == STDOUT) outByte(c); else out(stream, new byte[] { (byte) c }, 1); return c & 0xFF; }
    public static int putc(int c, long stream) { return fputc(c, stream); }
    public static long fwrite(long p, long size, long n, long stream) { int len = (int) (size * n); out(stream, bytes(p, len), len); return n; }
    public static long write(int fd, long p, long n) { out(fd == 1 ? STDOUT : fd == 2 ? STDERR : -1, bytes(p, (int) n), (int) n); return n; }
    public static int fflush(long stream) { if (stream == STDOUT || stream == 0) flushOut(); return 0; }
    public static int isatty(int fd) { return System.console() != null && fd <= 2 ? 1 : 0; }

    private static byte[] bytes(long p, int n) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = U.getByte(p + i); return b; }

    public static int printf(long fmt, long[] va) { Buf b = format(fmt, va); out(STDOUT, b.a, b.n); return b.n; }
    public static int fprintf(long stream, long fmt, long[] va) { Buf b = format(fmt, va); out(stream, b.a, b.n); return b.n; }
    public static int sprintf(long dst, long fmt, long[] va) { Buf b = format(fmt, va); U.copyMemory(b.a, Unsafe.ARRAY_BYTE_BASE_OFFSET, null, dst, b.n); U.putByte(dst + b.n, (byte) 0); return b.n; }
    public static int snprintf(long dst, long cap, long fmt, long[] va) {
        Buf b = format(fmt, va);
        if (cap > 0) { int k = (int) Math.min(b.n, cap - 1); for (int i = 0; i < k; i++) U.putByte(dst + i, b.a[i]); U.putByte(dst + k, (byte) 0); }
        return b.n;
    }

    public static long fopen(long path, long mode) {
        String m = jstr(mode), p = jstr(path);
        try {
            RandomAccessFile f;
            if (m.startsWith("r")) f = new RandomAccessFile(p, m.contains("+") ? "rw" : "r");
            else { f = new RandomAccessFile(p, "rw"); if (m.startsWith("w")) f.setLength(0); else f.seek(f.length()); }
            long h = nextFile++; files.put(h, f); return h;
        } catch (IOException e) { return 0; }
    }
    public static int fclose(long h) { RandomAccessFile f = files.remove(h); eof.remove(h); try { if (f != null) f.close(); } catch (IOException e) { } return 0; }
    public static long fread(long p, long size, long n, long h) {
        RandomAccessFile f = files.get(h); if (f == null || size == 0) return 0;
        byte[] b = new byte[(int) Math.min(size * n, 1 << 20)];
        long total = 0, want = size * n;
        try {
            while (total < want) {
                int r = f.read(b, 0, (int) Math.min(b.length, want - total));
                if (r <= 0) { eof.put(h, true); break; }
                U.copyMemory(b, Unsafe.ARRAY_BYTE_BASE_OFFSET, null, p + total, r);
                total += r;
            }
        } catch (IOException e) { }
        return total / size;
    }
    public static int fseek(long h, long off, int whence) {
        RandomAccessFile f = files.get(h); if (f == null) return -1;
        try { f.seek(whence == 0 ? off : whence == 1 ? f.getFilePointer() + off : f.length() + off); eof.remove(h); return 0; } catch (IOException e) { return -1; }
    }
    public static long ftell(long h) { RandomAccessFile f = files.get(h); try { return f == null ? -1 : f.getFilePointer(); } catch (IOException e) { return -1; } }
    public static void rewind(long h) { fseek(h, 0, 0); }
    public static int fgetc(long h) {
        if (h == STDIN) { try { return System.in.read(); } catch (IOException e) { return -1; } }
        RandomAccessFile f = files.get(h); if (f == null) return -1;
        try { int c = f.read(); if (c < 0) eof.put(h, true); return c; } catch (IOException e) { return -1; }
    }
    public static int getc(long h) { return fgetc(h); }
    public static int getchar() { flushOut(); return fgetc(STDIN); }
    public static long fgets(long p, int n, long h) {
        int i = 0;
        while (i < n - 1) { int c = fgetc(h); if (c < 0) break; U.putByte(p + i++, (byte) c); if (c == '\n') break; }
        if (i == 0) return 0;
        U.putByte(p + i, (byte) 0); return p;
    }
    public static int feof(long h) { return eof.containsKey(h) ? 1 : 0; }
    public static int ferror(long h) { return 0; }

    // ------------------------------------------------------------------ process, environment, time

    public static void exit(int code) { flushAll(); Runtime.getRuntime().halt(code); }
    public static void _exit(int code) { exit(code); }
    public static void abort() { flushAll(); Runtime.getRuntime().halt(134); }

    private static final HashMap<String, Long> env = new HashMap<>();
    public static long getenv(long name) {
        String v = System.getenv(jstr(name));
        if (v == null) return 0;
        return env.computeIfAbsent(v, KxRt::cstr);
    }

    private static final long T0 = System.nanoTime();
    public static long clock_gettime_nsec_np(int clk) { return System.nanoTime() - T0 + 1_000_000_000L; }
    public static long mach_absolute_time() { return System.nanoTime(); }
    public static int clock_gettime(int clk, long ts) {
        long ns = clk == 0 ? System.currentTimeMillis() * 1_000_000L : System.nanoTime() - T0 + 1_000_000_000L;
        U.putLong(ts, ns / 1_000_000_000L); U.putLong(ts + 8, ns % 1_000_000_000L); return 0;
    }
    public static long clock() { return java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1000; }
    public static long time(long out) { long t = System.currentTimeMillis() / 1000; if (out != 0) U.putLong(out, t); return t; }
    public static int gettimeofday(long tv, long tz) { long ms = System.currentTimeMillis(); U.putLong(tv, ms / 1000); U.putInt(tv + 8, (int) (ms % 1000) * 1000); return 0; }
    public static int usleep(int us) { try { Thread.sleep(us / 1000, (us % 1000) * 1000); } catch (InterruptedException e) { } return 0; }
    public static int sleep(int s) { try { Thread.sleep(s * 1000L); } catch (InterruptedException e) { } return 0; }
    public static int getrlimit(int res, long r) { U.putLong(r, 8L << 20); U.putLong(r + 8, Long.MAX_VALUE); return 0; }

    private static long errno;
    public static long __error() { if (errno == 0) errno = calloc(1, 8); return errno; }

    // ------------------------------------------------------------------ number parsing

    /** Length of the longest prefix of [s] (from [i]) that strtod accepts, or 0. */
    private static int floatPrefix(String s, int i) {
        int st = i, n = s.length();
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        String rest = s.substring(i).toLowerCase();
        if (rest.startsWith("infinity")) return i + 8 - st;
        if (rest.startsWith("inf") || rest.startsWith("nan")) return i + 3 - st;
        int d0 = i;
        while (i < n && Character.isDigit(s.charAt(i))) i++;
        int digits = i - d0;
        if (i < n && s.charAt(i) == '.') { i++; int f0 = i; while (i < n && Character.isDigit(s.charAt(i))) i++; digits += i - f0; }
        if (digits == 0) return 0;
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int e = i + 1; if (e < n && (s.charAt(e) == '+' || s.charAt(e) == '-')) e++;
            int e0 = e; while (e < n && Character.isDigit(s.charAt(e))) e++;
            if (e > e0) i = e;
        }
        return i - st;
    }

    private static double parseD(String t) {
        String l = t.toLowerCase(); boolean neg = l.startsWith("-");
        String u = l.startsWith("+") || neg ? l.substring(1) : l;
        if (u.startsWith("inf")) return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        if (u.startsWith("nan")) return Double.NaN;
        return Double.parseDouble(t);
    }

    public static double strtod(long p, long end) {
        String s = jstr(p); int i = 0; while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        int k = floatPrefix(s, i);
        if (end != 0) U.putLong(end, k == 0 ? p : p + s.substring(0, i + k).getBytes(StandardCharsets.UTF_8).length);
        return k == 0 ? 0.0 : parseD(s.substring(i, i + k));
    }
    public static float strtof(long p, long end) {
        String s = jstr(p); int i = 0; while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        int k = floatPrefix(s, i);
        if (end != 0) U.putLong(end, k == 0 ? p : p + s.substring(0, i + k).getBytes(StandardCharsets.UTF_8).length);
        if (k == 0) return 0f;
        String t = s.substring(i, i + k); double d = parseD(t);
        return Double.isNaN(d) || Double.isInfinite(d) ? (float) d : Float.parseFloat(t);
    }
    public static double atof(long p) { return strtod(p, 0); }

    public static long strtoll(long p, long end, int base) {
        long i = p; while (U.getByte(i) == ' ' || U.getByte(i) == '\t' || U.getByte(i) == '\n') i++;
        boolean neg = false;
        if (U.getByte(i) == '-' || U.getByte(i) == '+') { neg = U.getByte(i) == '-'; i++; }
        if ((base == 0 || base == 16) && U.getByte(i) == '0' && (U.getByte(i + 1) | 32) == 'x') { i += 2; base = 16; }
        else if (base == 0 && U.getByte(i) == '0') base = 8;
        else if (base == 0) base = 10;
        long v = 0; long st = i; boolean ovf = false;
        for (;; i++) {
            int c = U.getByte(i), d = c >= '0' && c <= '9' ? c - '0' : (c | 32) >= 'a' && (c | 32) <= 'z' ? (c | 32) - 'a' + 10 : 99;
            if (d >= base) break;
            long nv = v * base + d; if (nv < v || (v != 0 && nv / base != v)) ovf = true; v = nv;
        }
        if (end != 0) U.putLong(end, i == st ? p : i);
        if (ovf) return neg ? Long.MIN_VALUE : Long.MAX_VALUE;
        return neg ? -v : v;
    }
    public static long strtol(long p, long end, int base) { return strtoll(p, end, base); }
    public static long strtoul(long p, long end, int base) { return strtoll(p, end, base); }
    public static long strtoull(long p, long end, int base) { return strtoll(p, end, base); }
    public static int atoi(long p) { return (int) strtoll(p, 0, 10); }
    public static long atol(long p) { return strtoll(p, 0, 10); }

    // ------------------------------------------------------------------ printf

    static final class Buf {
        byte[] a = new byte[64]; int n;
        void b(int c) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = (byte) c; }
        void s(String s) { for (int i = 0; i < s.length(); i++) b(s.charAt(i)); }
        void pad(int c, int k) { for (int i = 0; i < k; i++) b(c); }
    }

    static Buf format(long f, long[] va) {
        Buf o = new Buf();
        int ai = 0;
        for (long p = f; ; p++) {
            int c = U.getByte(p) & 0xFF;
            if (c == 0) break;
            if (c != '%') { o.b(c); continue; }
            p++;
            boolean left = false, plus = false, space = false, alt = false, zero = false;
            for (;; p++) {
                c = U.getByte(p);
                if (c == '-') left = true; else if (c == '+') plus = true; else if (c == ' ') space = true;
                else if (c == '#') alt = true; else if (c == '0') zero = true; else break;
            }
            int width = 0, prec = -1;
            if (U.getByte(p) == '*') { width = (int) va[ai++]; if (width < 0) { left = true; width = -width; } p++; }
            else while (Character.isDigit(U.getByte(p))) width = width * 10 + U.getByte(p++) - '0';
            if (U.getByte(p) == '.') {
                p++; prec = 0;
                if (U.getByte(p) == '*') { prec = (int) va[ai++]; p++; if (prec < 0) prec = -1; }
                else while (Character.isDigit(U.getByte(p))) prec = prec * 10 + U.getByte(p++) - '0';
            }
            int len = 0; // 0 int, 1 hh, 2 h, 3 long
            for (;; p++) {
                c = U.getByte(p);
                if (c == 'h') len = len == 2 ? 1 : 2; else if (c == 'l' || c == 'j' || c == 'z' || c == 't' || c == 'q' || c == 'L') len = 3; else break;
            }
            c = U.getByte(p);
            String body; String sign = ""; String prefix = "";
            boolean numeric = true;
            switch (c) {
                case 'd': case 'i': {
                    long v = va[ai++];
                    v = len == 1 ? (byte) v : len == 2 ? (short) v : len == 3 ? v : (int) v;
                    if (v < 0) { sign = "-"; body = v == Long.MIN_VALUE ? "9223372036854775808" : Long.toString(-v); }
                    else { sign = plus ? "+" : space ? " " : ""; body = Long.toString(v); }
                    body = intPrec(body, prec);
                    break;
                }
                case 'u': case 'x': case 'X': case 'o': {
                    long v = va[ai++];
                    v = len == 1 ? v & 0xFF : len == 2 ? v & 0xFFFF : len == 3 ? v : v & 0xFFFFFFFFL;
                    int radix = c == 'u' ? 10 : c == 'o' ? 8 : 16;
                    body = Long.toUnsignedString(v, radix);
                    if (c == 'X') body = body.toUpperCase();
                    body = intPrec(body, prec);
                    if (alt && v != 0 && radix == 16) prefix = c == 'X' ? "0X" : "0x";
                    if (alt && radix == 8 && !body.startsWith("0")) body = "0" + body;
                    break;
                }
                case 'p': body = Long.toHexString(va[ai++]); prefix = "0x"; break;
                case 'c': numeric = false; body = null; {
                    int k = width > 1 ? width - 1 : 0;
                    if (!left) o.pad(' ', k);
                    o.b((int) va[ai++]);
                    if (left) o.pad(' ', k);
                    continue;
                }
                case 's': {
                    numeric = false;
                    long sp = va[ai++];
                    byte[] b;
                    if (sp == 0) b = "(null)".getBytes();
                    else { long n = 0; while ((prec < 0 || n < prec) && U.getByte(sp + n) != 0) n++; b = bytes(sp, (int) n); }
                    int k = Math.max(0, width - b.length);
                    if (!left) o.pad(' ', k);
                    for (byte x : b) o.b(x);
                    if (left) o.pad(' ', k);
                    continue;
                }
                case 'f': case 'F': case 'e': case 'E': case 'g': case 'G': case 'a': case 'A': {
                    double d = Double.longBitsToDouble(va[ai++]);
                    boolean neg = d < 0 || (d == 0 && 1 / d < 0);
                    sign = neg ? "-" : plus ? "+" : space ? " " : "";
                    double a = Math.abs(d);
                    boolean upper = Character.isUpperCase(c);
                    if (Double.isNaN(d)) { body = upper ? "NAN" : "nan"; sign = plus ? "+" : space ? " " : ""; zero = false; }
                    else if (Double.isInfinite(d)) { body = upper ? "INF" : "inf"; zero = false; }
                    else {
                        char lc = Character.toLowerCase((char) c);
                        body = lc == 'f' ? fmtF(a, prec < 0 ? 6 : prec, alt) : lc == 'e' ? fmtE(a, prec < 0 ? 6 : prec, alt) :
                            lc == 'g' ? fmtG(a, prec < 0 ? 6 : prec, alt) : Double.toHexString(a).replace("p", "p+").replace("p+-", "p-");
                        if (upper) body = body.toUpperCase();
                    }
                    break;
                }
                case '%': o.b('%'); continue;
                case 'n': ai++; continue;
                default: o.b('%'); o.b(c); continue;
            }
            int total = sign.length() + prefix.length() + body.length();
            int k = Math.max(0, width - total);
            if (left) { o.s(sign); o.s(prefix); o.s(body); o.pad(' ', k); }
            else if (zero && numeric && !(prec >= 0 && "diuxXo".indexOf(c) >= 0)) { o.s(sign); o.s(prefix); o.pad('0', k); o.s(body); }
            else { o.pad(' ', k); o.s(sign); o.s(prefix); o.s(body); }
        }
        return o;
    }

    private static String intPrec(String body, int prec) {
        if (prec == 0 && body.equals("0")) return "";
        if (prec > body.length()) return "0".repeat(prec - body.length()) + body;
        return body;
    }

    /** %f of a non-negative finite value, correctly rounded from the exact binary value (like C). */
    static String fmtF(double a, int prec, boolean alt) {
        String s = new BigDecimal(a).setScale(prec, RoundingMode.HALF_EVEN).toPlainString();
        return prec == 0 && alt ? s + "." : s;
    }

    /** %e of a non-negative finite value. */
    static String fmtE(double a, int prec, boolean alt) {
        String digits; int exp;
        if (a == 0) { digits = "0".repeat(prec + 1); exp = 0; }
        else {
            BigDecimal r = new BigDecimal(a).round(new MathContext(prec + 1, RoundingMode.HALF_EVEN));
            BigInteger unscaled = r.unscaledValue();
            String u = unscaled.toString();
            exp = u.length() - 1 - r.scale();
            digits = u.length() >= prec + 1 ? u.substring(0, prec + 1) : u + "0".repeat(prec + 1 - u.length());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(digits.charAt(0));
        if (prec > 0 || alt) sb.append('.');
        sb.append(digits, 1, digits.length());
        sb.append('e').append(exp < 0 ? '-' : '+');
        int ae = Math.abs(exp);
        if (ae < 10) sb.append('0');
        sb.append(ae);
        return sb.toString();
    }

    static String fmtG(double a, int prec, boolean alt) {
        int P = prec == 0 ? 1 : prec;
        int X;
        if (a == 0) X = 0;
        else { String e = fmtE(a, P - 1, false); X = Integer.parseInt(e.substring(e.indexOf('e') + 1)); }
        String s = (P > X && X >= -4) ? fmtF(a, P - 1 - X, alt) : fmtE(a, P - 1, alt);
        if (alt) return s;
        int e = s.indexOf('e');
        String mant = e >= 0 ? s.substring(0, e) : s, tail = e >= 0 ? s.substring(e) : "";
        if (mant.indexOf('.') >= 0) { mant = mant.replaceAll("0+$", ""); if (mant.endsWith(".")) mant = mant.substring(0, mant.length() - 1); }
        return mant + tail;
    }

    // ------------------------------------------------------------------ libm

    public static double sqrt(double x) { return Math.sqrt(x); }
    public static double cbrt(double x) { return Math.cbrt(x); }
    public static double pow(double x, double y) { return Math.pow(x, y); }
    public static double exp(double x) { return Math.exp(x); }
    public static double expm1(double x) { return Math.expm1(x); }
    public static double exp2(double x) { return Math.pow(2, x); }
    public static double log(double x) { return Math.log(x); }
    public static double log1p(double x) { return Math.log1p(x); }
    public static double log10(double x) { return Math.log10(x); }
    public static double log2(double x) { return Math.log(x) / Math.log(2); }
    public static double sin(double x) { return Math.sin(x); }
    public static double cos(double x) { return Math.cos(x); }
    public static double tan(double x) { return Math.tan(x); }
    public static double asin(double x) { return Math.asin(x); }
    public static double acos(double x) { return Math.acos(x); }
    public static double atan(double x) { return Math.atan(x); }
    public static double atan2(double y, double x) { return Math.atan2(y, x); }
    public static double sinh(double x) { return Math.sinh(x); }
    public static double cosh(double x) { return Math.cosh(x); }
    public static double tanh(double x) { return Math.tanh(x); }
    public static double hypot(double x, double y) { return Math.hypot(x, y); }
    public static double floor(double x) { return Math.floor(x); }
    public static double ceil(double x) { return Math.ceil(x); }
    public static double rint(double x) { return Math.rint(x); }
    public static double trunc(double x) { return x < 0 ? Math.ceil(x) : Math.floor(x); }
    /** C round(): halves away from zero. */
    // ---- iW integers, 64 < W <= 128 (JvmBackend.pushWide): (lo, hi) pairs, zero-extended to W bits. Each helper
    // returns lo and leaves hi in wh. Shift amounts are < W (LLVM: larger is poison).
    public static long wh;
    public static long wfix(long lo, long hi, int w) { wh = w == 128 ? hi : hi & ((1L << (w - 64)) - 1); return lo; }
    public static long wsext(long x, int w) { return wfix(x, x >> 63, w); }
    public static long wadd(long al, long ah, long bl, long bh, int w) { long lo = al + bl; return wfix(lo, ah + bh + (Long.compareUnsigned(lo, al) < 0 ? 1 : 0), w); }
    public static long wsub(long al, long ah, long bl, long bh, int w) { return wfix(al - bl, ah - bh - (Long.compareUnsigned(al, bl) < 0 ? 1 : 0), w); }
    public static long wmul(long al, long ah, long bl, long bh, int w) { return wfix(al * bl, Math.unsignedMultiplyHigh(al, bl) + al * bh + ah * bl, w); }
    public static long wand(long al, long ah, long bl, long bh, int w) { return wfix(al & bl, ah & bh, w); }
    public static long wor(long al, long ah, long bl, long bh, int w) { return wfix(al | bl, ah | bh, w); }
    public static long wxor(long al, long ah, long bl, long bh, int w) { return wfix(al ^ bl, ah ^ bh, w); }
    public static long wshl(long al, long ah, long bl, long bh, int w) {
        int s = (int) bl;
        if (s == 0) return wfix(al, ah, w);
        if (s >= 64) return wfix(0, al << (s - 64), w);
        return wfix(al << s, (ah << s) | (al >>> (64 - s)), w);
    }
    public static long wlshr(long al, long ah, long bl, long bh, int w) {
        int s = (int) bl;
        if (s == 0) return wfix(al, ah, w);
        if (s >= 64) return wfix(ah >>> (s - 64), 0, w);
        return wfix((al >>> s) | (ah << (64 - s)), ah >>> s, w);
    }
    public static long washr(long al, long ah, long bl, long bh, int w) {
        int s = (int) bl, k = 128 - w;
        ah = (ah << k) >> k; // sign-extend from bit w-1
        if (s == 0) return wfix(al, ah, w);
        if (s >= 64) return wfix(ah >> (s - 64), ah >> 63, w);
        return wfix((al >>> s) | (ah << (64 - s)), ah >> s, w);
    }

    public static double round(double x) { if (Double.isNaN(x) || Double.isInfinite(x)) return x; double t = trunc(x); return Math.abs(x - t) >= 0.5 ? t + Math.signum(x) : t; }
    public static double fmod(double x, double y) { return x % y; }
    public static double fabs(double x) { return Math.abs(x); }
    public static double fmin(double x, double y) { return minnum(x, y); }
    public static double fmax(double x, double y) { return maxnum(x, y); }
    public static float sqrtf(float x) { return (float) Math.sqrt(x); }
    public static float powf(float x, float y) { return (float) Math.pow(x, y); }
    public static float floorf(float x) { return (float) Math.floor(x); }
    public static float ceilf(float x) { return (float) Math.ceil(x); }
    public static float fabsf(float x) { return Math.abs(x); }
    public static float fmodf(float x, float y) { return x % y; }
    public static float roundf(float x) { return (float) round(x); }
    public static float truncf(float x) { return (float) trunc(x); }
    public static float expf(float x) { return (float) Math.exp(x); }
    public static float logf(float x) { return (float) Math.log(x); }
    public static float sinf(float x) { return (float) Math.sin(x); }
    public static float cosf(float x) { return (float) Math.cos(x); }

    // ------------------------------------------------------------------ helpers for LLVM intrinsics and casts

    public static double minnum(double a, double b) { return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.min(a, b); }
    public static double maxnum(double a, double b) { return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.max(a, b); }
    public static float minnum(float a, float b) { return Float.isNaN(a) ? b : Float.isNaN(b) ? a : Math.min(a, b); }
    public static float maxnum(float a, float b) { return Float.isNaN(a) ? b : Float.isNaN(b) ? a : Math.max(a, b); }
    public static int umax32(int a, int b) { return Integer.compareUnsigned(a, b) >= 0 ? a : b; }
    public static int umin32(int a, int b) { return Integer.compareUnsigned(a, b) <= 0 ? a : b; }
    public static long umax64(long a, long b) { return Long.compareUnsigned(a, b) >= 0 ? a : b; }
    public static long umin64(long a, long b) { return Long.compareUnsigned(a, b) <= 0 ? a : b; }
    public static int fshl32(int a, int b, int s) { s &= 31; return s == 0 ? a : (a << s) | (b >>> (32 - s)); }
    public static int fshr32(int a, int b, int s) { s &= 31; return s == 0 ? b : (a << (32 - s)) | (b >>> s); }
    public static long fshl64(long a, long b, long s) { int k = (int) (s & 63); return k == 0 ? a : (a << k) | (b >>> (64 - k)); }
    public static long fshr64(long a, long b, long s) { int k = (int) (s & 63); return k == 0 ? b : (a << (64 - k)) | (b >>> k); }
    public static long fptoui64(double d) { return d >= 0x1p63 ? (long) (d - 0x1p63) ^ Long.MIN_VALUE : (long) d; }
    public static long fptoui64(float d) { return fptoui64((double) d); }
    public static int fptouisat32(double d) { return d <= 0 || Double.isNaN(d) ? 0 : d >= 4294967295.0 ? -1 : (int) (long) d; }
    public static long fptouisat64(double d) { return d <= 0 || Double.isNaN(d) ? 0 : d >= 0x1p64 ? -1L : fptoui64(d); }
    public static double uitofp64(long v) { return v >= 0 ? (double) v : ((double) ((v >>> 1) | (v & 1))) * 2.0; }
    public static float uitofp64f(long v) { return (float) uitofp64(v); }
    public static int saddovf32(int a, int b) { int r = a + b; return ((a ^ r) & (b ^ r)) < 0 ? 1 : 0; }
    public static int ssubovf32(int a, int b) { int r = a - b; return ((a ^ b) & (a ^ r)) < 0 ? 1 : 0; }
    public static int smulovf32(int a, int b) { long r = (long) a * b; return r != (int) r ? 1 : 0; }
    public static int uaddovf32(int a, int b) { return Integer.compareUnsigned(a + b, a) < 0 ? 1 : 0; }
    public static int usubovf32(int a, int b) { return Integer.compareUnsigned(a, b) < 0 ? 1 : 0; }
    public static int umulovf32(int a, int b) { long r = (a & 0xFFFFFFFFL) * (b & 0xFFFFFFFFL); return (r >>> 32) != 0 ? 1 : 0; }
    public static int saddovf64(long a, long b) { long r = a + b; return ((a ^ r) & (b ^ r)) < 0 ? 1 : 0; }
    public static int ssubovf64(long a, long b) { long r = a - b; return ((a ^ b) & (a ^ r)) < 0 ? 1 : 0; }
    public static int smulovf64(long a, long b) { long h = Math.multiplyHigh(a, b), l = a * b; return h != (l >> 63) ? 1 : 0; }
    public static int uaddovf64(long a, long b) { return Long.compareUnsigned(a + b, a) < 0 ? 1 : 0; }
    public static int usubovf64(long a, long b) { return Long.compareUnsigned(a, b) < 0 ? 1 : 0; }
    public static int umulovf64(long a, long b) { return Math.unsignedMultiplyHigh(a, b) != 0 ? 1 : 0; }
}
