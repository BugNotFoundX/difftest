package difftest

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.sys.process._

class MigrationSpec extends AnyFunSuite {
  private def directory(name: String): Path = {
    val root = Paths.get("target", "migration-tests").toAbsolutePath
    Files.createDirectories(root)
    Files.createTempDirectory(root, name)
  }
  private def read(path: Path): String = new String(Files.readAllBytes(path), UTF_8)
  private def write(path: Path, text: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(UTF_8))
  }
  private def command(args: Seq[String], dir: Path): Unit = {
    val log = new StringBuilder
    val status = Process(args, dir.toFile).!(ProcessLogger(
      line => log.append(line).append('\n'), line => log.append(line).append('\n')))
    write(dir.resolve("commands.log"), readLog(dir) + args.mkString(" ") + "\n" + log.toString)
    assert(status == 0, args.mkString(" ") + "\n" + log.toString.takeRight(16000))
  }
  private def readLog(dir: Path): String =
    if (Files.exists(dir.resolve("commands.log"))) read(dir.resolve("commands.log")) else ""
  private def config(dir: Path): SpinalConfig = SpinalConfig(
    targetDirectory = dir.toString,
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))

  private def verilate[T <: Component](report: SpinalReport[T], dir: Path, top: String,
                                      resource: String, withDpi: Boolean): Unit = {
    val source = Paths.get("src", "test", "resources", resource).toAbsolutePath
    val generated = dir.resolve("generated-src")
    val flags = if (withDpi) Seq(
      "-CFLAGS", s"-I$generated -I$dir", generated.resolve("difftest-dpic.cpp").toString) else Seq.empty
    command(Seq("verilator", "--cc", "--exe", "--build", "-j", "2", "--assert", "--no-timing",
      "-DDIFFTEST", "--top-module", top, "--Mdir", dir.resolve("obj").toString) ++
      report.rtlSourcesPaths.toSeq ++ Seq(source.toString) ++ flags, dir)
    command(Seq(dir.resolve("obj").resolve("V" + top).toString), dir)
  }

  test("RV32/RV64 full interfaces are deterministic and elaborations do not share state") {
    val headers = Seq((32, 64, 256), (64, 64, 256), (32, 64, 256), (64, 32, 64)).map {
      case (xlen, flen, vlen) =>
      val dir = directory(s"full-rv$xlen-")
      val report = DifftestMain.generate(DifftestMain.Options(
        targetDir = dir.toString, xlen = xlen, flen = flen, vlen = vlen, numPhyRegs = 65, numCores = 2))
      command(Seq("verilator", "--lint-only", "--no-timing", "-DDIFFTEST", "--top-module", "DifftestTop") ++
        report.rtlSourcesPaths.toSeq, dir)
      val header = read(dir.resolve("generated-src/diffstate.h"))
      assert(header.contains("#define NUM_CORES 2"))
      assert(header.contains(s"uint${xlen}_t value[32]"))
      assert(header.contains(s"uint64_t value[${32 * vlen / 64}]"))
      assert(header.contains(s"uint${flen}_t value[32]"))
      assert(header.contains("uint64_t cycleCnt"))
      assert(header.contains("#define CONFIG_DIFF_WB_INT_WIDTH 65"))
      Seq("diffstate.h", "difftest-dpic.h", "difftest-dpic.cpp")
        .map(f => read(dir.resolve("generated-src").resolve(f)))
    }
    assert(headers(0) == headers(2))
    assert(headers(0) != headers(1))
  }

  for (xlen <- Seq(32, 64)) {
    test(s"RV$xlen DPI transports high bits, indexed/flattened arrays, reset and delayed valid") {
      val dir = directory(s"transport-rv$xlen-")
      val report = config(dir).generateSystemVerilog(new TransportTop(xlen))
      write(dir.resolve("difftest.h"),
        "#pragma once\n#include \"diffstate.h\"\n" +
          "struct TestDifftest { DiffTestState dut; };\nextern TestDifftest **difftest;\n")
      verilate(report, dir, "TransportTop", "transport.cpp", withDpi = true)
    }
  }

  test("single and dual port RAM helpers preserve byte masks, word indexing, reset and read hold") {
    val dir = directory("memory-")
    verilate(config(dir).generateSystemVerilog(new MemoryTop), dir, "MemoryTop", "memory.cpp", withDpi = false)
  }

  test("LA32R schema retains generated DPI without claiming a runnable reference backend") {
    val dir = directory("la32r-")
    val report = config(dir).generateSystemVerilog(new TransportTop(32, "la32r"))
    val header = read(dir.resolve("generated-src/diffstate.h"))
    assert(header.contains("#define DIFFTEST_ISA_LA32R 1"))
    assert(header.contains("#define DIFFTEST_REF_ABI_NONE 1"))
    write(dir.resolve("difftest.h"),
      "#pragma once\n#include \"diffstate.h\"\n" +
        "struct TestDifftest { DiffTestState dut; };\nextern TestDifftest **difftest;\n")
    verilate(report, dir, "TransportTop", "transport.cpp", withDpi = true)
  }

  test("invalid widths and incompatible same-name probe layouts fail before producing interfaces") {
    intercept[IllegalArgumentException] { Widths.checkXlen(16) }
    intercept[IllegalArgumentException] { Widths.storageBits(65) }
    val error = intercept[Exception] {
      config(directory("mixed-width-")).generateSystemVerilog(new Component {
        DifftestModule(DiffArchEvent(32), dontCare = true)
        DifftestModule(DiffInstrCommit(xlen = 32), dontCare = true)
        DifftestModule(DiffInstrCommit(xlen = 64), dontCare = true)
        DifftestModule.finish("MIXED")
      })
    }
    assert(error.getMessage.contains("Incompatible probe layouts"))
  }
}
