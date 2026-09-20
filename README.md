# DiffTest for Windrise

本分支基于上游 `bf9586cb`，Scala 接口已迁移到 **SpinalHDL + sbt**。保留历史 DPI 函数名及 C++ 状态字段名，支持生成 RV32/RV64 探针。

## 编译和生成

需要 JDK 17 或更新版本、sbt；测试还需要 Verilator、C++ 编译器和 make。依赖版本与 Windrise 一致：Scala 2.13.18、SpinalHDL 1.15.0、sbt 1.11.7。

```bash
sbt compile
sbt test
sbt "runMain difftest.DifftestMain --xlen 32 --target-dir build/rv32"
sbt "runMain difftest.DifftestMain --xlen 64 --target-dir build/rv64"
```

输出包括 `DifftestTop.sv`（内含各个 DPI wrapper）和 `generated-src/{diffstate.h,difftest-dpic.h,difftest-dpic.cpp}`。示例顶层暴露各探针输入，用于生成和检查接口；它不是可运行软件的 CPU。

也可通过 Makefile 生成：

```bash
make difftest_verilog NOOP_HOME="$PWD" BUILD_DIR="$PWD/build/rv32" XLEN=32
```

支持 `--flen`、`--vlen`、`--num-phy-regs` 和 `--num-cores`，对应 Makefile 的 `FLEN`、`VLEN`、`NUM_PHY_REGS`、`NUM_CORES`。Scala 入口不依赖 `NOOP_HOME`；Makefile 仍使用它定位设计目录。

## 位宽参数

```scala
DiffInstrCommit(numPhyRegs = 64, xlen = 32)
DiffArchIntRegState(xlen = 32)
DiffCSRState(xlen = 64)
DiffFpWriteback(numElements = 64, flen = 64)
DiffArchVecRegState(vlen = 256)
DiffSbufferEvent(xlen = 32, lineBytes = 32)
```

| 参数 | 作用 |
| --- | --- |
| `xlen`，默认 64 | 32/64 位的整数数据、PC、地址和 CSR；指令编码仍为 32 位 |
| `flen`，默认 64 | 浮点寄存器和写回宽度，独立于整数 XLEN，可选 32/64 |
| `vlen`，默认 128 | 每个向量寄存器的位数；以 64 位分片存储，必须是至少 64 的 64 倍数 |
| `numPhyRegs` / `numElements` / `numRegs` | 物理寄存器数量或写回/延迟更新地址空间大小 |
| `robIdxBits` / `lqIdxBits` / `sqIdxBits` | 提交探针中的队列索引位数 |
| `lineBytes`，默认 64 | Sbuffer/Refill 的缓存行字节数，独立于 XLEN |

计数器和 runahead checkpoint ID 保持 64 位。Store/Atomic 的字节掩码为 `xlen / 8` 位。C++ 标量使用 8/16/32/64 位存储；例如 17 位索引会使用 `uint32_t`，不会产生无效的 `uint24_t`。

同一次生成中，同名探针必须使用相同参数。RV32、RV64 可以在同一 JVM 中分别生成到不同目录，生成状态不会跨设计累积。

## 集成到 Spinal 设计

在 CPU 项目的 sbt 构建中引入本地项目：

```scala
lazy val difftest = RootProject(file("difftest"))
lazy val cpu = (project in file(".")).dependsOn(difftest)
```

连接探针，并在顶层构造结束时输出对应 C++ 接口：

```scala
import spinal.core._
import difftest._

class ProbeExample(xlen: Int = 32) extends Component {
  val io = new Bundle {
    val event = in(DiffArchEvent(xlen))
    val commit = in(DiffInstrCommit(xlen = xlen))
    val trap = in(DiffTrapEvent(xlen))
    val regs = in(DiffArchIntRegState(xlen))
    val csr = in(DiffCSRState(xlen))
  }
  DifftestModule(DiffArchEvent(xlen)) := io.event
  DifftestModule(DiffInstrCommit(xlen = xlen)) := io.commit
  DifftestModule(DiffTrapEvent(xlen)) := io.trap
  DifftestModule(DiffArchIntRegState(xlen)) := io.regs
  DifftestModule(DiffCSRState(xlen)) := io.csr
  DifftestModule.finish("WINDRISE")
}

SpinalConfig(targetDirectory = "build").generateSystemVerilog(new ProbeExample(32))
```

每个核心实例化一个 `DiffArchEvent`，`coreid` 从 0 连续编号；带 index 的探针按核心内槽位编号。必须连接所有字段，未使用的控制位明确置零。默认输出路径为 Spinal 的 `targetDirectory/generated-src`，也可传入 `finish(..., outputDir = "...")`。

`DifftestModule(..., delay = 1)` 延迟整个数据包，coreid 保持当前核心编号。延迟寄存器复位为零；复位期间保留当前 coreid/index 并清零数据，以清除对应槽位的 valid。DPI 在每个时钟沿调用，使无事件周期也能清除 valid。`dontCare = true` 仅用于明确允许未定义输入的场景。

探针应接在与其状态采样时刻匹配的退休接口；编译 SV 时需定义 `DIFFTEST`。仿真顶层的 clock/reset/UART/log/perf 与历史 emu 的连接仍由 CPU 集成层负责。

## 内存 helper

`difftest.common.DifftestMem` 已迁移为 Spinal Component，提供单端口/双端口和 `readAndHold`。其 DPI 传输单位保持为 64 位字，与 CPU 的 XLEN 无关；beatBytes 必须是正的 8 倍数，byte mask 会扩展为位掩码。

```scala
import difftest.common._
val memory = DifftestMem(size = 1024 * 1024, beatBytes = 8)
when (writeEnable) {
  memory.write(index, bytes, byteMask)
}
val data = memory.readAndHold(index, readEnable)
```

index 是 beat 索引；内部转换为 C++ 的 64 位字索引。单端口不能同周期读写，复位期间禁止访问 RAM。

## 本次验证范围

`sbt test` 检查完整探针的 RV32/RV64 生成、重复生成隔离、位宽错误、Verilator DPI 原型与 C++ 接收端一致性，以及实际的高位数据传递、数组索引、valid 清除、延迟/复位和 RAM byte mask/read-hold 行为。

**RV32 接口生成完成不代表 CPU 差分接入完成。** 历史 C++ 检查器、NEMU RV32 ABI、PC/store 比较和 CPU 回归接入仍按[迁移计划](.github/verilator-cleanup-plan-v2.md)继续实施。主仓库 `src/main/scala/difftest` 中的旧实现本次没有替换。
