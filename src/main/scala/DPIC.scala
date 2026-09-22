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

package difftest.dpic

import spinal.core._
import difftest._

/** 一个探针的 DPI 端点：把探针字段拉平成一串标量端口，并生成与它们对应的 C++ 函数。
  *
  * 每个实例产出三样东西，名字都由探针推导：
  *   - Verilog 黑盒模块 `${gen.desiredModuleName}`，端口是 `io_<字段名>` 加时钟；
  *   - DPI 函数 `v_difftest_<类名去掉 Difftest 前缀>` 的 C++ 声明与实现，
  *     实现直接写 `difftest[io_coreid]->dut.<desiredCppName>[index]`；
  *   - 注册到 [[DifftestModule]] 的接口表，最终汇总进 `difftest-dpic.h` / `.cpp`。
  *
  * 三段文本就是契约本身：改了它们，Verilog 与 C++ 两侧必须一起改。
  */
class DPIC[T <: DifftestBundle](gen: T) extends BlackBox with DifftestModule[T] {
  val clock = in Bool()
  val io = in(cloneOf(gen))
  setDefinitionName(gen.desiredModuleName)
  mapClockDomain(clock = clock)

  /** 拉平后的全部端口：一个字段可能展开成多个（向量）。 */
  private val args: Seq[DiffPort] = gen.fields.flatMap(_.ports)

  private val functionName = s"v_difftest_${gen.desiredModuleName.stripPrefix("Difftest")}"

  /** DPI 只认这几种标量类型；1 位单独走 `bit`。 */
  private def svType(width: Int): String = width match {
    case 1 => "bit"
    case _ => Widths.storageBits(width) match {
      case 8 => "byte unsigned"
      case 16 => "shortint unsigned"
      case 32 => "int unsigned"
      case 64 => "longint unsigned"
    }
  }

  /** C++ 函数原型，同时作为头文件里的声明。 */
  private val prototype = s"extern \"C\" void $functionName(\n" +
    args.map { a =>
      val cType = if (a.width > 32) "unsigned long long" else s"uint${Widths.storageBits(a.width)}_t"
      s"  $cType ${a.port}"
    }.mkString(",\n") + "\n)"

  /** 不往 C++ 结构体里写的字段：身份字段恒不写，扁平化探针另外不写 `valid`/`address`。 */
  private val removed = Set("coreid", "index") ++
    (if (gen.isFlatten) Set("valid", "address") else Set.empty[String])

  /** 结构体赋值语句；`valid` 单独处理，见下面的 [[statements]]。 */
  private val assigns = gen.fields.filterNot(f => removed(f.name) || f.name == "valid")
    .flatMap(_.ports).map(a => s"packet->${a.member} = ${a.port};")

  /** 取槽位的下标表达式：索引探针用 `index`，扁平化探针用 `address`。 */
  private val index = if (gen.isIndexed) "[io_index]" else if (gen.isFlatten) "[io_address]" else ""

  /** 槽位越界检查；槽位数来自 `diffstate.h` 里的 `CONFIG_DIFF_<名>_WIDTH`。 */
  private val checkIndex =
    if (gen.isIndexed) s"  assert(io_index < CONFIG_DIFF_${gen.desiredCppName.toUpperCase}_WIDTH);\n"
    else if (gen.isFlatten) s"  assert(io_address < CONFIG_DIFF_${gen.desiredCppName.toUpperCase}_WIDTH);\n" else ""

  /** 函数体语句。带 `valid` 的探针只在 `io_valid` 时写数据字段（`valid` 本身无条件写），
    * 扁平化探针的 `valid` 由调用方在入口提前返回处理，故不再单列。
    */
  private val statements = if (gen.withValid) {
    (if (gen.isFlatten) Seq.empty else Seq("packet->valid = io_valid;")) ++
      Seq("if (io_valid) {") ++ assigns.map("  " + _) ++ Seq("}")
  } else assigns

  private val body = prototype + " {\n  if (difftest == nullptr) return;\n" +
    "  assert(io_coreid < NUM_CORES);\n" +
    (if (gen.isFlatten) "  if (!io_valid) return;\n" else "") + checkIndex +
    s"  auto *packet = &difftest[io_coreid]->dut.${gen.desiredCppName}$index;\n" +
    statements.map("  " + _).mkString("\n") + "\n}"
  DifftestModule.addInterface(functionName, prototype, body)

  /** 黑盒模块的端口：时钟加全部拉平端口。 */
  private val verilogPorts = "  input clock" +: args.map { a =>
    s"  input ${if (a.width == 1) "" else s"[${a.width - 1}:0] "}${a.port}"
  }

  /** DPI 声明的形参表（SV 侧类型）。 */
  private val svImportArgs = args.map { a => s"    input ${svType(a.width)} ${a.port}" }.mkString(",\n")

  /** DPI 调用的实参表：按存储宽度转型后逐个传入。 */
  private val svCallArgs = args.map { a =>
    val cast = if (a.width == 1) "1" else Widths.storageBits(a.width).toString
    s"$cast'(${a.port})"
  }.mkString(", ")

  setInlineVerilog(s"""module ${gen.desiredModuleName}(
${verilogPorts.mkString(",\n")}
);
`ifndef SYNTHESIS
`ifdef DIFFTEST
  import "DPI-C" function void $functionName(
$svImportArgs
  );
  always @(posedge clock) begin
    $functionName($svCallArgs);
  end
`endif
`endif
endmodule
""")
}
