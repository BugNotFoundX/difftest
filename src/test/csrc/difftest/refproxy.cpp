/***************************************************************************************
* Copyright (c) 2020-2023 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* DiffTest is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include "refproxy.h"
#include <unistd.h>
#include <dlfcn.h>
#include <string>

uint8_t* ref_golden_mem = NULL;
const char *difftest_ref_so = NULL;

#define check_and_assert(func)                                \
  do {                                                        \
    if (!func) {                                              \
      throw std::runtime_error("REF is missing required symbol: " #func); \
    }                                                         \
  } while (0);

#ifdef LINKED_REFPROXY_LIB
#define DeclExtRefFunc(dummy, ref_func, ret, ...)             \
  extern "C" { extern RefFunc(ref_func, ret, __VA_ARGS__); }
REF_ALL(DeclExtRefFunc)

// Special notes on these weak symbols:
// For static libraries, the functions are linked at compile-time, as well as for the symbols.
// For dynamic libraries, despite being loaded at run-time, the symbols are looked up at compile-time.
// Be careful if you change the symbols in dynamic libraries after compiling and want to load it at run-time.
#define DeclWeakExtRefFunc(dummy, ref_func, ret, ...)             \
  extern "C" { extern RefFunc(ref_func, ret __attribute__((weak)), __VA_ARGS__); }
REF_OPTIONAL(DeclWeakExtRefFunc)
#endif

AbstractRefProxy::AbstractRefProxy(int coreid, size_t ram_size, const char *env, const char *file_path,
                                 RefBackend selected_backend) : backend(selected_backend) {
  if (backend == RefBackend::Unavailable || backend != default_ref_backend)
    throw std::runtime_error("REF backend does not match the generated ISA/state profile");
  handler = load_handler(env, file_path);
  try {
#ifdef LINKED_REFPROXY_LIB
#define GetRefFunc(dummy, ref_func, ret, ...) ref_func
#else
#define GetRefFunc(dummy, ref_func, ret, ...) load_function<RefFunc((*), ret, __VA_ARGS__)>(#ref_func)
#endif
#define LoadRefFunc(this_func, ref_func, ret, ...)  this_func = GetRefFunc(, ref_func, ret, __VA_ARGS__);
#define CheckRefFunc(this_func, ref_func, ret, ...) check_and_assert(this_func);

  if (backend == RefBackend::NemuRV32V2 || backend == RefBackend::NemuRV64V2 ||
      backend == RefBackend::NemuRV64GC) {
#ifdef LINKED_REFPROXY_LIB
    throw std::runtime_error("The NEMU v2 backend currently requires a dynamically loaded REF");
#else
    if (NUM_CORES != 1 || coreid != 0)
      throw std::runtime_error("The NEMU v2 backend currently supports one hart");
    ref_init_v2 = load_function<void (*)(unsigned)>("difftest_init_v2");
    ref_status = load_function<int (*)()>("difftest_status");
    ref_regcpy_v2 = load_function<void (*)(void *, bool)>("difftest_regcpy");
    ref_memcpy = load_function<void (*)(uint64_t, void *, size_t, bool)>("difftest_memcpy");
    ref_exec = load_function<void (*)(uint64_t)>("difftest_exec");
    ref_close = load_function<void (*)()>("difftest_close");
    check_and_assert(ref_init_v2);
    check_and_assert(ref_status);
    check_and_assert(ref_regcpy_v2);
    check_and_assert(ref_memcpy);
    check_and_assert(ref_exec);
    store_commit = load_function<int (*)(uint64_t *, uint64_t *, uint8_t *)>("difftest_store_commit");
    check_and_assert(store_commit);
    update_config = load_function<void (*)(void *)>("update_dynamic_config");
    ref_csrcpy = load_function<void (*)(void *, bool)>("difftest_csrcpy");
    ref_reg_display = load_function<void (*)()>("difftest_display");
    if (backend == RefBackend::NemuRV64GC) {
      raise_intr = load_function<void (*)(uint64_t)>("difftest_raise_intr");
      uarchstatus_sync = load_function<void (*)(void *)>("difftest_uarchstatus_sync");
      check_and_assert(raise_intr);
      check_and_assert(uarchstatus_sync);
      check_and_assert(ref_csrcpy);
    }
    ref_set_ramsize = load_function<void (*)(size_t)>("difftest_set_ramsize");
    if (ram_size) {
      check_and_assert(ref_set_ramsize);
      ref_set_ramsize(ram_size);
    }
    ref_init_v2(sizeof(RefState));
    check_status();
    return;
#endif
  }
  REF_ALL(LoadRefFunc)
  REF_ALL(CheckRefFunc)
  REF_OPTIONAL(LoadRefFunc)

  if (NUM_CORES > 1) {
    check_and_assert(ref_set_mhartid);
    ref_set_mhartid(coreid);

    check_and_assert(ref_put_gmaddr);
    check_and_assert(ref_golden_mem);
    ref_put_gmaddr(ref_golden_mem);
  }

  // set ram_size before ref_init()
  if (ram_size > 0) {
    check_and_assert(ref_set_ramsize);
    ref_set_ramsize(ram_size);
  }

  ref_init();
  } catch (...) {
    if (ref_close) ref_close();
    if (handler) dlclose(handler);
    handler = nullptr;
    throw;
  }
}

void AbstractRefProxy::check_status() const {
  if (ref_status && ref_status() != 0) throw std::runtime_error("NEMU REF reported failure");
}

void AbstractRefProxy::copy_state(void *state, bool to_ref, bool legacy_restore) {
  if (backend == RefBackend::NemuRV32V2 || backend == RefBackend::NemuRV64V2 ||
      backend == RefBackend::NemuRV64GC) ref_regcpy_v2(state, to_ref);
  else ref_regcpy(state, to_ref, legacy_restore);
  check_status();
}

void AbstractRefProxy::require_legacy(const char *operation) const {
  if (backend != RefBackend::LegacyRV64 && backend != RefBackend::NemuRV64GC)
    throw std::runtime_error(std::string("REF profile does not support ") + operation);
}

AbstractRefProxy::~AbstractRefProxy() {
  if (handler) {
    dlclose(handler);
  }
};

void *AbstractRefProxy::load_handler(const char *env, const char *file_path) {
#ifdef LINKED_REFPROXY_LIB
  Info("The reference model (dynamically or statically linked at compile-time) is %s\n", LINKED_REFPROXY_LIB);
  return nullptr;
#endif

  std::string path;
  if (difftest_ref_so) {
    path = difftest_ref_so;
  } else {
    const char *ref_home = env ? getenv(env) : nullptr;
    if (ref_home == NULL) {
#ifdef REF_HOME
      ref_home = REF_HOME;
#else
      throw std::runtime_error("Specify a REF library or its home directory");
#endif // REF_HOME
    }
    if (!file_path) throw std::runtime_error("No default REF library for this profile");
    path = std::string(ref_home) + "/" + file_path;
  }

  Info("The reference model is %s\n", path.c_str());

  int mode = RTLD_LAZY;
  // ASan must interpose allocator calls in a sanitized reference too.
#if !defined(DIFFTEST_SANITIZE)
  mode |= RTLD_DEEPBIND;
#endif
  void *so_handler = (NUM_CORES > 1) ? dlmopen(LM_ID_NEWLM, path.c_str(), mode) : dlopen(path.c_str(), mode);
  if (!so_handler) throw std::runtime_error(std::string("Cannot load REF: ") + dlerror());

  return so_handler;
}

template <typename T>
T AbstractRefProxy::load_function(const char *func_name) {
  check_and_assert(handler);
  return reinterpret_cast<T>(dlsym(handler, func_name));
}

RefProxy::~RefProxy() {
  if (ref_close) {
    ref_close();
  }
}


void RefProxy::regcpy(DiffTestState *dut) {
  state.regs_int = dut->regs_int;
#ifdef CONFIG_DIFFTEST_ARCHFPREGSTATE
  state.regs_fp = dut->regs_fp;
#endif // CONFIG_DIFFTEST_ARCHFPREGSTATE
#ifdef CONFIG_DIFFTEST_CSRSTATE
  state.csr = dut->csr;
#endif
#ifdef CONFIG_DIFFTEST_FCSRSTATE
  state.fcsr = dut->fcsr;
#endif
  state.pc = dut->commit[0].pc;
#ifdef CONFIG_DIFFTEST_ARCHVECREGSTATE
  state.regs_vec = dut->regs_vec;
#endif // CONFIG_DIFFTEST_ARCHVECREGSTATE
#ifdef CONFIG_DIFFTEST_VECCSRSTATE
  state.vcsr = dut->vcsr;
#endif // CONFIG_DIFFTEST_VECCSRSTATE
  copy_state(&state, DUT_TO_REF, false);
};

int RefProxy::compare(DiffTestState *dut) {
#define PROXY_COMPARE(field) memcmp(&(dut->field), &(state.field), sizeof(state.field))

  const int results[] = {
    PROXY_COMPARE(regs_int),
#ifdef CONFIG_DIFFTEST_ARCHFPREGSTATE
    PROXY_COMPARE(regs_fp),
#endif // CONFIG_DIFFTEST_ARCHFPREGSTATE
#ifdef CONFIG_DIFFTEST_ARCHVECREGSTATE
    PROXY_COMPARE(regs_vec),
#endif // CONFIG_DIFFTEST_ARCHVECREGSTATE
#ifdef CONFIG_DIFFTEST_VECCSRSTATE
    PROXY_COMPARE(vcsr),
#endif // CONFIG_DIFFTEST_VECCSRSTATE
#ifdef CONFIG_DIFFTEST_FCSRSTATE
    PROXY_COMPARE(fcsr),
#endif
#ifdef CONFIG_DIFFTEST_CSRSTATE
    PROXY_COMPARE(csr)
#endif

  };
  for (int i = 0; i < sizeof(results) / sizeof(int); i++) {
    if (results[i]) {
      // There may be some waive rules for CSRs
#ifdef CONFIG_DIFFTEST_CSRSTATE
      if (i == sizeof(results) / sizeof(int) - 1) {
        // If mismatches are cleared, we sync the states back to REF.
        if (do_csr_waive(dut) && !PROXY_COMPARE(csr)) {
          sync(true);
          return 0;
        }
      }
#endif
      return 1;
    }
  }
  return 0;
};

// Copy individual scalar words instead of aliasing a struct as uint64_t[].
static void display_words(const void *dut, const void *ref, const char *const *names,
                          size_t count, uint64_t pc, size_t width) {
  for (size_t i = 0; i < count; ++i) {
    uint64_t actual = 0, expected = 0;
    memcpy(&actual, static_cast<const uint8_t *>(dut) + i * width, width);
    memcpy(&expected, static_cast<const uint8_t *>(ref) + i * width, width);
    if (actual != expected)
      printf("%7s different at pc = 0x%010lx, right= 0x%016lx, wrong = 0x%016lx\n",
             names[i], pc, expected, actual);
  }
}

void RefProxy::display(DiffTestState *dut) {
  if (dut) {
#define PROXY_COMPARE_AND_DISPLAY(field, field_names)                   \
do {                                                                  \
  using element_t = std::remove_reference_t<decltype(state.field.value[0])>; \
  const auto *_ptr_dut = dut->field.value;                              \
  const auto *_ptr_ref = state.field.value;                             \
  for (size_t i = 0; i < sizeof(state.field) / sizeof(element_t); i ++) { \
    if (_ptr_dut[i] != _ptr_ref[i]) {                                 \
      printf("%7s different at pc = 0x%010lx, right= 0x%016lx, "      \
              "wrong = 0x%016lx\n", field_names[i], uint64_t(dut->commit[0].pc), \
              uint64_t(_ptr_ref[i]), uint64_t(_ptr_dut[i]));             \
    }                                                                 \
  }                                                                   \
} while(0);

    PROXY_COMPARE_AND_DISPLAY(regs_int, regs_name_int)
#ifdef CONFIG_DIFFTEST_ARCHFPREGSTATE
    PROXY_COMPARE_AND_DISPLAY(regs_fp, regs_name_fp)
#endif // CONFIG_DIFFTEST_ARCHFPREGSTATE
#ifdef CONFIG_DIFFTEST_CSRSTATE
    display_words(&dut->csr, &state.csr, regs_name_csr, 18, dut->commit[0].pc, sizeof(diff_int_t));
#endif
#ifdef CONFIG_DIFFTEST_FCSRSTATE
    const char *fcsr_name[] = {"fcsr"};
    display_words(&dut->fcsr, &state.fcsr, fcsr_name, 1, dut->commit[0].pc, sizeof(uint64_t));
#endif
#ifdef CONFIG_DIFFTEST_ARCHVECREGSTATE
    PROXY_COMPARE_AND_DISPLAY(regs_vec, regs_name_vec)
#endif // CONFIG_DIFFTEST_ARCHVECREGSTATE
#ifdef CONFIG_DIFFTEST_VECCSRSTATE
    display_words(&dut->vcsr, &state.vcsr, regs_name_vec_csr, 7, dut->commit[0].pc, sizeof(diff_int_t));
#endif // CONFIG_DIFFTEST_VECCSRSTATE
  }
  else {
    if (ref_reg_display) ref_reg_display();
    else {
      printf("REF pc = 0x%016lx\n", uint64_t(state.pc));
      for (unsigned i = 0; i < 32; ++i)
        printf("%7s = 0x%016lx\n", regs_name_int[i], read_arch_reg(i));
    }
  }
};

#ifdef CPU_ROCKET_CHIP
// similar function as encodeVirtualAddress@RocketCore.scala: 1151
static uint64_t encode_vaddr(uint64_t vaddr) {
  int64_t hi = (int64_t)vaddr >> 39;
  if (hi == 0 || hi == -1) {
    return vaddr;
  }
  hi = ((vaddr >> 38) & 0x1) ? 0 : -1;
  uint64_t mask = (1UL << 39) - 1;
  return (vaddr & mask) | (hi & (~mask));
}

static uint64_t sext_vaddr_40bit(uint64_t vaddr) {
  uint64_t hi = ((vaddr >> 39) & 0x1) ? -1UL : 0;
  uint64_t mask = (1UL << 40) - 1;
  return (vaddr & mask) | (hi & (~mask));
}

static uint64_t read_stvec(uint64_t vaddr) {
  vaddr = vaddr & ((1UL << 39) - 1);
  vaddr = vaddr & (~((vaddr & 0x1) ? 0xfeUL : 0x2UL));
  return ((int64_t)vaddr << 25) >> 25;
}

static uint64_t read_mtvec(uint64_t paddr) {
  paddr = paddr & ((1UL << 32) - 1);
  return paddr & (~((paddr & 0x1) ? 0xfeUL : 0x2UL));
}
#endif // CPU_ROCKET_CHIP

#ifdef CONFIG_DIFFTEST_CSRSTATE
bool RefProxy::do_csr_waive(DiffTestState *dut) {
#define CSR_WAIVE(field, mapping)                             \
  do {                                                        \
    uint64_t v = mapping(state.csr.field);                          \
    if (state.csr.field != dut->csr.field && dut->csr.field == v) { \
      state.csr.field = v;                                          \
      has_waive = true;                                       \
    }                                                         \
  } while (0);

  bool has_waive = false;
#ifdef CPU_ROCKET_CHIP
  CSR_WAIVE(mtval, encode_vaddr);
  CSR_WAIVE(mtval, sext_vaddr_40bit);
  CSR_WAIVE(stval, encode_vaddr);
  CSR_WAIVE(stval, sext_vaddr_40bit);
  CSR_WAIVE(mtvec, read_mtvec);
  CSR_WAIVE(stvec, read_stvec);
#endif // CPU_ROCKET_CHIP
  return has_waive;
}
#endif

#define NEMU_ENV_VARIABLE "NEMU_HOME"
#ifdef DIFFTEST_REF_ABI_NEMU_RV32_V2
#define NEMU_SO_FILENAME  "build/riscv32-nemu-interpreter-so"
#else
#define NEMU_SO_FILENAME  "build/riscv64-nemu-interpreter-so"
#endif
NemuProxy::NemuProxy(int coreid, size_t ram_size) :
  RefProxy(coreid, ram_size, NEMU_ENV_VARIABLE, NEMU_SO_FILENAME) {
}

#define SPIKE_ENV_VARIABLE "SPIKE_HOME"
#define SPIKE_SO_FILENAME  "difftest/build/riscv64-spike-so"
SpikeProxy::SpikeProxy(int coreid, size_t ram_size) :
  RefProxy(coreid, ram_size, SPIKE_ENV_VARIABLE, SPIKE_SO_FILENAME) {
}

LinkedProxy::LinkedProxy(int coreid, size_t ram_size) : RefProxy(coreid, ram_size) {
  if (NUM_CORES > 1) {
    printf("LinkedProxy does not support NUM_CORES(%d) > 1. Please use other REFs.\n", NUM_CORES);
    fflush(stdout);
    assert(0);
  }
}
