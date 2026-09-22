package difftest

/** Architecture and reference protocol are independent of the CPU/platform name. */
case class DifftestTarget(isa: String, xlen: Int, flen: Int,
                          paddrBits: Int, vaddrBits: Int, refAbi: String) {
  require(Set("riscv32", "riscv64", "la32r").contains(isa), s"Unknown ISA: $isa")
  require(xlen == (if (isa == "riscv64") 64 else 32), "ISA/XLEN mismatch")
  require(Set(0, 32, 64).contains(flen), "FLEN must be 0, 32 or 64")
  require(paddrBits > 0 && paddrBits <= 64 && vaddrBits > 0 && vaddrBits <= xlen)
  require(Set("legacy-rv64", "nemu-rv32-v2", "nemu-rv64-v2", "none").contains(refAbi))
  require(refAbi != "legacy-rv64" || (isa == "riscv64" && flen != 32))
  require(refAbi != "nemu-rv32-v2" || (isa == "riscv32" && flen == 0))
  require(refAbi != "nemu-rv64-v2" || (isa == "riscv64" && flen == 0))

  /** 检查已登记的探针与本目标是否自洽。规则：
    *
    * | 探针 | 要求 |
    * | --- | --- |
    * | `DiffArchIntRegState`／`DiffInstrCommit`／`DiffArchEvent`／`DiffTrapEvent`／`DiffIntWriteback` | XLEN 一致 |
    * | `DiffCSRState` | RISC-V 且 XLEN 一致 |
    * | `DiffArchFpRegState`／`DiffFpWriteback` | FLEN 一致，且 FLEN 不为 0 时必须有 FP 寄存器快照 |
    * | `DiffStoreEvent` | 地址位宽等于 `paddrBits` |
    * | `nemu-rv32/64-v2` | 只支持 GPR/PC 类架构探针，禁止 CSR、向量与 LR/SC |
    * | `legacy-rv64` | 必须带 CSR 块 |
    */
  private[difftest] def validate(probes: Seq[DifftestBundle]): Unit = {
    probes.foreach {
      case p: DiffArchIntRegState => require(p.xlen == xlen, "GPR/XLEN mismatch")
      case p: DiffInstrCommit => require(p.xlen == xlen, "commit PC/XLEN mismatch")
      case p: DiffArchEvent => require(p.xlen == xlen, "exception PC/XLEN mismatch")
      case p: DiffTrapEvent => require(p.xlen == xlen, "trap PC/XLEN mismatch")
      case p: DiffCSRState => require(isa.startsWith("riscv") && p.xlen == xlen, "RISC-V CSR/ISA mismatch")
      case p: DiffArchFpRegState => require(p.flen == flen, "FPR/FLEN mismatch")
      case p: DiffFpWriteback => require(p.flen == flen, "FP writeback/FLEN mismatch")
      case p: DiffIntWriteback => require(p.xlen == xlen, "integer writeback/XLEN mismatch")
      case p: DiffStoreEvent => require(p.addr.getBitsWidth == paddrBits, "store address/PADDR mismatch")
      case _ =>
    }
    require(probes.exists(_.isInstanceOf[DiffArchFpRegState]) == (flen != 0), "FLEN/probe capability mismatch")
    if (Set("nemu-rv32-v2", "nemu-rv64-v2").contains(refAbi)) {
      require(!probes.exists(p => p.isInstanceOf[DiffCSRState] ||
        p.isInstanceOf[DiffArchVecRegState] || p.isInstanceOf[DiffVecCSRState] ||
        p.isInstanceOf[DiffLrScEvent]), s"$refAbi supports only GPR/PC architectural probes")
    }
    if (refAbi == "legacy-rv64") require(probes.exists(_.isInstanceOf[DiffCSRState]),
      "legacy-rv64 requires the legacy CSR block")
  }

  /** 写进 `diffstate.h` 的平台宏，同时参与 schema 指纹的计算。 */
  private[difftest] def macros: String = Seq(
    "#define DIFFTEST_SCHEMA_VERSION 1",
    s"#define DIFFTEST_ISA_${isa.toUpperCase} 1",
    s"#define DIFFTEST_ISA_NAME \"$isa\"",
    s"#define DIFFTEST_XLEN $xlen", s"#define DIFFTEST_FLEN $flen",
    s"#define DIFFTEST_PADDR_BITS $paddrBits", s"#define DIFFTEST_VADDR_BITS $vaddrBits",
    s"#define DIFFTEST_REF_ABI_${refAbi.toUpperCase.replace('-', '_')} 1",
    s"#define DIFFTEST_REF_ABI_NAME \"$refAbi\""
  ).mkString("\n")
}

object DifftestTarget {
  /** 本仓库 micro 流程使用：RV32IM、无浮点、NEMU v2 REF。 */
  val MicroRV32: DifftestTarget = DifftestTarget("riscv32", 32, 0, 32, 32, "nemu-rv32-v2")
  val MicroRV64: DifftestTarget = DifftestTarget("riscv64", 64, 0, 32, 64, "nemu-rv64-v2")

  def micro(xlen: Int): DifftestTarget = xlen match {
    case 32 => MicroRV32
    case 64 => MicroRV64
    case _ => throw new IllegalArgumentException("micro DiffTest requires XLEN 32 or 64")
  }
  def legacyRV64(flen: Int = 64): DifftestTarget =
    DifftestTarget("riscv64", 64, flen, 64, 64, "legacy-rv64")
}
