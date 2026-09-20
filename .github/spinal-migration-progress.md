# Scala / SpinalHDL 迁移进度

计划：[spinal-migration-plan.md](spinal-migration-plan.md)

| 阶段 | 状态 |
| --- | --- |
| 历史源码、主仓库 Spinal 与构建审计 | 完成 |
| Scala 与 Bundle 参数迁移 | 完成 |
| sbt / Makefile / 文档 / CI | 完成，远程 CI 未执行 |
| 编译、RV32/RV64 生成和 DPI 验证 | 通过，5 项测试 |

现有主仓库包含多项 CPU 修改，本次保持其工作区内容。子模块四份已暂存规划文档保持原暂存状态。

## 2026-09-20：完成 Scala 迁移

### 实际修改

- 将四份历史 Scala 源码迁为 Spinal；Bundle 定义拆入 `Bundles.scala`。
- 增加 `xlen`、`flen`、`vlen`、缓存行大小、寄存器数量和队列索引位宽参数；默认保持 RV64 数据宽度。
- DPI、C++ 状态及宏从同一组探针定义产生；生成状态限定在单次 elaboration，输出路径使用 Spinal targetDirectory。
- 保留 C++ 字段名和 DPI 函数名，修正标准整数类型映射、长度为 1 的数组以及无效 flattened 写回的索引访问。
- 复位期间保留 coreid/index 并清零数据，保证每个提交槽的 valid 清除；延迟寄存器具有复位初值。
- 新增 sbt 构建，移除 Mill 构建；Makefile、README 和 CI 改为 Spinal/sbt 入口。旧 Chisel 使用说明标明历史文档。
- 新增 ScalaTest 和 C++ 夹具，仅在测试中使用假的状态容器，测试实际生成的 SV/DPI 接收代码；没有把该容器用作 CPU 参考模型。

### 验证结果

- `sbt compile test`：5 项全部通过，最终测试耗时约 7.5 秒。
- 完整探针四次生成并通过 Verilator lint：RV32/FLEN64/VLEN256、RV64/FLEN64/VLEN256、重复 RV32、RV64/FLEN32/VLEN64；均使用 2 核、65 个物理寄存器。重复 RV32 的三个 C++ 输出文件逐字节一致。
- RV32/RV64 实际 Verilator 仿真通过：高位 PC/GPR 数据、异常号 0、17 位 ROB 索引、两个提交槽、65 元素写回数组、单元素数组、valid 清除、一拍延迟及活动状态下复位。
- 同时包含 Verilator 导出的 DPI 头与本生成器的 DPI 头进行 C++ 编译，签名一致；64 位 DPI 参数采用 `unsigned long long`，状态存储采用 `uint64_t`。
- 单/双端口 RAM helper 仿真通过：128 位 beat 拆为两个 64 位字、字节掩码、索引换算、复位禁止访问、读数据保持。
- 非法位宽及同名探针混合布局按预期拒绝。
- `sbt "runMain difftest.DifftestMain --xlen 64 --target-dir build/rv64"` 通过。
- `make difftest_verilog NOOP_HOME="$PWD" BUILD_DIR="$PWD/build/make-rv32" XLEN=32` 通过。
- 运行环境：Java 21、sbt 1.11.7、SpinalHDL 1.15.0、Verilator 5.020。日志位于忽略的 `build/test-final.log` 和 `build/make-rv32.log`，各夹具命令记录于 `target/migration-tests/*/commands.log`。

### 调试记录

- 首次 sbt 启动受只读缓存锁限制，使用获授权的执行环境读取/更新已有缓存后继续。
- 普通参数化 Bundle 不能自动克隆：改为 case class；派生延迟更新类的构造字段被 Scala 优化为父类访问器，需要显式 clone。
- trait 中直接定义硬件字段未被 Spinal Bundle 注册：改为标记/访问接口，在 Bundle 基类中实际声明 coreid/index/valid/address；RAM trait 端口显式命名。
- 整个向量状态拼接为复位常量超过默认信号宽度限制：改为逐字段清零。
- Verilator 不接受多词整数类型直接用于 cast：改为显式大小转换；接口参数仍声明 unsigned DPI 类型。
- Scala 2.13 的 immutable Seq 与 Spinal Vec 不兼容：内存 API 接受 collection.Seq。
- 首轮通过后的复核发现复位清零 index 会遗漏非零提交槽：保持槽位身份并加入活动后复位用例，最终测试通过。

### 边界

本次没有改主仓库旧探针实现、CPU 或 NEMU，没有提交或推送。历史软件检查器的 RV32 适配、PC/store 修正和 CPU 回归仍未完成；本次测试证明的是 Scala/RTL/DPI 生成链。
