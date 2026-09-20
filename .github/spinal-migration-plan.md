# Scala / SpinalHDL 迁移

基线：`windrise` / `bf9586cb`。本次先迁移子模块 Scala 和构建入口；不改正在开发的 CPU、主仓库旧探针或 NEMU，不提前实施软件检查器的 RV32 移植。

1. 将 Bundle、DPI BlackBox、接口生成器、示例顶层和内存 helper 改为 SpinalHDL。保留 DPI 函数名及 C++ 字段约定，生成上下文按 elaboration 隔离。
2. 为架构状态加入 `xlen=32/64`，浮点、向量、缓存行和队列索引采用独立参数；默认保留历史 RV64 接口，示例入口可选择 RV32。
3. 使用与主仓库一致的 Scala 2.13.18、SpinalHDL 1.15.0、sbt 1.11.7，替换 Mill 构建、Makefile 生成入口、使用文档和 CI。
4. 执行 `sbt compile test`，覆盖两种位宽、重复生成、索引/数组布局、延迟及复位；对生成的 SV 使用 Verilator，核对其 DPI 原型并执行 C++ 接收端测试。

验收命令（在子模块内）：

```bash
sbt compile test
sbt "runMain difftest.DifftestMain --xlen 32 --target-dir build/rv32"
sbt "runMain difftest.DifftestMain --xlen 64 --target-dir build/rv64"
make difftest_verilog NOOP_HOME="$PWD" BUILD_DIR="$PWD/build/make-rv32" XLEN=32
git diff --check
```

验收范围为 Scala/RTL/DPI 生成链；生成 RV32 接口不代表历史 C++ 检查器和 NEMU 已完成 RV32 适配。
