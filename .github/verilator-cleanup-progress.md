# Verilator 后端清理进度

计划：[verilator-cleanup-plan.md](verilator-cleanup-plan.md)

| 阶段 | 状态 | 构建验证 | 差分正负例 |
| --- | --- | --- | --- |
| 文档和代码依赖审计 | 完成 | 未执行 | 未执行 |
| Phase 0：运行基线及夹具 | 待实施 | 未执行 | 未执行 |
| Phase 1：其他运行后端 | 待实施 | 未执行 | 未执行 |
| Phase 2：FPGA/GSIM 生成分支 | 待实施 | 未执行 | 未执行 |
| Phase 3：Windrise 功能裁剪 | 后续批次 | 未执行 | 未执行 |

## 2026-09-20：规划完成

### 实际修改

- 新增清理计划和本进度文档。
- 未删除或修改源代码、构建规则、CI 配置；未提交任何变更。

### 审计依据

- 当前 difftest 基线为 `f292230bb44b906215d110bd73e1211c34bb91ed`，本地分支 `windrise`。
- 阅读香山官方 DiffTest 文档，以及当前仓库的 Makefile、运行入口、生成接口和开发说明。
- 根据 `docs/workflow.md`，将硬件生成依赖和软件检查依赖分别委派只读审计，已合并结论。
- 确认 `emu.mk → verilator.mk` 的 native 运行链；`vcs.mk` 另有 `VCS=verilator` 路径。
- 确认 common/checkers/接口生成器不是其他后端专用代码；FPGA 目录删除前需处理 Scala 静态引用。

### 验证结果

- 本轮为静态规划，没有运行编译、仿真或测试。
- 已发现 Verilator、sbt；mill 未在 PATH 中发现。
- 计划中的 `scripts/verify-verilator.sh` 是 Phase 0 拟新增接口，目前不存在。

### 关键问题及处置

- 所选保留入口为 native emu；相应将 simv、FPGA_SIM 和其他宿主入口列入退役范围。
- CUDA/MMA 属于矩阵参考检查，不归入 RTL 仿真后端清理。
- 默认 Preprocess/Validate 和 difftest step 端口有实际作用，首批保持。
- 保留标量存储检查；后续检查 LoadChecker 的 REF 同步例外，避免将失配同步为通过。
- RV32、NEMU ABI 和 SpinalHDL 迁移列为独立后续工作，不能以清理完成替代其验收。
