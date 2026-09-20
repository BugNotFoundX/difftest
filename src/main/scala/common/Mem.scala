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

package difftest.common

import spinal.core._

trait HasReadPort { this: BlackBox =>
  val r = new Bundle {
    val enable = in Bool()
    val index = in UInt(64 bits)
    val data = out UInt(64 bits)
  }
  r.setName("r")
  protected val readPorts = "input r_enable, input [63:0] r_index, output reg [63:0] r_data"
  protected val readImport =
    "import \"DPI-C\" function longint unsigned difftest_ram_read(input longint unsigned rIdx);"
  protected val readCall = "if (r_enable) r_data <= difftest_ram_read(r_index);"
  def read(enable: Bool, index: UInt): UInt = {
    r.enable := enable
    r.index := index.resize(64)
    r.data
  }
}

trait HasWritePort { this: BlackBox =>
  val w = new Bundle {
    val enable = in Bool()
    val index = in UInt(64 bits)
    val data = in UInt(64 bits)
    val mask = in UInt(64 bits)
  }
  w.setName("w")
  protected val writePorts =
    "input w_enable, input [63:0] w_index, input [63:0] w_data, input [63:0] w_mask"
  protected val writeImport =
    "import \"DPI-C\" function void difftest_ram_write(input longint unsigned index, " +
      "input longint unsigned data, input longint unsigned mask);"
  protected val writeCall = "if (w_enable) difftest_ram_write(w_index, w_data, w_mask);"
  def write(enable: Bool, index: UInt, data: UInt, mask: UInt): HasWritePort = {
    w.enable := enable
    w.index := index.resize(64)
    w.data := data
    w.mask := mask
    this
  }
}

class MemRHelper extends BlackBox with HasReadPort {
  val clock = in Bool()
  mapClockDomain(clock = clock)
  setInlineVerilog(s"""module MemRHelper($readPorts, input clock);
  $readImport
  always @(posedge clock) begin
    $readCall
  end
endmodule
""")
}
class MemWHelper extends BlackBox with HasWritePort {
  val clock = in Bool()
  mapClockDomain(clock = clock)
  setInlineVerilog(s"""module MemWHelper($writePorts, input clock);
  $writeImport
  always @(posedge clock) begin
    $writeCall
  end
endmodule
""")
}
class MemRWHelper extends BlackBox with HasReadPort with HasWritePort {
  val clock = in Bool()
  val enable = in Bool()
  mapClockDomain(clock = clock)
  setInlineVerilog(s"""module MemRWHelper($readPorts, $writePorts, input enable, input clock);
  $readImport
  $writeImport
  always @(posedge clock) begin
    if (enable) begin
      $readCall
      $writeCall
    end
  end
endmodule
""")
}

// RAM's C++ API transports 64-bit words independently of the CPU's XLEN.
abstract class DifftestMem(val size: BigInt, val lanes: Int, val bits: Int) extends Component {
  require(size > 0)
  require(bits == 8 && lanes > 0 && lanes % 8 == 0, "RAM DPI requires 64-bit aligned byte lanes")
  val n_helper = lanes / 8
  val read = new Bundle {
    val valid = in Bool()
    val index = in UInt(64 bits)
    val data = out(Vec(UInt(64 bits), n_helper))
  }
  val write = new Bundle {
    val valid = in Bool()
    val index = in UInt(64 bits)
    val data = in(Vec(UInt(64 bits), n_helper))
    val mask = in(Vec(UInt(64 bits), n_helper))
  }
  def read(addr: UInt): Vec[UInt] = {
    read.valid := !write.valid
    read.index := addr.resize(64)
    read.data
  }
  def readAndHold(addr: UInt, en: Bool): Vec[UInt] = {
    read.valid := en
    read.index := addr.resize(64)
    val responseValid = RegNext(en) init(False)
    val held = RegNextWhen(read.data, responseValid)
    held.foreach(_ init(0))
    val bytes = Vec(UInt(bits bits), lanes)
    bytes.assignFromBits(Mux(responseValid, read.data, held).asBits)
    bytes
  }
  def write(addr: UInt, data: scala.collection.Seq[UInt], mask: scala.collection.Seq[Bool]): Unit = {
    require(data.length == lanes && data.forall(_.getWidth == bits))
    require(mask.length == lanes)
    write.valid := True
    write.index := addr.resize(64)
    write.data.assignFromBits(Vec(data.toSeq).asBits)
    write.mask.assignFromBits(Vec(mask.toSeq.map(m => Mux(m, B(255, 8 bits), B(0, 8 bits)))).asBits)
  }
}

class DifftestMem1P(size: BigInt, lanes: Int, bits: Int) extends DifftestMem(size, lanes, bits) {
  when(!ClockDomain.current.isResetActive) {
    assert(!read.valid || !write.valid, "read and write in the same single-port cycle")
  }
  for (i <- 0 until n_helper) {
    val helper = new MemRWHelper
    helper.enable := !ClockDomain.current.isResetActive
    helper.write(write.valid, (write.index * n_helper + i).resize(64), write.data(i), write.mask(i))
    read.data(i) := helper.read(read.valid, (read.index * n_helper + i).resize(64))
  }
}
class DifftestMem2P(size: BigInt, lanes: Int, bits: Int) extends DifftestMem(size, lanes, bits) {
  for (i <- 0 until n_helper) {
    val reader = new MemRHelper
    read.data(i) := reader.read(!ClockDomain.current.isResetActive && read.valid,
      (read.index * n_helper + i).resize(64))
    val writer = new MemWHelper
    writer.write(!ClockDomain.current.isResetActive && write.valid,
      (write.index * n_helper + i).resize(64), write.data(i), write.mask(i))
  }
}
object DifftestMem {
  def apply(size: BigInt, beatBytes: Int): DifftestMem = apply(size, beatBytes, 8)
  def apply(size: BigInt, lanes: Int, bits: Int, singlePort: Boolean = true): DifftestMem = {
    val memory = if (singlePort) new DifftestMem1P(size, lanes, bits) else new DifftestMem2P(size, lanes, bits)
    memory.read.valid := False
    memory.read.index := 0
    memory.write.assignFromBits(B(0, memory.write.getBitsWidth bits))
    memory.read.valid.allowOverride()
    memory.read.index.allowOverride()
    memory.write.allowOverride()
    memory
  }
}
