#include "VTransportTop.h"
#include "VTransportTop__Dpi.h"
#include "difftest-dpic.h"
#include "difftest.h"
#include <cassert>
#include <cstdint>
#include <iostream>

TestDifftest state{};
TestDifftest *cores[] = {&state};
TestDifftest **difftest = cores;

int main() {
  VTransportTop dut;
  auto tick = [&]() {
    dut.clk = 0; dut.eval();
    dut.clk = 1; dut.eval();
  };
  using Word = decltype(state.dut.regs_int.value[0] + 0u);
  const Word pc = sizeof(Word) == 8 ? Word(0xFEDCBA9880000010ULL) : Word(0x80000010U);
  const Word data = sizeof(Word) == 8 ? Word(0x87654321ABCDEF80ULL) : Word(0xABCDEF80U);
  dut.io_valid = 1;
  dut.io_pc = pc;
  dut.io_data = data;
  dut.io_address = 64;
  dut.reset = 1;
  tick();
  assert(!state.dut.event.valid && !state.dut.commit[0].valid && !state.dut.commit[1].valid);
  dut.reset = 0;
  tick();
  assert(state.dut.event.valid && state.dut.event.exception == 0);
  assert(state.dut.event.exceptionPC == pc);
  assert(!state.dut.commit[0].valid);
  assert(state.dut.commit[1].valid && state.dut.commit[1].pc == Word(pc + 4));
  assert(state.dut.commit[1].wpdest == 64);
  assert(state.dut.regs_int.value[0] == data && state.dut.regs_int.value[31] == Word(data + 31));
  assert(state.dut.wb_int[64].data == data);
  assert(state.dut.store[0].valid && state.dut.store[0].addr == pc);
  assert(state.dut.store[0].data == data && state.dut.store[0].mask == 1);
  assert(state.dut.sbuffer[0].data[0] == uint8_t(data));
  assert(state.dut.refill[0].data[0] == data);
  dut.io_pc = Word(pc + 8);
  tick();
  assert(state.dut.commit[0].valid && state.dut.commit[0].pc == pc);
  assert(state.dut.commit[0].robIdx == 0x10001 && state.dut.commit[0].special == 2);
  dut.io_valid = 0;
  dut.io_address = 127; // An invalid flattened write must not index the state array.
  tick();
  assert(!state.dut.event.valid && !state.dut.store[0].valid && !state.dut.commit[1].valid);
  assert(state.dut.commit[0].valid);
  tick();
  assert(!state.dut.commit[0].valid);
  assert(state.dut.wb_int[64].data == data);
  dut.io_valid = 1;
  dut.io_address = 64;
  tick();
  assert(state.dut.commit[1].valid);
  dut.reset = 1;
  tick();
  assert(!state.dut.event.valid && !state.dut.commit[0].valid && !state.dut.commit[1].valid);
  dut.final();
  std::cout << "DPI transport RV" << sizeof(Word) * 8 << " passed\n";
}
