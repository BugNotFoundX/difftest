# Windrise DiffTest：Verilator 后端清理规划

## 范围与目标

- 审计日期：2026-09-20。
- 基线：`f292230bb44b906215d110bd73e1211c34bb91ed`，当前本地分支 `windrise`。
- 本轮交付：代码结构分析、分阶段删除清单、验证方案。尚未执行源代码删除或构建。
- 建议保留入口：原生 `emu` / `emu-verilator`，使用 Verilator 编译 `SimTop`，通过 DPI-C 驱动共享差分检查器和 NEMU。
- `simv VCS=verilator` 是另一个使用 Verilator 的 SV testbench 入口。本方案明确将其退役，统一使用原生 `emu`。
- 清理实施范围：本子模块的 Makefile、后端专属源文件、平台分支、相关说明与 CI 任务。后续功能裁剪单独成批。
- 首批保持：现有探针 ABI、参考模型 ABI、检查语义、默认传输行为。Windrise 的 RV32/SpinalHDL 接入另行实施。
- 本轮不修改主仓库 CPU、`src/main/scala/difftest`、NEMU 或 nexus-am，不手工编辑生成文件，不重排整个目录。
- 保留许可证、源文件版权说明和有效开发文档。

## 设计依据

官方文档将 DiffTest 分为 DUT 探针、软硬件通信、软件检查器和参考模型；Batch、Squash/Delta、NonBlock、Replay 主要针对通信成本进行优化。它们与 RTL 仿真器选择是两个维度，不能仅凭只使用 Verilator 就判定它们或所有硬件生成代码无用。

来源：[香山 DiffTest 文档](https://docs.xiangshan.cc/zh-cn/latest/tools/difftest/)。具体删除范围以当前代码依赖为准。

当前原生构建链：

```text
Makefile
  ├─ config + common + generated-src + difftest + 选中的 plugin
  └─ emu.mk
       ├─ src/test/csrc/emu
       └─ verilator.mk
            └─ src/test/csrc/verilator
```

当前数据链：

```text
探针定义 / profile
  → Difftest + Gateway
  → Preprocess + Validate（默认配置仍需要）
  → DPIC 生成 SV 调用与匹配的 C++ 接收器、状态布局
  → diffstate
  → Difftest::step / checkers
  → RefProxy / NEMU

emu/main → Emulator::tick → VerilatorSim 驱动 RTL
                        → get_difftest_step → difftest_nstep
```

关键源码证据：

- `Makefile:66,76,81,101` 收集 common、生成代码、topdown 和检查器；`326–335` 引入各后端及工具 Makefile。
- `emu.mk:61–83` 同时引入 Verilator 和 GSIM，并选择执行入口。
- `src/test/csrc/emu/main.cpp:35–43` 创建 Emulator 并驱动 tick；`emu.cpp:516–540` 用 step 端口推进差分检查。
- `src/main/scala/Gateway.scala:24–30,305–362` 静态依赖多种传输模块；关闭功能不等于可以直接删除 Scala 源码。
- `src/main/scala/SimTop.scala:32,146–205` 静态依赖 FPGA 类型和设备实例。
- `src/test/vsrc/vcs/DifftestEndpoint.sv` 由同目录 `top.v` 实例化，属于多个平台共用的 SV testbench 路径，原生 emu 不使用它。

## 删除和保留清单

以下路径均相对于本子模块。

| 分类 | 文件或目录 | 处理方式 |
| --- | --- | --- |
| 其他 RTL 后端入口 | `vcs.mk`、`uvs.mk`、`galaxsim.mk`、`palladium.mk`、`gsim.mk`、`fpga.mk` | 第一批删除，同时删除 include、目标、变量及 clean 依赖 |
| 其他运行驱动 | `src/test/csrc/vcs/`、`gsim/`、`fpga/`、`fpga_sim/` | 第一批删除，并清理 emu 内 GSIM 条件分支 |
| SV testbench / FPGA 仿真 | `src/test/vsrc/vcs/`、`src/test/vsrc/fpga_sim/` | 退役 simv/PLDM/UVS/GalaxSim/FPGA_SIM 后删除 |
| 平台脚本 | `scripts/palladium/`、`scripts/fpga/`、`scripts/fpga_sim/` | 第一批删除；`scripts/st_tools/` 先确认没有保留入口的调用再删 |
| FPGA 硬件适配 | `src/main/scala/fpga/` | 先解除 SimTop/Gateway 的 FPGA 引用，再删除 |
| FPGA 总线定义 | `src/main/scala/common/AXI4.scala`、`AXI4Lite.scala`、`AXI4Stream.scala` | 当前子模块消费者均在 FPGA 链路；解除消费者后删除 |
| 其他宿主接口 | `libso.mk`、`pdb.mk`、`src/test/csrc/plugin/xspdb/` | 在保留单一 emu 可执行入口的范围内退役；NEMU 的参考模型 `.so` 加载能力保留 |
| 原生运行主链 | `emu.mk`、`verilator.mk`、`src/test/csrc/emu/`、`verilator/` | 保留，先做平台选择分支清理 |
| 检查和参考模型 | `src/test/csrc/difftest/`、`checkers/`、`refproxy.*`、`diffstate.*`、`goldenmem.*` | 第一批保留；依据 Windrise 的架构契约再裁剪 |
| 共享运行支持 | `config/`、`src/test/csrc/common/`、`src/test/vsrc/common/` | 按依赖保留；不能整目录删除 |
| 接口生成器 | `build.sc`、`.mill-version`、`Bundles.scala`、`Difftest.scala`、`Gateway.scala`、`DPIC.scala`、`src/test/scala/` | 保留至替代生成器完成；`src/test/scala` 含非 Chisel CPU 的正式接口生成入口 |
| 默认状态处理 | `Preprocess.scala`、`Validate.scala`、`common/FileControl.scala`、`common/WiringControl.scala`、`util/Profile.scala` | 第一批保留 |
| 通信优化 | `Batch.scala`、`Delta.scala`、`Squash.scala`、`Replay.scala`、`Trace.scala` | 默认不开启；后续同时清理配置、调用、生成模板和 C++ 消费端后删除 |
| 香山扩展插件 | `plugin/runahead`、`simfrontend`、`constantin`、`topdown`，Scala `plugin/topdown` | Windrise 功能裁剪批次退役，同步去掉生成端和调用端 |
| Matrix / CUDA | `cuda.mk`、`difftest/mma/`、`checkers/matrix.cpp`、`matrix_store_tracker.h` | 属于指令检查能力，不能归类为 RTL 后端；在确认只支持 RV32IM 的阶段单独裁剪 |
| 可选调试和性能功能 | `pgo.mk`、LightSSS、snapshot、query、coverage、splitview | 原生 Verilator 也会用到；按需要保留或单独退役，不与其他后端混删 |
| 诊断与检查底线 | 波形、提交历史、assert、超时、失败退出码、标量存储检查 | 保留；反汇编 `plugin/spikedasm` 与 Spike 参考模型选择相互独立 |

## 前置条件与验证入口

静态审计已确认本机有 `verilator` 和 `sbt`；`mill` 尚未在 PATH 中找到。尚未验证可用参考模型共享库，也没有建立可运行的本地 DiffTest CPU 基线。清理前先补齐基线，记录工具和输入版本。

所有以下命令从 Windrise 根目录执行。`task_build` 使用独立目录，不运行会清空其他开发产物的根目录 clean。

当前仓库已有的接口生成和编译检查命令：

```bash
task_build="$PWD/target/difftest-cleanup-baseline"
make -C difftest difftest_verilog NOOP_HOME="$PWD" BUILD_DIR="$task_build" CONFIG=
cp "$task_build/generated-src/difftest_profile.json" "$task_build/fixture-profile.json"
make -C difftest difftest_verilog NOOP_HOME="$PWD" BUILD_DIR="$task_build" PROFILE="$task_build/fixture-profile.json" NUM_CORES=1 CONFIG=
make -C difftest emu NOOP_HOME="$PWD" BUILD_DIR="$task_build" WITH_CHISELDB=0 WITH_CONSTANTIN=0 EMU_TRACE=fst -j2
"$task_build/emu" --help
"$task_build/emu" --no-diff --ram-size=128MB -C 0
```

上面的 profile 样例只验证接口生成、编译和启动，不能证明处理器差分正确；`--no-diff` 不能充当清理的最终验收。

Phase 0 还需建立最小确定性测试夹具及独立回归脚本，拟命名为 `scripts/verify-verilator.sh`，本次尚未创建。脚本约定：

```bash
# 以下为拟新增脚本接口，Phase 0 实现后才可运行。
bash difftest/scripts/verify-verilator.sh --build-dir "$PWD/target/difftest-cleanup-p0" --suite all
```

脚本需完成：接口生成和全新构建、DPI 数据包传递、真实参考模型正例、错误 PC/GPR/store 负例、波形、超时和复位重复运行；汇总逐项退出码和比较次数。夹具使用与基线匹配的 ISA/参考模型，先验证现有后端，不提前混入 RV32 移植。若使用现成 DUT，则固定其版本、镜像及 REF 路径。

## Phase 0：固定基线和清理边界

**修改范围：** 新增最小测试夹具、`scripts/verify-verilator.sh` 和必要构建目标；保持原有功能实现。

**变化：** 当前只有依赖分析，尚无可验证的运行基线。建立独立的 Verilator+差分回归，并记录构建输入、预处理宏、生成接口、退休数量和退出码。

**命令：** 执行上面的完整生成/编译命令，然后执行：

```bash
bash difftest/scripts/verify-verilator.sh --build-dir "$PWD/target/difftest-cleanup-p0" --suite all
```

**通过条件：** 正例启用差分且比较次数非零；错误 PC、GPR、store 数据分别导致预期失败；能生成 FST；超时和正常结束可区分。基线未建立时，后续阶段不能声称已验证行为保持。

## Phase 1：移除其他运行后端

**修改范围：** 删除清单中的平台 `.mk`、C++/SV 驱动和平台脚本；修改 `Makefile`、`emu.mk`、`emu/simulator.h`、`emu/emu.cpp`；退役 libso/xspdb 替代入口。

**变化：** 多入口构建改为唯一 native emu，删除 `GSIM` 选择和其他平台的递归 make、clean 依赖。保留共享文件及默认生成器。

**同步事项：** 调整对应 CI 任务和文档，保留生成/格式检查及 Verilator 检查，不再提交依赖已删除目标的任务。保持通用 CI 环境，避免无关的容器或平台迁移。

**命令：**

```bash
bash difftest/scripts/verify-verilator.sh --build-dir "$PWD/target/difftest-cleanup-p1" --suite all
git -C difftest diff --check
rg -n 'include (vcs|uvs|galaxsim|palladium|gsim|fpga|libso|pdb)\.mk|gsim\.h|vcs-clean|pldm-clean|fpga-clean' difftest/Makefile difftest/emu.mk difftest/src/test/csrc/emu
```

**通过条件：** 回归与 Phase 0 相同；以上残留引用搜索无命中；干净环境构建不探测已移除平台的工具。旧目标应明确不可用，不能静默回退到其他行为。

## Phase 2：解除 FPGA/GSIM 生成侧依赖

**修改范围：** `SimTop.scala`、`Gateway.scala`、`DPIC.scala`、`Difftest.scala`、`Squash.scala`、`common/Mem.scala`，再删除 FPGA Scala 目录和仅有 FPGA 消费者的 AXI 类型。

**变化：** 清除 FPGA IO/reset/enable/Host/XDMA/MemCtrl、GSIM 生成分支及 `F/G` 配置项。与这些配置相关的输入改成显式报错。普通 DPI、默认 Preprocess/Validate 和 step 协议保持不变。

**命令：**

```bash
(cd difftest && mill -i design.compile)
bash difftest/scripts/verify-verilator.sh --build-dir "$PWD/target/difftest-cleanup-p2" --suite all
git -C difftest diff --check
rg -n 'difftest\.fpga|HostEndpoint|XDMAConfigBar|DifftestMemCtrl|GsimSim|gsim\.h' difftest/src
```

**通过条件：** Scala 全量编译通过；无悬空类型；生成的普通 DPI 参数及状态结构与基线相同；step 大于零并实际驱动参考模型；残留引用搜索无命中。

## Phase 3：Windrise 功能裁剪（独立后续批次）

**修改范围：** 传输优化、可选插件、矩阵检查、设备模型和相应构建参数。根据最终 Windrise 探针集合逐项选择，每个功能单独处理，不一次删整个 common/plugin/checkers。

**变化顺序：** 先收窄已实例化探针/功能配置，再删除全部生成和运行依赖，最后删除实现文件。`Query` 无条件参与 DPIC 头生成，`Preprocess` 默认参与状态转换，不能靠设置一个开关后直接删文件。

**配置陷阱：** 不用单独 `CONFIG=U` 作为最简 native emu 配置；当前实现可能绕过 Endpoint 而让 step 恒为零。首批保留默认配置，后续如使用软件架构寄存器重建，需要单独核验 `EU` 及 step 协议。

**命令：** 每裁掉一个功能都用独立产物目录完整重建：

```bash
(cd difftest && mill -i design.compile)
bash difftest/scripts/verify-verilator.sh --build-dir "$PWD/target/difftest-cleanup-p3" --suite all
git -C difftest diff --check
```

**通过条件：** 所选功能完全退出构建及运行参数；保留的检查覆盖未减少；故意破坏存储但不再读回的负例仍失败；没有失配后无依据地用 DUT 值同步 REF 的路径。

此阶段还应审计 `BASIC_DIFFTEST_ONLY` 对 PC 检查的影响，以及 LoadChecker 内部针对多核的 REF 同步例外。不能为了精简默认启用会减弱比较的选项。

## 后续 SpinalHDL/RV32 接入边界

当前 Chisel 源码不仅生成硬件探针，还生成 `difftest-state.h`、`difftest-dpic.h/cpp`、DUT wrapper 配置等 C++ 必需输入。`src/test/scala/DifftestTop.scala` 是非 Chisel 设计的接口生成入口，先用最小 profile 收窄探针集合。

待新的 SpinalHDL 或其他生成器能够完整产生同一协议，并通过上述正负例后，才退役 Chisel/Mill。Windrise 主仓库中恢复的旧版 Scala 实现不能未经 ABI 核对直接替代这里的生成器。

RV32 数据布局、NEMU ABI、异常语义及 Windrise 顶层总线适配另作移植提交。当前 RefProxy 默认仍是 RV64，清理成功不代表这些适配已经完成。

## 最终验收

```bash
bash difftest/scripts/verify-verilator.sh --build-dir "$PWD/target/difftest-cleanup-final" --suite all
git -C difftest diff --check
git -C difftest status --short
```

1. 构建图仅保留所选 native Verilator 入口，生成器到软件检查器的依赖完整。
2. 每轮从新目录构建，无残留生成文件掩盖缺失依赖。
3. 正例结果与基线一致，记录非零差分次数、退休数及最终状态。
4. 错误 PC/GPR/store 负例明确失败，复位、超时和失败退出码正确。
5. FST 波形和最近提交日志可用于定位问题。
6. 无其他仿真后端的工具依赖或活跃 CI 任务；被删除的选项明确报错。
7. 源代码清理与 RV32/SpinalHDL 语义迁移分批提交，主仓库在子模块提交后再更新指针。

工作流文档中的 Linux 长时测试针对香山完整系统。当前 Windrise 尚未接入且不具备该运行基线，此处采用与清理范围匹配的确定性正负例及已有 DUT 回归；只有存在可运行的全系统基线时才追加 Linux 长时回归。
