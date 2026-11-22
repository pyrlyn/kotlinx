package newkotlin

// LLVM module prelude of the native backend: TBAA metadata, target header and runtime declarations.

internal val TB_TYS = listOf("i1", "i8", "i16", "i32", "i64", "float", "double", "ptr")

/** Scalar TBAA type tree rooted at "kx": length, elem.<ty>, field.<ty> are disjoint siblings. */
internal fun tbaaMeta(): String = buildString {
    append("!20 = !{!\"kx tbaa\"}\n!19 = !{!\"kx.len\", !20, i64 0}\n!21 = !{!19, !19, i64 0}\n")
    TB_TYS.forEachIndexed { i, t ->
        append("!${70 + i} = !{!\"kx.elem.$t\", !20, i64 0}\n!${30 + i} = !{!${70 + i}, !${70 + i}, i64 0}\n")
        append("!${90 + i} = !{!\"kx.field.$t\", !20, i64 0}\n!${50 + i} = !{!${90 + i}, !${90 + i}, i64 0}\n")
    }
}

/**
 * --target=js ([LlvmTarget.JS32]): reference counting works on the 32-bit halves of the i64 header word, which
 * JavaScript then handles as one int32 each instead of a pair: the count is the low word (offset 0), the flags of a
 * traced object the high word (offset 4: color bits 0-1, buffered bit 2). The memory layout is the same, so runtime.c
 * and the collectors still read the whole word; a count never carries into the flags.
 */
internal fun rc32() = llvmTarget == LlvmTarget.JS32

/** LLVM type of a retain / release on the header's count: the whole word, or its low word for [rc32]. */
internal fun cntTy() = if (rc32()) "i32" else "i64"

/** Target header and the declarations whose types depend on the target's C ABI ([LlvmTarget], [LIBC_SIGS]). */
internal fun targetPrelude(): String = "\n" + llvmTarget.header() +
    "declare ${llvmTarget.cSize} @llvm.coro.size.${llvmTarget.cSize}()\n" +
    LIBC_SIGS.entries.joinToString("") { (n, sig) -> "declare ${llvmTarget.cType(sig.first)} @$n(${sig.second.joinToString(", ") { llvmTarget.cType(it) }})\n" }

internal val PRELUDE = """@rt_argc = global i32 0
; `Unit?` non-null value: an object header with a huge refcount (retain/release never free it).
@rt_unit_sentinel = internal global { i64, ptr } { i64 1125899906842624, ptr @vt.rt_unit }
@vt.rt_unit = internal constant [1 x ptr] [ptr @rt_unit_rel]
define internal void @rt_unit_rel(ptr %p) {
  ret void
}
@rt_exc = internal global ptr null
declare token @llvm.coro.id(i32, ptr, ptr, ptr)
declare ptr @llvm.coro.begin(token, ptr)
declare i8 @llvm.coro.suspend(token, i1)
declare ptr @llvm.coro.free(token, ptr)
declare i1 @llvm.coro.end(ptr, i1, token)
declare i1 @llvm.coro.done(ptr)
declare void @llvm.coro.resume(ptr)
declare void @llvm.coro.destroy(ptr)
declare void @free(ptr)
declare i32 @strcmp(ptr, ptr)
declare ptr @getenv(ptr)
declare ptr @fopen(ptr, ptr)
declare i32 @fclose(ptr)
declare i64 @rt_nanos()
declare void @rt_panic(ptr) noreturn
declare void @rt_bad_step(i64) noreturn
declare void @rt_throw(ptr, ptr) noreturn
@rt_leakcheck = external global i32
declare void @rt_exit_flush()
declare void @rt_print_str(ptr)
declare void @rt_print_i64(i64)
declare void @rt_print_f64(double)
declare void @rt_print_f32(float)
declare void @rt_print_bool(i1 zeroext)
declare void @rt_newline()
declare ptr @rt_sb_new()
declare void @rt_sb_str(ptr, ptr)
declare void @rt_sb_i64(ptr, i64)
declare void @rt_sb_f64(ptr, double)
declare void @rt_sb_f32(ptr, float)
declare void @rt_sb_bool(ptr, i1 zeroext)
declare ptr @rt_sb_done(ptr)
declare ptr @rt_list_new(ptr)
declare void @rt_list_add(ptr, i64)
declare i64 @rt_list_get(ptr, i64)
declare i64 @rt_list_set(ptr, i64, i64)
declare i64 @rt_list_remove_at(ptr, i64)
declare i64 @rt_list_size(ptr)
declare void @rt_list_release(ptr)
declare void @rt_list_trav(ptr, ptr)
declare void @rt_list_release_untraced(ptr)
declare void @rt_map_trav(ptr, ptr)
declare void @rt_map_release_untraced(ptr)
declare void @rt_list_trav_owned(ptr, ptr, ptr)
declare void @rt_list_release_owned(ptr, ptr)
declare void @rt_list_retain_gc(ptr)
declare void @rt_list_own(ptr, ptr)
declare void @rt_list_hold(ptr, ptr)
declare void @rt_list_disown(ptr, ptr)
declare void @rt_map_trav_owned(ptr, ptr, ptr)
declare void @rt_map_release_owned(ptr, ptr)
declare void @rt_map_retain_gc(ptr)
declare void @rt_map_own(ptr, ptr)
declare void @rt_map_hold(ptr, ptr)
declare void @rt_map_disown(ptr, ptr)
declare ptr @rt_arr_new(i64, i64)
declare void @rt_oob(i64, i64) noreturn cold
declare void @rt_print_char(i32)
declare void @rt_print_u64(i64)
declare void @rt_sb_u64(ptr, i64)
declare i32 @rt_str_eq(ptr, ptr)
declare i32 @rt_str_hash(ptr)
declare i32 @rt_str_cmp(ptr, ptr)
declare i32 @rt_double_hash(double)
declare void @rt_sb_char(ptr, i32)
declare ptr @rt_sb_tostring(ptr)
declare i64 @rt_sb_len(ptr)
declare void @rt_sb_release(ptr)
declare ptr @rt_map_new()
declare ptr @rt_smap_new()
declare void @rt_sb_set_length(ptr, i64)
declare ptr @rt_map_with_vrel(ptr, ptr)
declare void @rt_map_put(ptr, i64, i64)
declare i64 @rt_map_get(ptr, i64)
declare i64 @rt_map_get_or(ptr, i64, i64)
declare i32 @rt_map_contains(ptr, i64)
declare i64 @rt_map_size(ptr)
declare i64 @rt_map_remove(ptr, i64, i64)
declare void @rt_map_clear(ptr)
declare i64 @rt_map_end(ptr)
declare i32 @rt_map_live(ptr, i64)
declare i64 @rt_map_key_at(ptr, i64)
declare i64 @rt_map_val_at(ptr, i64)
declare void @rt_map_release(ptr)
declare double @llvm.sqrt.f64(double)
declare double @llvm.fabs.f64(double)
declare float @llvm.sqrt.f32(float)
declare i32 @llvm.fptosi.sat.i32.f32(float)
declare i32 @llvm.fptosi.sat.i32.f64(double)
declare i64 @llvm.fptosi.sat.i64.f32(float)
declare i64 @llvm.fptosi.sat.i64.f64(double)
declare i32 @llvm.fptoui.sat.i32.f32(float)
declare i32 @llvm.fptoui.sat.i32.f64(double)
declare i64 @llvm.fptoui.sat.i64.f32(float)
declare i64 @llvm.fptoui.sat.i64.f64(double)
declare float @llvm.fabs.f32(float)
!0 = !{!"branch_weights", i32 2000, i32 1}
!1 = !{!"branch_weights", i32 1, i32 2000}
${tbaaMeta()}
define internal void @rt_release_dyn(ptr %p) {
entry:
  %isnull = icmp eq ptr %p, null
  br i1 %isnull, label %done, label %go
go:
  %vtp = getelementptr i8, ptr %p, i64 8
  %vt = load ptr, ptr %vtp
  %fn = load ptr, ptr %vt
  call void %fn(ptr %p)
  br label %done
done:
  ret void
}
declare ptr @rt_alloc(i64)
declare ptr @rt_alloc_trace(i64)
declare void @rt_free_trace(ptr, i64)
declare void @rt_hdr_retain(ptr)
declare void @rt_str_release(ptr)
declare void @rt_arr_release(ptr)
declare ptr @rt_str_from_c(ptr)
declare void @rt_cc_possible_root(ptr)
declare void @rt_cc_dead(ptr)
declare void @rt_free(ptr, i64)
@rt_rel_limit = external global ptr
declare void @rt_rel_defer(ptr)
declare ptr @llvm.stacksave.p0()
declare void @rt_gc_log_inc(ptr)
declare void @rt_gc_log_dec(ptr)
declare void @rt_gc_write_barrier(ptr)

define internal void @rt_retain_dyn(ptr %p) alwaysinline {
entry:
  %isnull = icmp eq ptr %p, null
  br i1 %isnull, label %done, label %chk
chk:
  %vtp = getelementptr i8, ptr %p, i64 8
  %vt = load ptr, ptr %vtp
  %cp = getelementptr i8, ptr %vt, i64 ${4 * llvmTarget.ptrBytes}
  %cy = load ptr, ptr %cp
  %iscy = icmp ne ptr %cy, null
  br i1 %iscy, label %log, label %inc
log:
  call void @rt_gc_log_inc(ptr %p)
  br label %done
inc:
  %c = load ${cntTy()}, ptr %p
  %c1 = add ${cntTy()} %c, 1
  store ${cntTy()} %c1, ptr %p
  br label %done
done:
  ret void
}

define internal void @rt_retain(ptr %p) alwaysinline {
entry:
  %isnull = icmp eq ptr %p, null
  br i1 %isnull, label %done, label %inc
inc:
  %c = load ${cntTy()}, ptr %p
  %c1 = add ${cntTy()} %c, 1
  store ${cntTy()} %c1, ptr %p
  br label %done
done:
  ret void
}

"""
