package difftest

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import scala.sys.process._

/** Checks the generated full RV64GC interface against the C++ reference ABI. */
class Rv64GcSpec extends AnyFunSuite {
  test("RV64GC schema carries FCSR and matches the checked NEMU wire layout") {
    val directory = Paths.get("target", "rv64gc-schema").toAbsolutePath
    Files.createDirectories(directory)
    SpinalConfig(targetDirectory = directory.toString).generateSystemVerilog(new Component {
      DifftestModule(DiffArchEvent(64), dontCare = true)
      for (_ <- 0 until 2) DifftestModule(DiffInstrCommit(xlen = 64), dontCare = true)
      DifftestModule(DiffArchIntRegState(64), dontCare = true)
      DifftestModule(DiffArchFpRegState(64), dontCare = true)
      DifftestModule(DiffCSRState(64), dontCare = true)
      DifftestModule(DiffFCSRState(), dontCare = true)
      DifftestModule(DiffTrapEvent(64), dontCare = true)
      DifftestModule(DiffStoreEvent(64, 32, 64), dontCare = true)
      DifftestModule(DiffLrScEvent(), dontCare = true)
      DifftestModule.finish("Windrise", target = DifftestTarget.VexiiRV64GC())
    })
    val generated = directory.resolve("generated-src")
    val schema = new String(Files.readAllBytes(generated.resolve("difftest-schema.json")), UTF_8)
    assert(schema.contains("\"ref_abi\":\"nemu-rv64gc\""))
    assert(schema.contains("DifftestFCSRState"))
    val source = directory.resolve("layout.cpp")
    Files.write(source, ("#include \"ref-state.h\"\n" +
      "static_assert(default_ref_backend == RefBackend::NemuRV64GC);\n" +
      "#ifdef DIFFTEST_CHECK_INITIAL_STATE\n#error Integer-only checks enabled\n#endif\n" +
      "int main() { return sizeof(RefState) != 672; }\n").getBytes(UTF_8))
    val includes = Seq(generated, Paths.get("config").toAbsolutePath,
      Paths.get("src/test/csrc/common").toAbsolutePath,
      Paths.get("src/test/csrc/difftest").toAbsolutePath).map(p => s"-I$p")
    val executable = directory.resolve("layout")
    assert((Seq("g++", "-std=c++17") ++ includes ++ Seq(source.toString, "-o", executable.toString)).! == 0)
    assert(Seq(executable.toString).! == 0)
  }

  test("full RV64GC ABI rejects missing floating-point CSR state") {
    val directory = Files.createTempDirectory(Paths.get("target"), "rv64gc-invalid-")
    val error = intercept[Exception] {
      SpinalConfig(targetDirectory = directory.toString).generateSystemVerilog(new Component {
        DifftestModule(DiffArchEvent(64), dontCare = true)
        DifftestModule(DiffArchFpRegState(64), dontCare = true)
        DifftestModule(DiffCSRState(64), dontCare = true)
        DifftestModule.finish("Windrise", target = DifftestTarget.VexiiRV64GC())
      })
    }
    assert(error.getMessage.contains("floating-point CSR snapshots"))
  }
}
