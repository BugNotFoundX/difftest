#ifndef DIFFTEST_REF_STATE_H
#define DIFFTEST_REF_STATE_H

#include "common.h"
#include <cstddef>
#include <type_traits>

// Headers generated before schema v1 describe the historical RV64 ABI.
#ifndef DIFFTEST_SCHEMA_VERSION
#define DIFFTEST_XLEN 64
#define DIFFTEST_ISA_RISCV64 1
#define DIFFTEST_REF_ABI_LEGACY_RV64 1
#endif

using diff_int_t = std::remove_reference_t<decltype(DifftestArchIntRegState{}.value[0])>;
using diff_pc_t = diff_int_t;
static_assert(sizeof(diff_int_t) * 8 == DIFFTEST_XLEN, "GPR/target width mismatch");
static_assert(sizeof(DifftestArchIntRegState) == 32 * sizeof(diff_int_t), "GPR layout mismatch");
#ifdef CONFIG_DIFFTEST_ARCHFPREGSTATE
using diff_fp_t = std::remove_reference_t<decltype(DifftestArchFpRegState{}.value[0])>;
#ifdef DIFFTEST_FLEN
static_assert(sizeof(diff_fp_t) * 8 == DIFFTEST_FLEN, "FPR/target width mismatch");
#endif
#endif

// Only this standard-layout object crosses regcpy. No C++ object/vtable/member
// adjacency is part of the reference ABI. The legacy field order is unchanged.
struct RefState {
  DifftestArchIntRegState regs_int;
#ifdef CONFIG_DIFFTEST_ARCHFPREGSTATE
  DifftestArchFpRegState regs_fp;
#endif
#ifdef CONFIG_DIFFTEST_CSRSTATE
  DifftestCSRState csr;
#elif defined(DIFFTEST_REF_ABI_NEMU_RV64_V2)
  // Current integer NEMU wire includes 18 CSR words even without DUT CSR probes.
  uint64_t ref_csrs[18];
#endif
  diff_pc_t pc;
#ifdef CONFIG_DIFFTEST_ARCHVECREGSTATE
  DifftestArchVecRegState regs_vec;
#endif
#ifdef CONFIG_DIFFTEST_VECCSRSTATE
  DifftestVecCSRState vcsr;
#endif
#ifdef CONFIG_DIFFTEST_FCSRSTATE
  DifftestFCSRState fcsr;
#endif
};
static_assert(std::is_standard_layout_v<RefState> && std::is_trivially_copyable_v<RefState>);
static_assert(offsetof(RefState, regs_int) == 0);
#ifdef DIFFTEST_REF_ABI_NEMU_RV32_V2
static_assert(sizeof(RefState) == 132 && offsetof(RefState, pc) == 128,
              "nemu-rv32-v2 requires exactly 32 GPRs followed by PC");
#endif
#ifdef DIFFTEST_REF_ABI_LEGACY_RV64
static_assert(sizeof(diff_pc_t) == 8);
static_assert(sizeof(DifftestCSRState) == 18 * 8, "legacy RV64 CSR ABI mismatch");
static_assert(offsetof(RefState, csr) == 32 * 8
#ifdef CONFIG_DIFFTEST_ARCHFPREGSTATE
              + 32 * 8
#endif
);
static_assert(offsetof(RefState, pc) == offsetof(RefState, csr) + 18 * 8);
#endif

#ifdef DIFFTEST_REF_ABI_NEMU_RV64_V2
static_assert(sizeof(RefState) == 408 && offsetof(RefState, pc) == 400,
              "integer NEMU RV64 v2 wire layout mismatch");
#endif

#ifdef DIFFTEST_REF_ABI_NEMU_RV64GC
static_assert(sizeof(RefState) == 672 && offsetof(RefState, csr) == 512 &&
              offsetof(RefState, pc) == 656 && offsetof(RefState, fcsr) == 664,
              "RV64GC NEMU requires GPR, FPR, 18 CSR words, PC and FCSR");
#endif

enum class RefBackend { LegacyRV64, NemuRV32V2, NemuRV64V2, NemuRV64GC, Unavailable };
constexpr RefBackend default_ref_backend =
#if defined(DIFFTEST_REF_ABI_NEMU_RV64GC)
  RefBackend::NemuRV64GC;
#elif defined(DIFFTEST_REF_ABI_NEMU_RV32_V2)
  RefBackend::NemuRV32V2;
#elif defined(DIFFTEST_REF_ABI_NEMU_RV64_V2)
  RefBackend::NemuRV64V2;
#elif defined(DIFFTEST_REF_ABI_LEGACY_RV64)
  RefBackend::LegacyRV64;
#else
  RefBackend::Unavailable;
#endif

#endif
