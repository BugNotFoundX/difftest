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
import difftest.dpic.DPIC
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable

private[difftest] object Widths {
  def checkXlen(xlen: Int): Unit =
    require(xlen == 32 || xlen == 64, "xlen/flen must be 32 or 64")
  def storageBits(width: Int): Int = {
    require(width > 0 && width <= 64, s"DPI scalar width must be in 1..64, got $width")
    Seq(8, 16, 32, 64).find(_ >= width).get
  }
}

trait DifftestWithCoreid { def coreid: UInt }
trait DifftestWithIndex { def index: UInt }
trait DifftestWithValid { def valid: Bool }
trait DifftestWithAddress { this: DifftestWithValid =>
  val numElements: Int
  def needFlatten: Boolean = false
  def address: UInt
}

private[difftest] case class DiffField(name: String, values: Seq[BaseType], vector: Boolean) {
  require(values.nonEmpty, s"Empty field $name")
  val width: Int = values.head.getBitsWidth
  require(values.forall(_.getBitsWidth == width), s"Non-uniform field $name")
  val storage: Int = Widths.storageBits(width)
  def cppType: String = s"uint${storage}_t"
  def cppDeclaration: String = s"  $cppType $name${if (vector) s"[${values.size}]" else ""};"
  def ports: Seq[(String, String, Int)] = values.indices.map { i =>
    (s"io_$name${if (vector) s"_$i" else ""}", if (vector) s"$name[$i]" else name, width)
  }
}

abstract class DifftestBundle(addressElements: Int = 0) extends Bundle with DifftestWithCoreid {
  // Declare hardware in the Bundle so Spinal includes it in naming and cloning.
  val coreid = UInt(8 bits)
  val index = isIndexed generate UInt(8 bits)
  val valid = withValid generate Bool()
  val address = (this.isInstanceOf[DifftestWithAddress]) generate {
    require(addressElements > 0)
    UInt((log2Up(addressElements) max 1) bits)
  }
  def isUniqueIdentifier: Boolean = false
  def desiredOffset: Int = 999
  val desiredCppName: String
  def desiredModuleName: String = getClass.getSimpleName.replaceFirst("^Diff", "Difftest")
  def order: (Int, String) = (desiredOffset, desiredModuleName)
  def isIndexed: Boolean = this.isInstanceOf[DifftestWithIndex]
  def getIndex: Option[UInt] = this match {
    case b: DifftestWithIndex => Some(b.index)
    case _ => None
  }
  def withValid: Boolean = this.isInstanceOf[DifftestWithValid]
  def getValid: Bool = this match {
    case b: DifftestWithValid => b.valid
    case _ => True
  }
  def isFlatten: Boolean = this match {
    case b: DifftestWithAddress => b.needFlatten
    case _ => false
  }
  def numFlattenElements: Int = this.asInstanceOf[DifftestWithAddress].numElements
  private[difftest] def fields: Seq[DiffField] = elements.toSeq.map {
    case (name, base: BaseType) => DiffField(name, Seq(base), vector = false)
    case (name, vec: Vec[_]) => DiffField(name, vec.toSeq.map {
      case base: BaseType => base
      case _ => throw new IllegalArgumentException(s"Unsupported nested field $name")
    }, vector = true)
    case (name, _) => throw new IllegalArgumentException(s"Unsupported field $name")
  }
  private[difftest] def stateFields: Seq[DiffField] = fields.filterNot { f =>
    Set("coreid", "index").contains(f.name) ||
      (isFlatten && Set("valid", "address").contains(f.name))
  }
  def diffElements: Seq[(String, Seq[BaseType])] =
    fields.filterNot(f => Set("coreid", "index").contains(f.name)).map(f => f.name -> f.values)
  def diffBytes(aligned: Int = 8): Seq[UInt] = {
    require(aligned > 0)
    diffElements.flatMap(_._2).flatMap { value =>
      val bits = value.asBits
      (0 until bits.getWidth by aligned).map { offset =>
        bits((offset + aligned - 1) min (bits.getWidth - 1) downto offset).asUInt
      }
    }
  }
  def size(round: Int): Int = {
    require(round > 0)
    diffElements.flatMap(_._2).map(v => (v.getBitsWidth + round - 1) / round).sum
  }
  def byteSize: Int = size(8)
  def toCppDeclMacro: String =
    s"#define CONFIG_DIFFTEST_${desiredModuleName.stripPrefix("Difftest").toUpperCase}"
  def toCppDeclaration: String =
    (Seq("typedef struct {") ++ stateFields.map(_.cppDeclaration) ++ Seq(s"} $desiredModuleName;")).mkString("\n")
}

trait DifftestModule[T <: DifftestBundle] { val io: T }

object DifftestModule {
  private class Registry {
    val instances = mutable.ArrayBuffer.empty[DifftestBundle]
    val interfaces = mutable.LinkedHashMap.empty[String, (String, String)]
  }
  private def registry: Registry =
    GlobalData.get.userDatabase.getOrElseUpdate(this, new Registry).asInstanceOf[Registry]
  def apply[T <: DifftestBundle](gen: T, style: String = "dpic",
                                dontCare: Boolean = false, delay: Int = 0): T = {
    require(style == "dpic", s"Unsupported DiffTest backend: $style")
    require(delay >= 0)
    register(gen, style)
    if (dontCare) {
      gen.assignDontCare()
      gen.allowOverride()
    }
    val sink = new DPIC(gen)
    sink.io := Delayer(gen, delay)
    when(ClockDomain.current.isResetActive) {
      sink.io.elements.filterNot { case (name, _) => name == "coreid" || name == "index" }
        .foreach { case (_, data) =>
          data.flatten.foreach(field => field.assignFromBits(B(0, field.getBitsWidth bits)))
        }
      // Clear the correct slot even while its delayed index register is reset.
      sink.io.getIndex.foreach(_ := gen.getIndex.get)
    }
    // A core's identity is not delayed or cleared by reset.
    sink.io.coreid.allowOverride()
    sink.io.coreid := gen.coreid
    gen
  }
  def register[T <: DifftestBundle](gen: T, style: String = "dpic"): Int = {
    require(style == "dpic", s"Unsupported DiffTest backend: $style")
    registry.instances.find(_.desiredModuleName == gen.desiredModuleName).foreach { previous =>
      require(previous.toCppDeclaration == gen.toCppDeclaration &&
        previous.fields.map(f => (f.name, f.width, f.values.size, f.vector)) ==
          gen.fields.map(f => (f.name, f.width, f.values.size, f.vector)) &&
        previous.desiredCppName == gen.desiredCppName &&
        previous.isFlatten == gen.isFlatten &&
        (!gen.isFlatten || previous.numFlattenElements == gen.numFlattenElements),
        s"Incompatible probe layouts for ${gen.desiredModuleName}")
    }
    val id = registry.instances.size
    registry.instances += gen
    id
  }
  private[difftest] def addInterface(name: String, prototype: String, body: String): Unit = {
    registry.interfaces.get(name).foreach { previous =>
      require(previous == (prototype -> body), s"Incompatible DPI declarations for $name")
    }
    registry.interfaces(name) = prototype -> body
  }
  def hasDPIC: Boolean = registry.instances.nonEmpty
  private def defaultOutputDir: String =
    Paths.get(GlobalData.get.config.targetDirectory, "generated-src").toString
  private def defaultTarget: DifftestTarget = DifftestTarget.legacyRV64(
    registry.instances.collectFirst { case p: DiffArchFpRegState => p.flen }.getOrElse(0))
  def finish(cpu: String, cppHeader: Option[String] = Some("dpic"),
             outputDir: String = defaultOutputDir,
             target: DifftestTarget = defaultTarget): Unit = {
    cppHeader.foreach { style => generateCppHeader(cpu, style, outputDir, target) }
    val interfaces = registry.interfaces.values.toSeq
    write(outputDir, "difftest-dpic.h",
      "#ifndef __DIFFTEST_DPIC_H__\n#define __DIFFTEST_DPIC_H__\n#include <cstdint>\n\n" +
        interfaces.map(_._1 + ";").mkString("\n\n") + "\n#endif\n")
    write(outputDir, "difftest-dpic.cpp",
      "#ifndef CONFIG_NO_DIFFTEST\n#include \"difftest.h\"\n#include \"difftest-dpic.h\"\n#include <cassert>\n\n" +
        interfaces.map(_._2).mkString("\n\n") + "\n#endif\n")
  }
  def generateCppHeader(cpu: String, style: String = "dpic",
                        outputDir: String = defaultOutputDir,
                        target: DifftestTarget = defaultTarget): Unit = {
    require(style == "dpic")
    val instances = registry.instances.toSeq
    target.validate(instances)
    val numCores = instances.count(_.isUniqueIdentifier)
    require(numCores > 0 && numCores <= 256, "Instantiate one DiffArchEvent per core (1..256)")
    val groups = instances.groupBy(_.desiredModuleName).toSeq.sortBy(_._2.head.order)
    require(groups.map(_._2.head.desiredCppName).distinct.size == groups.size,
      "Different probe types cannot share a C++ state member")
    val declarations = groups.map { case (_, copies) =>
      val gen = copies.head
      require(copies.size % numCores == 0, s"Different number of ${gen.desiredCppName} per core")
      require(gen.isIndexed || gen.isFlatten || copies.size == numCores,
        s"Duplicate unindexed probe ${gen.desiredCppName}")
      val width = if (gen.isFlatten) gen.numFlattenElements else copies.size / numCores
      require(!gen.isIndexed || width <= 256, "DPI index is 8 bits")
      val macroWidth = if (gen.isIndexed || gen.isFlatten)
        s"#define CONFIG_DIFF_${gen.desiredCppName.toUpperCase}_WIDTH $width\n" else ""
      gen.toCppDeclMacro + "\n" + macroWidth + gen.toCppDeclaration
    }
    val members = groups.map { case (name, copies) =>
      val gen = copies.head
      val width = if (gen.isFlatten) gen.numFlattenElements else copies.size / numCores
      val array = if (gen.isIndexed || gen.isFlatten) s"[$width]" else ""
      s"  $name ${gen.desiredCppName}$array;"
    }
    val cpuName = cpu.toUpperCase.replaceAll("[^A-Z0-9_]", "_")
    require(cpuName.nonEmpty, "CPU name cannot be empty")
    val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
      .digest((target.macros + numCores + declarations.mkString + members.mkString)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .take(8).map(b => f"${b & 0xff}%02x").mkString
    write(outputDir, "diffstate.h",
      s"#ifndef __DIFFSTATE_H__\n#define __DIFFSTATE_H__\n#include <cstdint>\n\n#define CPU_$cpuName\n#define NUM_CORES $numCores\n\n" +
        target.macros + s"\n#define DIFFTEST_SCHEMA_FINGERPRINT 0x${fingerprint}ULL\n\n" +
        declarations.mkString("\n\n") + "\n\ntypedef struct {\n" + members.mkString("\n") +
        "\n} DiffTestState;\n\n#endif\n")
    val probeSchema = groups.map { case (name, copies) =>
      val gen = copies.head
      val slots = if (gen.isFlatten) gen.numFlattenElements else copies.size / numCores
      val fields = gen.stateFields.map { f =>
        s"""{"name":"${f.name}","bits":${f.width},"storage_bits":${f.storage},"count":${f.values.size}}"""
      }.mkString(",")
      s"""{"type":"$name","member":"${gen.desiredCppName}","slots":$slots,"fields":[$fields]}"""
    }.mkString(",\n    ")
    write(outputDir, "difftest-schema.json", s"""{
  "schema_version":1,"isa":"${target.isa}","xlen":${target.xlen},"flen":${target.flen},
  "paddr_bits":${target.paddrBits},"vaddr_bits":${target.vaddrBits},
  "ref_abi":"${target.refAbi}","cpu":"$cpuName","num_cores":$numCores,
  "probes":[
    $probeSchema
  ]
}
""")
  }
  private def write(directory: String, name: String, content: String): Unit = {
    val dir = Paths.get(directory)
    Files.createDirectories(dir)
    Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8))
  }
}

object Delayer {
  def apply[T <: Data](gen: T, cycles: Int): T = {
    require(cycles >= 0)
    (0 until cycles).foldLeft(gen) { (data, _) => RegNext(data) init(data.getZero) }
  }
}
class PerfInfoIO extends Bundle {
  val clean = in Bool()
  val dump = in Bool()
}
class LogCtrlIO extends Bundle {
  val log_begin = in UInt(64 bits)
  val log_end = in UInt(64 bits)
  val log_level = in UInt(64 bits)
}
class UARTIO extends Bundle {
  val out = new Bundle {
    val valid = spinal.core.out(Bool())
    val ch = spinal.core.out(UInt(8 bits))
  }
  val in = new Bundle {
    val valid = spinal.core.out(Bool())
    val ch = spinal.core.in(UInt(8 bits))
  }
}
