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

/** 内存 DPI 桥。
  *
  * 这里的 `Mem*Helper` 把 CPU 的读写请求转成 C++ 侧的 `difftest_ram_read`/`difftest_ram_write`，
  * 两侧都用 64 位字搬运，与 CPU 的 XLEN 无关；`lanes` 必须是 8 的倍数，因此一个 64 位字
  * 固定由 m=n_helper 个 helper 拼出来。
  *
  * 注意生产平台走的是另一套等价实现：`src/test/vsrc/common/ram.v` 的 `RAMHelper`
  * 配合 C++ 的 `ram_read_helper`/`ram_write_helper`，与这里的黑盒互不依赖。
  */

/** 读端口：`r_enable` 有效时把 `r_index` 指向的 64 位字回填到 `r_data`。 */
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

/** 写端口：`w_enable` 有效时按 `w_mask` 写入 `w_data`；掩码按字节展开，与位一一对应。 */
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

/** 一块可被 DiffTest 回读的内存。
  *
  * 字宽与 CPU 的 XLEN 无关：C++ 侧统一按 64 位字搬运，所以 `lanes` 是字节通道数、必须是 8 的
  * 倍数，由 `n_helper = lanes / 8` 个 helper 拼成一个字。
  *
  * **命名注意**：`read`/`write` 既是 Bundle 字段（原始端口，由本类驱动）又是方法（给集成方
  * 用的便捷入口），同名遮蔽是有意为之，读代码时先分清是哪一个。
  *
  * @param size  字节容量
  * @param lanes 字节通道数，必须是 8 的倍数
  * @param bits  每个通道的位宽，目前只支持 8
  */
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
  /** 组合读：给出地址并启用读口；同拍有写时读口让位（见 [[DifftestMem1P]] 的单口断言）。 */
  def read(addr: UInt): Vec[UInt] = {
    read.valid := !write.valid
    read.index := addr.resize(64)
    read.data
  }

  /** DPI 在下一拍才回数据，这里把它打一拍并在没有新响应时保持上一次的值。 */
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

  /** 按字节掩码写入；`data` 与 `mask` 的长度都必须等于 `lanes`，掩码逐字节展开。 */
  def write(addr: UInt, data: scala.collection.Seq[UInt], mask: scala.collection.Seq[Bool]): Unit = {
    require(data.length == lanes && data.forall(_.getWidth == bits))
    require(mask.length == lanes)
    write.valid := True
    write.index := addr.resize(64)
    write.data.assignFromBits(Vec(data.toSeq).asBits)
    write.mask.assignFromBits(Vec(mask.toSeq.map(m => Mux(m, B(255, 8 bits), B(0, 8 bits)))).asBits)
  }
}

/** 单口：读与写共用一套 helper，同拍只能有一个方向。 */
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
/** 双口：读与写各有一组 helper，可以同拍进行。 */
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
  /** `beatBytes` 是字节通道数（须为 8 的倍数），通道位宽默认 8。 */
  def apply(size: BigInt, beatBytes: Int): DifftestMem = apply(size, beatBytes, 8)

  /** 建内存实例，并把端口置成默认无效；`allowOverride()` 让调用方随后可以驱动它们。 */
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
