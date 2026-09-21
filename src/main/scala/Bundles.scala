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

case class DiffArchIntRegState(val xlen: Int = 64) extends DifftestBundle {
  Widths.checkXlen(xlen)
  val value = Vec(UInt(xlen bits), 32)
  override val desiredCppName: String = "regs_int"
  override val desiredOffset: Int = 0
}

abstract class DiffArchDelayedUpdate(val numElements: Int, val dataBits: Int) extends DifftestBundle(numElements)
  with DifftestWithIndex
  with DifftestWithValid
  with DifftestWithAddress
{
  Widths.storageBits(dataBits)
  val data = UInt(dataBits bits)
  val nack = Bool()
}

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
