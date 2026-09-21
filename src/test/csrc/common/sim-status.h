#ifndef DIFFTEST_SIM_STATUS_H
#define DIFFTEST_SIM_STATUS_H

// Shared checker/runner result codes; independent of any Verilated top.
enum {
  STATE_GOODTRAP = 0,
  STATE_BADTRAP = 1,
  STATE_ABORT = 2,
  STATE_LIMIT_EXCEEDED = 3,
  STATE_SIG = 4,
  STATE_AMBIGUOUS = 5,
  STATE_SIM_EXIT = 6,
  STATE_FUZZ_COND = 7,
  STATE_RUNNING = -1
};

#endif
