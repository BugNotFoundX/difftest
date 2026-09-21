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
import java.nio.file.Paths

class DifftestTop(val xlen: Int = 64, val flen: Int = 64, val vlen: Int = 128,
                  val numPhyRegs: Int = 32, val numCores: Int = 1) extends Component {
  Widths.checkXlen(xlen)
  Widths.checkXlen(flen)
  require(numCores > 0 && numCores <= 256)
  for (core <- 0 until numCores) {
    val probes = Seq(
      new DiffArchEvent(xlen), new DiffInstrCommit(numPhyRegs, xlen),
      new DiffTrapEvent(xlen), new DiffCSRState(xlen), new DiffDebugMode(xlen),
      new DiffArchVecRegState(vlen), new DiffVecCSRState(xlen),
      new DiffIntWriteback(numPhyRegs, xlen), new DiffFpWriteback(numPhyRegs, flen),
      new DiffArchIntRegState(xlen), new DiffArchFpRegState(flen),
      new DiffArchIntDelayedUpdate(xlen), new DiffArchFpDelayedUpdate(flen),
      new DiffSbufferEvent(xlen), new DiffStoreEvent(xlen), new DiffLoadEvent(xlen),
      new DiffAtomicEvent(xlen), new DiffL1TLBEvent(xlen), new DiffL2TLBEvent(xlen),
      new DiffRefillEvent(xlen), new DiffLrScEvent,
      new DiffRunaheadEvent(xlen), new DiffRunaheadCommitEvent(xlen),
      new DiffRunaheadRedirectEvent(xlen)
    )
    probes.foreach { gen =>
      val input = in(gen)
      input.setName(s"core${core}_${gen.desiredCppName}")
      val probe = DifftestModule(cloneOf(gen))
      probe := input
      probe.coreid.allowOverride()
      probe.coreid := core
    }
  }
  DifftestModule.finish("GENERIC", target =
    if (xlen == 64 && flen == 64) DifftestTarget.legacyRV64(flen)
    else DifftestTarget(s"riscv$xlen", xlen, flen, xlen, xlen, "none"))
}

object DifftestMain {
  case class Options(targetDir: String = "build", xlen: Int = 64, flen: Int = 64,
                     vlen: Int = 128, numPhyRegs: Int = 32, numCores: Int = 1)
  def parse(args: List[String], options: Options = Options()): Options = args match {
    case Nil => options
    case ("--target-dir" | "-td") :: value :: tail => parse(tail, options.copy(targetDir = value))
    case "--xlen" :: value :: tail => parse(tail, options.copy(xlen = value.toInt))
    case "--flen" :: value :: tail => parse(tail, options.copy(flen = value.toInt))
    case "--vlen" :: value :: tail => parse(tail, options.copy(vlen = value.toInt))
    case "--num-phy-regs" :: value :: tail => parse(tail, options.copy(numPhyRegs = value.toInt))
    case "--num-cores" :: value :: tail => parse(tail, options.copy(numCores = value.toInt))
    case other => throw new IllegalArgumentException(s"Unknown or incomplete option: ${other.mkString(" ")}")
  }
  def generate(options: Options): SpinalReport[DifftestTop] = {
    val directory = Paths.get(options.targetDir).toAbsolutePath.normalize.toString
    SpinalConfig(targetDirectory = directory,
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
    ).generateSystemVerilog(new DifftestTop(
      options.xlen, options.flen, options.vlen, options.numPhyRegs, options.numCores))
  }
  def main(args: Array[String]): Unit = {
    if (args.contains("--help")) {
      println("DifftestMain [--target-dir DIR] [--xlen 32|64] [--flen 32|64] " +
        "[--vlen BITS] [--num-phy-regs N] [--num-cores N]")
    } else generate(parse(args.toList))
  }
}
