/***************************************************************************************
* Copyright (c) 2020-2023 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* DiffTest is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package difftest

import spinal.core._

/** 探针定义。
  *
  * 每个 case class 是一种 DiffTest 探针。**命名即契约**，改名字必须同步改
  * `src/test/csrc/` 下的 C++：
  *   - 类名决定生成的 Verilog 模块名与 `CONFIG_DIFFTEST_*` 宏（`Diff<X>` → `Difftest<X>`，
  *     见 [[DifftestBundle.desiredModuleName]]）；
  *   - 字段名决定 Verilog 端口名 `io_<字段名>` 与 C++ 结构体成员名；
  *   - [[DifftestBundle.desiredCppName]] 决定 `DiffTestState` 里的成员名；
  *   - [[DifftestBundle.desiredOffset]] 决定成员顺序，属 ABI。
  *
  * `desiredOffset` 的取值：`regs_int`=0、`csr`=1、`regs_fp`=2、`regs_vec`=4、`vcsr`=5，
  * 其余默认 999（排在最后）。顺序变了，已有的 DiffTrace 就不能再回放。
  *
  * 参数的默认位宽对应各 CPU 的典型配置。本仓库（micro 流程）只用
  * [[DiffInstrCommit]]、[[DiffArchIntRegState]]、[[DiffStoreEvent]]、[[DiffArchEvent]]
  * 与 [[DiffTrapEvent]]；其余供其他 CPU 模型使用，不是死代码。
  */

/** 架构异常探针：每核恰好实例化一个，核数由它数出（见 [[DifftestBundle.isUniqueIdentifier]]）。 */
case class DiffArchEvent(val xlen: Int = 64) extends DifftestBundle
  with DifftestWithValid
{
  Widths.checkXlen(xlen)
  val interrupt     = UInt(32 bits)
  val exception     = UInt(32 bits)
  val exceptionPC   = UInt(xlen bits)
  val exceptionInst = UInt(32 bits)

  // DiffArchEvent must be instantiated once for each core.
  override def isUniqueIdentifier: Boolean = true
  override val desiredCppName: String = "event"
}

/** 指令提交探针。
  *
  * `numPhyRegs` 决定物理目的寄存器号 `wpdest` 的位宽；`robIdxBits`/`lqIdxBits`/`sqIdxBits`
  * 必须分别容得下 ROB 与访存队列的索引。`wdest` 是 8 位架构寄存器号。
  */
case class DiffInstrCommit(val numPhyRegs: Int = 32, val xlen: Int = 64,
                      val robIdxBits: Int = 10, val lqIdxBits: Int = 7, val sqIdxBits: Int = 7) extends DifftestBundle
  with DifftestWithIndex
  with DifftestWithValid
{
  Widths.checkXlen(xlen)
  require(numPhyRegs >= 2)
  Seq(robIdxBits, lqIdxBits, sqIdxBits).foreach(Widths.storageBits)
  val skip     = Bool()
  val isRVC    = Bool()
  val rfwen    = Bool()
  val fpwen    = Bool()
  val vecwen   = Bool()
  val wpdest   = UInt(log2Up(numPhyRegs) bits)
  val wdest    = UInt(8 bits)

  val pc       = UInt(xlen bits)
  val instr    = UInt(32 bits)
  val robIdx   = UInt(robIdxBits bits)
  val lqIdx    = UInt(lqIdxBits bits)
  val sqIdx    = UInt(sqIdxBits bits)
  val isLoad   = Bool()
  val isStore  = Bool()
  val nFused   = UInt(8 bits)
  val special  = UInt(8 bits)

  def setSpecial(
    isDelayedWb: Bool = False,
    isExit: Bool = False,
  ): Unit = {
    special := Cat(isExit, isDelayedWb).asUInt.resize(8)
  }
  override val desiredCppName: String = "commit"
}

/** 退出/停机探针：报告是否停机、退出码以及周期与指令计数。 */
case class DiffTrapEvent(val xlen: Int = 64) extends DifftestBundle {
  Widths.checkXlen(xlen)
  val hasTrap  = Bool()
  val cycleCnt = UInt(64 bits)
  val instrCnt = UInt(64 bits)
  val hasWFI   = Bool()

  val code     = UInt(3 bits)
  val pc       = UInt(xlen bits)

  override val desiredCppName: String = "trap"
}

case class DiffCSRState(val xlen: Int = 64) extends DifftestBundle {
  Widths.checkXlen(xlen)
  val priviledgeMode = UInt(xlen bits)
  val mstatus = UInt(xlen bits)
  val sstatus = UInt(xlen bits)
  val mepc = UInt(xlen bits)
  val sepc = UInt(xlen bits)
  val mtval = UInt(xlen bits)
  val stval = UInt(xlen bits)
  val mtvec = UInt(xlen bits)
  val stvec = UInt(xlen bits)
  val mcause = UInt(xlen bits)
  val scause = UInt(xlen bits)
  val satp = UInt(xlen bits)
  val mip = UInt(xlen bits)
  val mie = UInt(xlen bits)
  val mscratch = UInt(xlen bits)
  val sscratch = UInt(xlen bits)
  val mideleg = UInt(xlen bits)
  val medeleg = UInt(xlen bits)
  override val desiredCppName: String = "csr"
  override val desiredOffset: Int = 1
}

/** Floating-point rounding mode and accrued exceptions in the NEMU RV64GC ABI.
  *
  * The low eight bits contain `frm` and `fflags`; the remaining bits are zero.
  * Keeping this separate preserves the existing eighteen-word CSR interface.
  */
case class DiffFCSRState() extends DifftestBundle {
  val value = UInt(64 bits)
  override val desiredCppName: String = "fcsr"
}

case class DiffDebugMode(val xlen: Int = 64) extends DifftestBundle {
  Widths.checkXlen(xlen)
  val debugMode = Bool()
  val dcsr = UInt(xlen bits)
  val dpc = UInt(xlen bits)
  val dscratch0 = UInt(xlen bits)
  val dscratch1 = UInt(xlen bits)
  override val desiredCppName: String = "dmregs"
}

case class DiffIntWriteback(val numElements: Int = 32, val xlen: Int = 64) extends DifftestBundle(numElements)
  with DifftestWithValid
  with DifftestWithAddress
{
  Widths.checkXlen(xlen)
  val data  = UInt(xlen bits)
  override val desiredCppName: String = "wb_int"
  override val needFlatten: Boolean = true
}

case class DiffFpWriteback(val numElements: Int = 32, val flen: Int = 64) extends DifftestBundle(numElements)
  with DifftestWithValid with DifftestWithAddress {
  Widths.checkXlen(flen)
  val data = UInt(flen bits)
  override val desiredCppName: String = "wb_fp"
  override val needFlatten: Boolean = true
}

/** 32 个整数架构寄存器快照，`value(i)` 对应 x<i>；每个核一个。 */
case class DiffArchIntRegState(val xlen: Int = 64) extends DifftestBundle {
  Widths.checkXlen(xlen)
  val value = Vec(UInt(xlen bits), 32)
  override val desiredCppName: String = "regs_int"
  override val desiredOffset: Int = 0
}

/** 延迟更新的架构状态（每核 `numElements` 槽）：`data` 是值，`nack` 表示该槽未确认。 */
abstract class DiffArchDelayedUpdate(val numElements: Int, val dataBits: Int) extends DifftestBundle(numElements)
  with DifftestWithIndex
  with DifftestWithValid
  with DifftestWithAddress
{
  Widths.storageBits(dataBits)
  val data = UInt(dataBits bits)
  val nack = Bool()
}

// 派生探针必须显式 clone：Spinal 按运行期类型复制 Bundle，基类的实现会丢掉参数。
case class DiffArchIntDelayedUpdate(val xlen: Int = 64, val numRegs: Int = 32)
  extends DiffArchDelayedUpdate(numRegs, xlen) {
  override val desiredCppName: String = "regs_int_delayed"
  Widths.checkXlen(xlen)
  override def clone: this.type = DiffArchIntDelayedUpdate(xlen, numRegs).asInstanceOf[this.type]
}

case class DiffArchFpDelayedUpdate(val flen: Int = 64, val numRegs: Int = 32)
  extends DiffArchDelayedUpdate(numRegs, flen) {
  override val desiredCppName: String = "regs_fp_delayed"
  Widths.checkXlen(flen)
  override def clone: this.type = DiffArchFpDelayedUpdate(flen, numRegs).asInstanceOf[this.type]
}

case class DiffArchFpRegState(val flen: Int = 64) extends DifftestBundle {
  Widths.checkXlen(flen)
  val value = Vec(UInt(flen bits), 32)
  override val desiredCppName: String = "regs_fp"
  override val desiredOffset: Int = 2
}

case class DiffArchVecRegState(val vlen: Int = 128) extends DifftestBundle {
  require(vlen >= 64 && vlen % 64 == 0, "vlen must be a positive multiple of 64")
  val value = Vec(UInt(64 bits), 32 * vlen / 64)
  override val desiredCppName: String = "regs_vec"
  override val desiredOffset: Int = 4
}

case class DiffVecCSRState(val xlen: Int = 64) extends DifftestBundle {
  Widths.checkXlen(xlen)
  val vstart = UInt(xlen bits)
  val vxsat  = UInt(xlen bits)
  val vxrm   = UInt(xlen bits)
  val vcsr   = UInt(xlen bits)
  val vl     = UInt(xlen bits)
  val vtype  = UInt(xlen bits)
  val vlenb  = UInt(xlen bits)
  override val desiredCppName: String = "vcsr"
  override val desiredOffset: Int = 5
}

case class DiffSbufferEvent(val xlen: Int = 64, val lineBytes: Int = 64) extends DifftestBundle
  with DifftestWithIndex
  with DifftestWithValid
{
  Widths.checkXlen(xlen)
  require(lineBytes > 0 && lineBytes <= 64, "DPI mask supports up to 64 bytes")
  val addr = UInt(xlen bits)
  val data = Vec(UInt(8 bits), lineBytes)
  val mask = UInt(lineBytes bits)
  override val desiredCppName: String = "sbuffer"
}

/** 已退休的 store：地址、数据与按字节展开的掩码。
  *
  * `paddrBits`/`dataBits` 传 0 表示沿用 `xlen`；`dataBits` 只支持 32 或 64，
  * 掩码宽度随之是 4 或 8 位。
  */
case class DiffStoreEvent(val xlen: Int = 64, val paddrBits: Int = 0,
                          val dataBits: Int = 0) extends DifftestBundle
  with DifftestWithIndex
  with DifftestWithValid
{
  Widths.checkXlen(xlen)
  private val addressWidth = if (paddrBits == 0) xlen else paddrBits
  private val dataWidth = if (dataBits == 0) xlen else dataBits
  Widths.storageBits(addressWidth)
  require(Set(32, 64).contains(dataWidth))
  val addr   = UInt(addressWidth bits)
  val data   = UInt(dataWidth bits)
  val mask   = UInt((dataWidth / 8) bits)
  override val desiredCppName: String = "store"
}

case class DiffLoadEvent(val xlen: Int = 64) extends DifftestBundle
  with DifftestWithIndex
  with DifftestWithValid
{
  Widths.checkXlen(xlen)
  val paddr  = UInt(xlen bits)
  val opType = UInt(8 bits)
  val fuType = UInt(8 bits)
  override val desiredCppName: String = "load"
}

case class DiffAtomicEvent(val xlen: Int = 64) extends DifftestBundle
  with DifftestWithValid
{
  Widths.checkXlen(xlen)
  val addr = UInt(xlen bits)
  val data = UInt(xlen bits)
  val mask = UInt((xlen / 8) bits)
  val fuop = UInt(8 bits)
  val out  = UInt(xlen bits)
  override val desiredCppName: String = "atomic"
}

case class DiffL1TLBEvent(val xlen: Int = 64) extends DifftestBundle
  with DifftestWithValid
  with DifftestWithIndex
{
  Widths.checkXlen(xlen)
  val satp = UInt(xlen bits)
  val vpn = UInt(xlen bits)
  val ppn = UInt(xlen bits)
  override val desiredCppName: String = "l1tlb"
}

case class DiffL2TLBEvent(val xlen: Int = 64) extends DifftestBundle
  with DifftestWithValid
  with DifftestWithIndex
{
  Widths.checkXlen(xlen)
  val valididx = Vec(Bool(), 8)
  val satp = UInt(xlen bits)
  val vpn = UInt(xlen bits)
  val ppn = Vec(UInt(xlen bits), 8)
  val perm = UInt(8 bits)
  val level = UInt(8 bits)
  val pf = Bool()
  override val desiredCppName: String = "l2tlb"
}

case class DiffRefillEvent(val xlen: Int = 64, val lineBytes: Int = 64) extends DifftestBundle
  with DifftestWithValid
  with DifftestWithIndex
{
  Widths.checkXlen(xlen)
  require(lineBytes > 0 && lineBytes % (xlen / 8) == 0)
  val addr  = UInt(xlen bits)
  val data  = Vec(UInt(xlen bits), lineBytes / (xlen / 8))
  val idtfr = UInt(8 bits) // identifier for flexible usage
  override val desiredCppName: String = "refill"
}

case class DiffLrScEvent() extends DifftestBundle
  with DifftestWithValid
{
  val success = Bool()
  override val desiredCppName: String = "lrsc"
}

case class DiffRunaheadEvent(val xlen: Int = 64) extends DifftestBundle
  with DifftestWithIndex
  with DifftestWithValid
{
  Widths.checkXlen(xlen)
  val branch        = Bool()
  val may_replay    = Bool()
  val pc            = UInt(xlen bits)
  val checkpoint_id = UInt(64 bits)
  override val desiredCppName: String = "runahead"
}

case class DiffRunaheadCommitEvent(val xlen: Int = 64) extends DifftestBundle
  with DifftestWithIndex
  with DifftestWithValid
{
  Widths.checkXlen(xlen)
  val pc            = UInt(xlen bits)
  override val desiredCppName: String = "runahead_commit"
}

case class DiffRunaheadRedirectEvent(val xlen: Int = 64) extends DifftestBundle
  with DifftestWithValid
{
  Widths.checkXlen(xlen)
  val pc            = UInt(xlen bits) // for debug only
  val target_pc     = UInt(xlen bits) // for debug only
  val checkpoint_id = UInt(64 bits)
  override val desiredCppName: String = "runahead_redirect"
}

case class DiffCoverage(
  val desiredCppName: String,
  val numElements: Int
) extends DifftestBundle(numElements)
  with DifftestWithValid
  with DifftestWithAddress
{
  val covered = Bool()
  override val needFlatten: Boolean = true
}
