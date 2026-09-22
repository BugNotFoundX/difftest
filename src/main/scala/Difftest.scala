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

/** DPI 只提供固定几档标量宽度，信号按最小的可容纳档位搬运。 */
private[difftest] object Widths {
  /** 探针的 XLEN/FLEN 只支持 32 或 64。 */
  def checkXlen(xlen: Int): Unit =
    require(xlen == 32 || xlen == 64, "xlen/flen must be 32 or 64")

  /** 该位宽在 DPI 边界上用多少位承载：1 位走 `bit`，其余取 8/16/32/64 中最小的可容纳者。 */
  def storageBits(width: Int): Int = {
    require(width > 0 && width <= 64, s"DPI scalar width must be in 1..64, got $width")
    Seq(8, 16, 32, 64).find(_ >= width).get
  }
}

/** 探针的可选能力。实现这些 trait 会让 [[DifftestBundle]] 多出对应字段与端口。 */
trait DifftestWithCoreid { def coreid: UInt }
trait DifftestWithIndex { def index: UInt }
trait DifftestWithValid { def valid: Bool }
trait DifftestWithAddress { this: DifftestWithValid =>
  val numElements: Int
  def needFlatten: Boolean = false
  def address: UInt
}

/** 探针字段在 DPI 边界上的一个端口。
  *
  * @param port   Verilog 侧端口名（`io_<字段名>`，向量字段带下标）
  * @param member C++ 侧 `DiffTestState` 里的成员名（向量字段带下标）
  * @param width  信号位宽
  */
private[difftest] case class DiffPort(port: String, member: String, width: Int)

/** 一个探针字段：它是 [[DifftestBundle]] 里的一个元素，也是 C++ 结构体里的一个成员。
  *
  * 向量字段（`Vec`）在两侧都展开：Verilog 用 `io_<名>_<下标>`，C++ 用 `<名>[<下标>]`。
  */
private[difftest] case class DiffField(name: String, values: Seq[BaseType], vector: Boolean) {
  require(values.nonEmpty, s"Empty field $name")
  val width: Int = values.head.getBitsWidth
  require(values.forall(_.getBitsWidth == width), s"Non-uniform field $name")
  val storage: Int = Widths.storageBits(width)
  def cppType: String = s"uint${storage}_t"
  def cppDeclaration: String = s"  $cppType $name${if (vector) s"[${values.size}]" else ""};"
  def ports: Seq[DiffPort] = values.indices.map { i =>
    DiffPort(s"io_$name${if (vector) s"_$i" else ""}", if (vector) s"$name[$i]" else name, width)
  }
}

/** 一个 DPI 探针：Spinal 侧的硬件字段，加上它在生成物里的名字与布局。
  *
  * 名字沿着几条链传播，改任何一环都要同步改 `src/test/csrc/`：
  *   - 类名 → [[desiredModuleName]]：生成的 Verilog 模块名与 `CONFIG_DIFFTEST_*` 宏；
  *   - [[desiredCppName]]：`DiffTestState` 里的成员名（同名的探针必须布局兼容）；
  *   - 字段名 → Verilog 端口名与 C++ 结构体成员名，见 [[DiffField.ports]]；
  *   - [[desiredOffset]]／[[order]]：决定成员在 `DiffTestState` 里的顺序，属 ABI。
  *
  * @param addressElements 扁平化探针（[[DifftestWithAddress]]）的元素个数，用来定位宽
  */
abstract class DifftestBundle(addressElements: Int = 0) extends Bundle with DifftestWithCoreid {
  // Declare hardware in the Bundle so Spinal includes it in naming and cloning.
  val coreid = UInt(8 bits)
  val index = isIndexed generate UInt(8 bits)
  val valid = withValid generate Bool()
  val address = (this.isInstanceOf[DifftestWithAddress]) generate {
    require(addressElements > 0)
    UInt((log2Up(addressElements) max 1) bits)
  }

  /** 每个核恰好实例化一个（如异常探针）；核数就是数它数出来的。 */
  def isUniqueIdentifier: Boolean = false

  /** 同名字段在 `DiffTestState` 里的排序权重，默认排到最后。 */
  def desiredOffset: Int = 999

  /** `DiffTestState` 里的成员名。 */
  val desiredCppName: String

  /** 生成的 Verilog 模块名：类名 `Diff<X>` → `Difftest<X>`。 */
  def desiredModuleName: String = getClass.getSimpleName.replaceFirst("^Diff", "Difftest")

  /** 成员顺序 = (排序权重, 模块名)，`generateCppHeader` 据此排序。 */
  def order: (Int, String) = (desiredOffset, desiredModuleName)

  def isIndexed: Boolean = this.isInstanceOf[DifftestWithIndex]
  def getIndex: Option[UInt] = this match {
    case b: DifftestWithIndex => Some(b.index)
    case _ => None
  }
  def withValid: Boolean = this.isInstanceOf[DifftestWithValid]

  /** 未实现 [[DifftestWithValid]] 的探针恒视为有效。当前无调用者。 */
  def getValid: Bool = this match {
    case b: DifftestWithValid => b.valid
    case _ => True
  }
  def isFlatten: Boolean = this match {
    case b: DifftestWithAddress => b.needFlatten
    case _ => false
  }
  def numFlattenElements: Int = this.asInstanceOf[DifftestWithAddress].numElements

  /** 全部字段，含搬运用的身份字段。 */
  private[difftest] def fields: Seq[DiffField] = elements.toSeq.map {
    case (name, base: BaseType) => DiffField(name, Seq(base), vector = false)
    case (name, vec: Vec[_]) => DiffField(name, vec.toSeq.map {
      case base: BaseType => base
      case _ => throw new IllegalArgumentException(s"Unsupported nested field $name")
    }, vector = true)
    case (name, _) => throw new IllegalArgumentException(s"Unsupported field $name")
  }

  /** 进 C++ 结构体的字段：`coreid`/`index` 恒不进；扁平化探针另外去掉 `valid`/`address`
    * （它们改用 `CONFIG_DIFF_<名>_WIDTH` 定槽位数，见 `DPIC` 的边界检查）。
    */
  private[difftest] def stateFields: Seq[DiffField] = fields.filterNot { f =>
    Set("coreid", "index").contains(f.name) ||
      (isFlatten && Set("valid", "address").contains(f.name))
  }

  /** 参与架构比对的数据字段（只去掉身份字段，扁平化探针保留 `valid`/`address`）。 */
  def diffElements: Seq[(String, Seq[BaseType])] =
    fields.filterNot(f => Set("coreid", "index").contains(f.name)).map(f => f.name -> f.values)

  /** 按 `aligned` 位对齐把数据字段切成 UInt。上游的 DiffTrace 记录布局用它；
    * 本分支没有调用者——当前的 DiffTrace 整条写 `DiffTestState` 并用 schema 指纹校验
    * （见 `src/test/csrc/difftest/difftrace.cpp`）。
    */
  def diffBytes(aligned: Int = 8): Seq[UInt] = {
    require(aligned > 0)
    diffElements.flatMap(_._2).flatMap { value =>
      val bits = value.asBits
      (0 until bits.getWidth by aligned).map { offset =>
        bits((offset + aligned - 1) min (bits.getWidth - 1) downto offset).asUInt
      }
    }
  }

  /** 数据字段按 `round` 位对齐后占多少单元。与 [[diffBytes]] 一样，本分支没有调用者。 */
  def size(round: Int): Int = {
    require(round > 0)
    diffElements.flatMap(_._2).map(v => (v.getBitsWidth + round - 1) / round).sum
  }
  def byteSize: Int = size(8)

  /** `CONFIG_DIFFTEST_<名>`：标记该探针类型存在。 */
  def toCppDeclMacro: String =
    s"#define CONFIG_DIFFTEST_${desiredModuleName.stripPrefix("Difftest").toUpperCase}"

  /** `DiffTestState` 里的这个探针结构体定义，字段顺序即 [[stateFields]] 的顺序。 */
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

  /** 把一个探针接到 DPI 边界上，返回同一个 `gen` 供调用方继续驱动。
    *
    * 每个探针配一个 [[difftest.dpic.DPIC]] blackbox；复位期间它的数据字段被清零、
    * `coreid` 与槽位索引除外（索引要按 `gen` 自己的值清，才能清到正确的槽位）。
    * 核号不参与延迟也不被复位清除。
    *
    * @param dontCare 调用方不驱动全部字段，未驱动的字段按无关处理
    * @param delay    在探针前插入几拍寄存器延迟，用来对齐不同流水级的观测点
    */
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

  /** 登记探针并返回它在注册表里的序号。
    *
    * 同模块名的探针必须布局兼容——结构体定义、字段名与宽度、`desiredCppName` 和
    * 扁平化槽位数都要一致；`Incompatible probe layouts` 这句报错被
    * `src/test/scala/MigrationSpec.scala` 断言，改字会挂测试。
    */
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

  /** [[difftest.dpic.DPIC]] 登记一个 DPI 函数的声明与实现，同名函数的声明必须一致。 */
  private[difftest] def addInterface(name: String, prototype: String, body: String): Unit = {
    registry.interfaces.get(name).foreach { previous =>
      require(previous == (prototype -> body), s"Incompatible DPI declarations for $name")
    }
    registry.interfaces(name) = prototype -> body
  }

  /** 是否登记过任何探针。当前无调用者。 */
  def hasDPIC: Boolean = registry.instances.nonEmpty

  private def defaultOutputDir: String =
    Paths.get(GlobalData.get.config.targetDirectory, "generated-src").toString

  /** 不指定 target 时的兜底：按已登记的 FP 探针推断，没有就当作无浮点。 */
  private def defaultTarget: DifftestTarget = DifftestTarget.legacyRV64(
    registry.instances.collectFirst { case p: DiffArchFpRegState => p.flen }.getOrElse(0))

  /** 收尾：写出 C++ 侧的全部生成物。`cppHeader = None` 时只写 DPI 的声明与实现。
    *
    * 产物都落在 `<outputDir>/`（默认 `<targetDirectory>/generated-src/`）：
    *   - `diffstate.h`：结构体布局、平台宏、schema 指纹；
    *   - `difftest-schema.json`：同样的布局，给工具链与 manifest 用；
    *   - `difftest-dpic.h` / `.cpp`：每个探针的 DPI 函数声明与实现（来自 DPIC）。
    */
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

  /** 写出 `diffstate.h` 与 `difftest-schema.json`。
    *
    * 两个文件描述同一份 `DiffTestState` 布局：成员顺序由探针的
    * [[DifftestBundle.order]] 决定，属 ABI，改动会让已有的 DiffTrace 失效。
    */
  def generateCppHeader(cpu: String, style: String = "dpic",
                        outputDir: String = defaultOutputDir,
                        target: DifftestTarget = defaultTarget): Unit = {
    require(style == "dpic")
    val instances = registry.instances.toSeq
    target.validate(instances)
    val numCores = coreCount(instances)
    val groups = probeGroups(instances)
    val declarations = cppDeclarations(groups, numCores)
    val members = stateMembers(groups, numCores)
    val cpuName = cpu.toUpperCase.replaceAll("[^A-Z0-9_]", "_")
    require(cpuName.nonEmpty, "CPU name cannot be empty")
    val fingerprint = schemaFingerprint(target, numCores, declarations, members)
    write(outputDir, "diffstate.h",
      s"#ifndef __DIFFSTATE_H__\n#define __DIFFSTATE_H__\n#include <cstdint>\n\n#define CPU_$cpuName\n#define NUM_CORES $numCores\n\n" +
        target.macros + s"\n#define DIFFTEST_SCHEMA_FINGERPRINT 0x${fingerprint}ULL\n\n" +
        declarations.mkString("\n\n") + "\n\ntypedef struct {\n" + members.mkString("\n") +
        "\n} DiffTestState;\n\n#endif\n")
    write(outputDir, "difftest-schema.json", schemaJson(groups, numCores, cpuName, target))
  }

  /** 核数由"每核一个"的探针（如异常探针）数出来。 */
  private def coreCount(instances: Seq[DifftestBundle]): Int = {
    val numCores = instances.count(_.isUniqueIdentifier)
    require(numCores > 0 && numCores <= 256, "Instantiate one DiffArchEvent per core (1..256)")
    numCores
  }

  /** 按模块名分组并按 [[DifftestBundle.order]] 排序；这个顺序就是结构体成员顺序。 */
  private def probeGroups(instances: Seq[DifftestBundle]): Seq[(String, Seq[DifftestBundle])] = {
    val groups = instances.groupBy(_.desiredModuleName).toSeq.sortBy(_._2.head.order)
    require(groups.map(_._2.head.desiredCppName).distinct.size == groups.size,
      "Different probe types cannot share a C++ state member")
    groups
  }

  /** 每个探针类型的 C++ 结构体定义，索引/扁平化的探针额外带槽位数宏。 */
  private def cppDeclarations(groups: Seq[(String, Seq[DifftestBundle])], numCores: Int): Seq[String] =
    groups.map { case (_, copies) =>
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

  /** `DiffTestState` 的成员声明，索引/扁平化的探针是定长数组。 */
  private def stateMembers(groups: Seq[(String, Seq[DifftestBundle])], numCores: Int): Seq[String] =
    groups.map { case (name, copies) =>
      val gen = copies.head
      val width = if (gen.isFlatten) gen.numFlattenElements else copies.size / numCores
      val array = if (gen.isIndexed || gen.isFlatten) s"[$width]" else ""
      s"  $name ${gen.desiredCppName}$array;"
    }

  /** schema 指纹：`DiffTrace` 头部用它拒绝结构不匹配的 trace。
    *
    * SHA-256 覆盖平台宏、核数与两份声明，取前 8 字节。故意**不含** CPU 名（改
    * `finish("...")` 会改变 `diffstate.h` 的字节但不改变指纹）与 DPI 函数原型
    * （它们由生成规则自洽，见 `DPIC`）。消费方是 `difftrace.cpp` 的头部校验。
    */
  private def schemaFingerprint(target: DifftestTarget, numCores: Int,
                                declarations: Seq[String], members: Seq[String]): String =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest((target.macros + numCores + declarations.mkString + members.mkString)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .take(8).map(b => f"${b & 0xff}%02x").mkString

  /** `difftest-schema.json` 的顶层结构，`probes` 与 `diffstate.h` 描述同一份布局。 */
  private def schemaJson(groups: Seq[(String, Seq[DifftestBundle])], numCores: Int,
                         cpuName: String, target: DifftestTarget): String = {
    val probeSchema = groups.map { case (name, copies) =>
      val gen = copies.head
      val slots = if (gen.isFlatten) gen.numFlattenElements else copies.size / numCores
      val fields = gen.stateFields.map { f =>
        s"""{"name":"${f.name}","bits":${f.width},"storage_bits":${f.storage},"count":${f.values.size}}"""
      }.mkString(",")
      s"""{"type":"$name","member":"${gen.desiredCppName}","slots":$slots,"fields":[$fields]}"""
    }.mkString(",\n    ")
    s"""{
  "schema_version":1,"isa":"${target.isa}","xlen":${target.xlen},"flen":${target.flen},
  "paddr_bits":${target.paddrBits},"vaddr_bits":${target.vaddrBits},
  "ref_abi":"${target.refAbi}","cpu":"$cpuName","num_cores":$numCores,
  "probes":[
    $probeSchema
  ]
}
"""
  }

  private def write(directory: String, name: String, content: String): Unit = {
    val dir = Paths.get(directory)
    Files.createDirectories(dir)
    Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8))
  }
}

/** 在探针前插入 `cycles` 拍寄存器延迟，每级复位为零，用来对齐不同流水级的观测点。 */
object Delayer {
  def apply[T <: Data](gen: T, cycles: Int): T = {
    require(cycles >= 0)
    (0 until cycles).foldLeft(gen) { (data, _) => RegNext(data) init(data.getZero) }
  }
}

// 下面三个 Bundle 供通用顶层（如 DifftestMain）与平台对接；本分支没有 Scala 消费者，
// 端口的名字与位宽要和 Verilog 顶层一致。
/** 性能计数控制：`clean` 清零、`dump` 输出一次快照。 */
class PerfInfoIO extends Bundle {
  val clean = in Bool()
  val dump = in Bool()
}

/** 日志窗口：只记录 `log_begin`..`log_end` 之间的指令，`log_level` 选详细程度。 */
class LogCtrlIO extends Bundle {
  val log_begin = in UInt(64 bits)
  val log_end = in UInt(64 bits)
  val log_level = in UInt(64 bits)
}

/** 串口：`out` 是 DUT 输出字节，`in` 是注入给 DUT 的输入字节。 */
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
