package difftest

import spinal.core._
import difftest.common._

class TransportTop(xlen: Int, isa: String = "") extends Component {
  val io = new Bundle {
    val valid = in Bool()
    val pc = in UInt(xlen bits)
    val data = in UInt(xlen bits)
    val address = in UInt(7 bits)
  }
  def probe[T <: DifftestBundle](gen: T, delay: Int = 0): T = {
    val p = DifftestModule(gen, delay = delay)
    p.flatten.foreach(f => f.assignFromBits(B(0, f.getBitsWidth bits)))
    p.allowOverride()
    p
  }
  val event = probe(DiffArchEvent(xlen))
  event.valid := io.valid
  event.exceptionPC := io.pc
  val first = probe(DiffInstrCommit(numPhyRegs = 65, xlen = xlen, robIdxBits = 17), delay = 1)
  first.valid := io.valid
  first.pc := io.pc
  first.robIdx := 0x10001
  first.setSpecial(isExit = io.valid)
  val second = probe(DiffInstrCommit(numPhyRegs = 65, xlen = xlen, robIdxBits = 17))
  second.index := 1
  second.valid := io.valid
  second.pc := io.pc + 4
  second.wpdest := 64
  val regs = probe(DiffArchIntRegState(xlen))
  regs.value.zipWithIndex.foreach { case (r, i) => r := io.data + i }
  val wb = probe(DiffIntWriteback(numElements = 65, xlen = xlen))
  wb.valid := io.valid
  wb.address := io.address
  wb.data := io.data
  val store = probe(DiffStoreEvent(xlen))
  store.valid := io.valid
  store.addr := io.pc
  store.data := io.data
  store.mask := 1
  val line = probe(DiffSbufferEvent(xlen, lineBytes = 1))
  line.valid := io.valid
  line.data(0) := io.data.resize(8)
  val refill = probe(DiffRefillEvent(xlen, lineBytes = 8))
  refill.valid := io.valid
  refill.data.foreach(_ := io.data)
  DifftestModule.finish("TEST", target = DifftestTarget(
    if (isa.nonEmpty) isa else s"riscv$xlen", xlen, 0, xlen, xlen, "none"))
}

class MemoryTop extends Component {
  val io = new Bundle {
    val ren = in Bool()
    val wen = in Bool()
    val index = in UInt(64 bits)
    val low = in UInt(64 bits)
    val high = in UInt(64 bits)
    val mask = in Bits(16 bits)
    val oneLow, oneHigh, twoLow, twoHigh = out UInt(64 bits)
  }
  val memories = Seq(
    DifftestMem(1024, 16, 8, singlePort = true),
    DifftestMem(1024, 16, 8, singlePort = false)
  )
  val results = memories.zipWithIndex.map { case (mem, port) =>
    val addr = io.index + (port * 4)
    when(io.wen) {
      mem.write(addr, (io.high.asBits ## io.low.asBits).subdivideIn(8 bits).map(_.asUInt), io.mask.asBools)
    }
    mem.readAndHold(addr, io.ren).asBits
  }
  io.oneLow := results(0)(63 downto 0).asUInt
  io.oneHigh := results(0)(127 downto 64).asUInt
  io.twoLow := results(1)(63 downto 0).asUInt
  io.twoHigh := results(1)(127 downto 64).asUInt
}
