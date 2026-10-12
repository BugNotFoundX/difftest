# Windrise micro 使用官方 Emulator

构建和运行见 [主仓库说明](../doc/micro-port.md#装配与验证入口) 和
[测试目标说明](../scripts/README.md)。生产入口为本仓库已有的
`Makefile → verilator.mk → common/main.cpp → Emulator`，没有第二个 main/checker。

接入边界：

- 主仓库 `SimTop.sv` 满足官方顶层端口，并使用官方 RAMHelper/SimMemory。
- DifftestPlugin 通过 pull 采样；Scala 自动生成匹配 RV32/RV64 的 DPI 和状态布局。
- RefState 明确隔离 C++ 对象布局和 REF wire。标准 NEMU v2 与历史 RV64 三参数接口分别适配。
- 单 hart 整数 profile 的独立初态、禁止 skip 和 store 数量检查是平台策略，不由 ABI 名称隐式启用。
- 已退休 store 延迟一拍，直接复用原 `do_store_check()` 的检查顺序。
- DiffTrace 增加布局指纹和错误输入检查；异常返回官方 Emulator 的失败状态。
- `RTL_FILES/BUILD_DIR/PLATFORM_VFLAGS` 允许工程提供 RTL 和平台配置，保留官方默认值。

`sbt test` 验证 RV32/RV64/LA32R 的自动 DPI 传输、布局及内存辅助模块。
真实 CPU/AM 回归从主仓库执行 `make micro-test`。历史 RV32 emulator 的入口功能回归使用
`make legacy-micro-features LEGACY_EMU=/path/to/rv32/emu`。
当前整数 profile 不支持磁盘快照；官方历史 profile 的快照代码仍保留。
