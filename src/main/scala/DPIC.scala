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

class DPIC[T <: DifftestBundle](gen: T) extends BlackBox with DifftestModule[T] {
  val clock = in Bool()
  val io = in(cloneOf(gen))
  setDefinitionName(gen.desiredModuleName)
  mapClockDomain(clock = clock)

  private val args = gen.fields.flatMap(_.ports)
  private val functionName = s"v_difftest_${gen.desiredModuleName.stripPrefix("Difftest")}"
  private def svType(width: Int): String = width match {
    case 1 => "bit"
    case _ => Widths.storageBits(width) match {
      case 8 => "byte unsigned"
      case 16 => "shortint unsigned"
      case 32 => "int unsigned"
      case 64 => "longint unsigned"
    }
  }
  private val prototype = s"extern \"C\" void $functionName(\n" +
    args.map { case (port, _, width) =>
      val cType = if (width > 32) "unsigned long long" else s"uint${Widths.storageBits(width)}_t"
      s"  $cType $port"
    }.mkString(",\n") + "\n)"
  private val removed = Set("coreid", "index") ++
    (if (gen.isFlatten) Set("valid", "address") else Set.empty[String])
  private val assigns = gen.fields.filterNot(f => removed(f.name) || f.name == "valid")
    .flatMap(_.ports).map { case (port, member, _) => s"packet->$member = $port;" }
  private val index = if (gen.isIndexed) "[io_index]" else if (gen.isFlatten) "[io_address]" else ""
  private val checkIndex =
    if (gen.isIndexed) s"  assert(io_index < CONFIG_DIFF_${gen.desiredCppName.toUpperCase}_WIDTH);\n"
    else if (gen.isFlatten) s"  assert(io_address < CONFIG_DIFF_${gen.desiredCppName.toUpperCase}_WIDTH);\n" else ""
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

  private val ports = "  input clock" +: args.map { case (port, _, width) =>
    s"  input ${if (width == 1) "" else s"[${width - 1}:0] "}$port"
  }
  setInlineVerilog(s"""module ${gen.desiredModuleName}(
${ports.mkString(",\n")}
);
`ifndef SYNTHESIS
`ifdef DIFFTEST
  import "DPI-C" function void $functionName(
${args.map { case (port, _, width) => s"    input ${svType(width)} $port" }.mkString(",\n")}
  );
  always @(posedge clock) begin
    $functionName(${args.map { case (port, _, width) => s"${if (width == 1) 1 else Widths.storageBits(width)}'($port)" }.mkString(", ")});
  end
`endif
`endif
endmodule
""")
}
