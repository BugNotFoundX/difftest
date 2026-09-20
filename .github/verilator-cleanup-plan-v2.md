# Windrise DiffTest：从历史提交迁移的规划（v2）

## 范围与结论

- 审计日期：2026-09-20。本版替代 v1 的“从最新代码向下裁剪”实施路线；保留 v1 供参考。
- 推荐迁移基线：`bf9586cb0429fc4284430df133dee83708a699f6`，2023-09-19，`Generate the C++ header with only one selected style (#163)`。
- 它是 `8aa92753`（首次引入 Batch）的直接父提交，仍有完整原生 Verilator emu 和按探针生成的 DPI/C++ 状态接口。
- 目标配置：Windrise、SpinalHDL、单核 RV32IM、原生 Verilator、NEMU 参考模型；提交宽度按 CPU 的实际退休接口定义。
- 历史选择和迁移规划已完成；迁移分支现已按用户要求重命名为 `windrise`，HEAD 为上述基线。后续 difftest 修改统一在该分支实施，尚未开始源码清理或运行仿真。
- 后续范围包括子模块、主仓库 `src/main/scala/difftest/`、仿真顶层和测试入口，以及必要的 NEMU RV32 接口适配。CPU 执行语义的修复单独记录。
- 保留许可证、版权说明及开发文档。实施时按阶段提交，最终再更新主仓库的子模块指针。

## 为什么选择这个节点

以下统计来自远程 master 历史中的 Git 树，不包含本地新增规划文档。文件数反映代码规模，不代表实测迁移工时。

| 节点 | 跟踪文件 | Scala 源文件 | 接口与取舍 |
| --- | ---: | ---: | --- |
| `f905bc9f`，2023-09-08 | 64 | 2 | 自动生成重构之前；C++ 接收接口手写，但状态固定为 RV64 和香山宽度，异常事件没有独立 valid |
| `4c1b283b`，2023-09-08 | 65 | 3 | 第一次统一自动生成接口的大重构；不选尚未包含后续修正的过渡节点 |
| **`bf9586cb`，2023-09-19** | **71** | **4** | 已包含接口后续修正、整数 writeback 修正和 Verilator 5 宏；还没有 Batch/Gateway/Squash/Replay/Delta |
| 当前 `f292230b`，2026-09-20 | 224 | 36 | 平台、传输优化及检查扩展较多，需要拆除大量依赖 |

`bf9586cb` 的四个 Scala 文件是 `Difftest.scala`、`DPIC.scala`、`DifftestMain.scala`、`common/Mem.scala`。其中接口生成逻辑能够按实例化的探针产生 `diffstate.h`、`difftest-dpic.h/cpp` 和 `CONFIG_DIFF_*`，可以迁入现有 Spinal 生成器，不必恢复一套固定的手写状态布局。

历史演变的关键边界：

```text
2023-09-08 f905bc9f：手写 C++ 接收接口
2023-09-08 4c1b283b：统一生成 DPI 和状态结构
2023-09-19 bf9586cb：本次推荐基线
2023-09-20 8aa92753：首次引入 Batch
2023-10：合并/Squash、step 控制、状态缓冲逐步加入
2023-11-06 4d31d02d：启用 Batch，引入 Gateway
后续：Replay、FPGA、GSIM、Delta 等继续扩展
```

相关远程提交：[推荐基线](https://github.com/BugNotFoundX/difftest/commit/bf9586cb0429fc4284430df133dee83708a699f6)、[更早的手写接口基线](https://github.com/BugNotFoundX/difftest/commit/f905bc9fc34e5d6bc52c919d251f80378e7a1755)、[接口重构](https://github.com/BugNotFoundX/difftest/commit/4c1b283b420f09428caed537f16a06af53526ba9)。提交及父子关系以本次从用户远程仓库取得的 Git 对象核实。

选择 `f905bc9f` 的理由只能是明确决定维护手写的小型 ABI。当前主仓库已经有 Spinal DPI 生成器，继续采用生成接口预计改动更集中，因此推荐 `bf9586cb`。这是静态审计结论，尚未证明该历史版本在当前环境能直接构建运行。

## 推荐基线中必须处理的缺口

### 1. 恢复显式 PC 比较

`bf9586cb` 的 `difftest.cpp:195` 调用 `proxy->compare()`，而 `refproxy.cpp:151–181` 不比较 PC。必须参考后续提交 [`790756c4`](https://github.com/BugNotFoundX/difftest/commit/790756c4017eae996ee3ecc8fa1d66eb2fb97a94) 的语义，在执行参考指令前比较退休组首 PC；单条退休时就是每条指令的 PC。

该补丁出现时已有其他结构变化，应移植必要逻辑及诊断，不能假定直接 cherry-pick 就适用。首期不启用会绕开 PC 比较的 `BASIC_DIFFTEST_ONLY`。

### 2. 对齐 RV32 与 NEMU ABI

旧接口默认 PC/GPR 为 64 位，RefProxy 内还有无条件的 CSR 状态和必需符号，不能只修改 `XLEN` 或替换共享库路径。

当前 NEMU 基线为 `a0105e46997f564e72951ebd18fc999a478bf4f8`：普通配置的 `difftest_regcpy` 是两个参数，历史候选声明为三个参数；RV32 的寄存器状态是 32 位 GPR/PC，不能直接复制到候选 RefProxy 的完整 RV64 状态。CSR、store 和部分微架构接口还受 `RV64_FULL_DIFF` 条件限制。

迁移时定义明确的 RV32 REF 状态和转换边界，核对大小、偏移、函数签名、方向及必需符号。借鉴 NEMU 已有的 `difftest_init_v2(state_size)` 做布局大小检查；同尺寸也仍需核对字段顺序。未实现的 ISA 扩展不能通过伪造状态或恒成功函数蒙混过关。

### 3. 保持 store 检查及正确时序

候选的 `difftest.cpp:107` 在执行本周期 REF 指令前检查 store，隐含 store 事件滞后的假设。若 Windrise 同周期退休并提交 store，需要采用 [`6d1452f6`](https://github.com/BugNotFoundX/difftest/commit/6d1452f69cb717d879c2a2e0e095f2a8d34716e3) 的思路：先记录事件，推进 REF，再匹配地址、数据、字节掩码。

RV32 NEMU 需提供实际的 store 校验能力。错误写入后永不读回的程序也必须失败，不能仅依赖 GPR 比较间接发现内存错误。

### 4. 明确探针周期和测试退出条件

- 本地生成代码使用 `getArchEvent()` 等 getter，候选直接访问 `difftest[coreid]->dut.event`；两者不能原样连接。
- 对齐 commit 的 `index`、`nFused`、字段顺序，以及 TrapEvent 位宽、GPR 数组表示。首期不使用融合、延迟写回等特性。
- 候选 ArchEvent 已有 valid，能够表示异常号 0；仍须对齐 CPU 当前的异常停机行为和已实现的 CSR 能力，不能宣称尚未支持的异常处理已经通过差分。
- 对齐每周期 valid 更新/清除、复位门控，以及首条指令执行前后的状态采样。仅在有效时调用 DPI 不能导致旧事件残留。
- `verilator/emu.h:148` 将 `STATE_LIMIT_EXCEEDED` 也算成功。CPU 回归模式必须区分正常结束、差分失败、超时和执行上限。

## 前置条件与验证方式

历史审计使用独立 bare clone：`/tmp/windrise-difftest-history.4KKuES/repo.git`。当前子模块位于 `windrise` 分支的 `bf9586cb`；旧 `windrise` 分支已删除，`f292230b` 仍由 `master` 保留。切换及重命名时四份规划/进度文档及其暂存内容均已保留，主仓库 gitlink 尚未暂存或提交。

本机 Verilator 为 `5.020`；候选已包含 `ac2a79b3`（支持 5.016）和 `243890be`（Verilator 5 宏）。这只证明已有相应兼容处理，实际编译仍需验证。已发现 sbt，未发现 PATH 中的 mill；优先复用 Spinal 生成链。

下面各阶段的 `scripts/verify-verilator.sh` **是待实现的验证入口，本轮没有创建，当前不可直接运行**。未来在迁移分支根目录执行，统一接受 `--suite smoke|abi|all|cpu` 和独立 `--build-dir`，实际生成/编译/执行命令须由该脚本及日志完整记录，禁止将跳过检查计为通过。

## Phase 0：固定历史基线（已完成）

**变化：** 从所选历史节点建立独立迁移分支/工作目录，保留当前工作区及暂存内容；迁移完成前不改主仓库 gitlink。先保留历史原状，再分别提交清理和功能适配，便于审查。

**已使用的只读核验命令：**

```bash
task_history=/tmp/windrise-difftest-history.4KKuES/repo.git
git --git-dir="$task_history" show -s --format=fuller bf9586cb
git --git-dir="$task_history" rev-parse 8aa92753^
git --git-dir="$task_history" merge-base --is-ancestor bf9586cb f292230b
git --git-dir="$task_history" ls-tree -r --name-only bf9586cb
```

**通过条件：** 完整 SHA、父子关系及树内容匹配；新分支源于所选提交，原分支和本地修改可追溯。此阶段不算运行验证。

## Phase 1：收窄到原生 Verilator

**修改范围：** 历史版本的 `Makefile`、`verilator.mk`、`vcs.mk`、`src/test/csrc/vcs/`、`src/test/vsrc/vcs/` 和相关 CI；新增验证入口与最小仿真夹具。

**变化：** 去掉 VCS 目标、include、clean 依赖及专用文件；保留 common/difftest/verilator。该基线没有 GSIM/FPGA/UVS 等目录，无需再做 v1 中的复杂平台解耦。先关闭 runahead、coverage/fuzzing、ChiselDB、Constantin 等可选功能，暂不删除共享支持。对齐 SimTop 的时钟、复位、UART/log/perf 端口及内存入口。

**拟实施后的验证命令：**

```bash
bash scripts/verify-verilator.sh --build-dir "$PWD/build/check-p1" --suite smoke
git diff --check
```

**通过条件：** 从空目录编译、启动、复位，能够生成波形；不再探测 VCS 工具。此时若只做无差分冒烟，必须标明不能据此判断 CPU 正确性。

## Phase 2：接上 Spinal 与 RV32 REF

**修改范围：** Windrise 探针/生成器、仿真顶层；子模块 `refproxy.*`、必要的状态使用方、构建输入；NEMU RV32 导出与 store 记录适配。

**变化：** 由一个探针定义来源生成匹配的 SV 和 C++ 接口，输出完整 `DiffTestState`、所需宏和 DPI 函数。生成器支持明确的输出目录，修掉历史生成器写死 `NOOP_HOME/build/generated-src` 的路径，避免旧产物混入验证。RV32 REF 状态采用明确布局；首期不实例化 FPR、向量、多核、融合等扩展。

只有替代生成器能完整产生必需输入之后，才移除旧 Chisel/Mill 构建链。未实现 CSR/异常能力的测试范围必须明确，不允许靠取消检查宣称通过。

**拟实施后的验证命令：**

```bash
bash scripts/verify-verilator.sh --build-dir "$PWD/build/check-p2" --suite abi
git diff --check
```

**通过条件：** 字段位宽、顺序、数组索引、符号签名和状态大小一致；无提交周期不重复消费事件；最小正常程序实际推进 REF 且比较次数非零。

## Phase 3：补齐历史检查缺口

**修改范围：** `difftest.cpp/.h`、`refproxy.*`、emu 退出条件、定向测试夹具。

**变化：** 补 PC 比较，按 CPU 接口解决 store 时序，明确超时失败，以及复位/事件清除和首条提交时序。只移植实际需要的后续修正，不把 Batch/Gateway 等重新引入。

**拟实施后的验证命令：**

```bash
bash scripts/verify-verilator.sh --build-dir "$PWD/build/check-p3" --suite all
git diff --check
```

**通过条件：** 正例正常结束；独立注入错误 PC、GPR、store 地址/数据/掩码均能检测；没有读回的坏 store 仍被检测；无限循环和执行上限不算成功。比较不能通过用 DUT 错误状态覆盖 REF 来消除差异。

## Phase 4：迁移 CPU 回归并完成剩余裁剪

**变化：** CPU 程序的架构结果统一由 DiffTest/NEMU 判断；保留现有测试中关于阻塞、冲刷和异常停机等时序性质的断言。根据实际依赖再删除不使用的插件、设备和特性，最后更新 README、CI 和子模块指针。

**拟实施后的验证命令：**

```bash
bash scripts/verify-verilator.sh --build-dir "$PWD/build/check-final" --suite all
bash scripts/verify-verilator.sh --build-dir "$PWD/build/check-cpu" --suite cpu
git diff --check
```

**最终通过条件：** 空目录完整构建、现有 CPU 回归通过、差分正负例通过，记录每例退休数、比较次数和退出原因；保留波形与最近提交诊断。当前不具备 Linux 全系统运行基线，不把 Linux 长时测试列为这次迁移的前置条件。
