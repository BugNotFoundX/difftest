#include "VMemoryTop.h"
#include "VMemoryTop__Dpi.h"
#include <cassert>
#include <cstdint>
#include <iostream>

static uint64_t ram[32]{};
static unsigned writes;
extern "C" unsigned long long difftest_ram_read(unsigned long long index) {
  assert(index < 32);
  return ram[index];
}
extern "C" void difftest_ram_write(unsigned long long index,
                                 unsigned long long data, unsigned long long mask) {
  assert(index < 32);
  ram[index] = (ram[index] & ~mask) | (data & mask);
  ++writes;
}
int main() {
  VMemoryTop dut;
  auto tick = [&]() { dut.clk = 0; dut.eval(); dut.clk = 1; dut.eval(); };
  dut.io_index = 1;
  dut.io_low = 0x1122334455667788ULL;
  dut.io_high = 0x99AABBCCDDEEFF00ULL;
  dut.io_mask = 0x0101;
  dut.io_wen = 1;
  dut.io_ren = 0;
  dut.reset = 1;
  tick();
  assert(writes == 0);
  dut.reset = 0;
  tick();
  assert(writes == 4);
  assert(ram[2] == 0x88 && ram[3] == 0 && ram[10] == 0x88 && ram[11] == 0);
  dut.io_mask = 0xFFFE;
  tick();
  assert(ram[2] == dut.io_low && ram[3] == dut.io_high);
  assert(ram[10] == dut.io_low && ram[11] == dut.io_high);
  dut.io_wen = 0;
  dut.io_ren = 1;
  tick();
  assert(dut.io_oneLow == dut.io_low && dut.io_oneHigh == dut.io_high);
  assert(dut.io_twoLow == dut.io_low && dut.io_twoHigh == dut.io_high);
  dut.io_ren = 0;
  tick();
  tick();
  assert(dut.io_oneLow == dut.io_low && dut.io_twoHigh == dut.io_high);
  dut.final();
  std::cout << "RAM helpers passed\n";
}
