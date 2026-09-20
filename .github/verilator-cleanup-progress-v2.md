# 历史基线迁移进度（v2）

计划：[verilator-cleanup-plan-v2.md](verilator-cleanup-plan-v2.md)

| 阶段 | 状态 | 构建/仿真 |
| --- | --- | --- |
| 远程历史、接口和软件检查审计 | 完成 | 静态分析，无仿真 |
| Phase 0：迁移分支建立 | 已完成，现名 `windrise`，位于 `bf9586cb` | Git 状态核验通过，无仿真 |
| Phase 1：Verilator 入口收窄 | 待实施 | 未执行 |
| Phase 2：Spinal/RV32 接口 | Scala 生成链完成，REF 适配待实施 | Scala/DPI 5 项测试通过；CPU 差分未执行 |
| Phase 3：检查语义修正 | 待实施 | 未执行 |
| Phase 4：CPU 回归及剩余裁剪 | 待实施 | 未执行 |

## 2026-09-20：改为从历史节点迁移

### 实际修改

- 新增本版计划与进度，保留 v1 文档及其暂存状态。
- 重新从 `git@github.com:BugNotFoundX/difftest.git` 获取 master 可达历史到独立 bare clone，没有复用已被替换的旧仓库缓存。
- 没有修改或切换子模块源码，没有更新主仓库 gitlink，没有创建迁移分支或提交。

### 审计及核验结果

- 推荐 `bf9586cb0429fc4284430df133dee83708a699f6`，核实是首次 Batch 提交 `8aa92753` 的直接父提交，也是当前 `f292230b` 的祖先。
- Git 树统计：推荐节点 71 个跟踪文件、4 个 Scala 文件；当前节点 224/36；更早的 `f905bc9f` 为 64/2。
- 核实推荐节点包含 `ac2a79b3` 和 `243890be` 两项 Verilator 兼容修改。本机为 Verilator 5.020；没有执行历史版本构建。
- 按 `docs/workflow.md` 对大型代码审计的要求，分别核对历史硬件接口和软件检查链。综合现有 Spinal 生成器后选择生成接口已收敛、但尚未引入 Batch 的节点。
- 原暂存区仍为 v1 的两份文档；新增 v2 文档没有暂存。
- GitHub 网页抓取未成功；提交内容、父子关系和代码统计以成功取得的 Git 对象为依据。

### 已发现问题及计划处置

- 推荐节点缺少显式 PC 比较：定向移植 `790756c4` 的逻辑，必须通过错误 PC 负例。
- RV32/NEMU ABI 不匹配：独立适配，普通 regcpy 使用实际两参数协议，核对状态布局及必需符号。
- store 检查早于 REF 执行：按 CPU 事件时序采用队列匹配；RV32 参考模型不能用恒成功实现替代校验。
- 执行上限被当成成功：修正 CPU 回归退出条件。
- 本地 Spinal 与历史 DPI 的字段、访问方式和 valid 周期不同：统一生成并验证事件消费时序。
- 更早手写接口节点虽然文件更少，但固定 RV64 状态和缺失异常 valid 会增加改造；作为选择静态手写 ABI 时的备选。

### 验证边界

- 本轮只读核验历史和代码，未运行编译、CPU 测试或差分正负例。
- `scripts/verify-verilator.sh` 仍为计划中的新入口，没有创建。
- 尚不能声称历史节点与当前 Verilator/NEMU 已经兼容；运行证据留待实施阶段获得。

## 2026-09-20：按用户要求切换迁移分支

### 实际修改

- 从已核验的独立历史仓库导入 `bf9586cb0429fc4284430df133dee83708a699f6`，创建并切换到 `windrise-verilator`。
- 后续 difftest 修改统一在 `windrise-verilator` 分支进行。原 `windrise`、`master` 分支保留在 `f292230b`。
- 原有四份暂存文档完整带到新分支；本次计划/进度更新留在工作区，没有重新暂存。
- 没有创建提交、推送远程或暂存主仓库的子模块指针。

### 核验结果

- 当前分支为 `windrise-verilator`，HEAD 与推荐基线的完整 SHA 一致。
- 切换前后的四份暂存文档 blob ID 一致，源码无额外本地差异。
- 主仓库已有的 `src/main/scala/micro/CPU.scala` 修改未触碰，切换前后文件 hash 一致。
- 本次仅切换 Git 基线并更新记录，没有运行构建或仿真；Phase 1–4 仍待实施。

## 2026-09-20：按用户要求调整分支名称

- 删除原先位于 `f292230b` 的本地 `windrise` 分支；该提交仍由 `master` 保留。
- 将当前 `windrise-verilator` 重命名为 `windrise`，基线仍为 `bf9586cb0429fc4284430df133dee83708a699f6`。
- 后续 difftest 修改统一在新 `windrise` 分支进行；现有暂存内容保留，没有提交或推送。

## 2026-09-20：按用户要求优先迁移 Scala

Scala 已从 Chisel/Mill 迁到 SpinalHDL/sbt，Bundle 支持 RV32/RV64 和独立的浮点、向量位宽。完整探针生成、实际 Verilator DPI 传输及 RAM helper 测试共 5 项通过，Makefile 生成入口通过。详见 [Scala 迁移进度](spinal-migration-progress.md)。

此结果只覆盖 Phase 2 的 Scala/生成接口部分；软件检查器、NEMU RV32 ABI 和 CPU 集成仍未实施，其他阶段的状态保持不变。
