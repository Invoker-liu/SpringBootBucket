# springboot4-native

Spring Boot 4 教程第 26 篇配套工程：AOT 处理与 GraalVM Native Image（启动时间与内存对比）。

工程是一个紧凑的订单查询 REST 服务，核心点位：

- 三个接口：`GET /api/orders/{id}`、`GET /api/orders`、`GET /api/orders/stats`
- `ShopProperties`（`@ConfigurationProperties(prefix = "shop")`）：典型的反射点位，
  AOT 处理会为它生成 BeanDefinition 代码并写入 reflect hints
- `pom.xml` 中显式绑定 `spring-boot-maven-plugin` 的 `process-aot` goal，
  `mvn package` 时 AOT 产物随包打进 fat jar

## 运行

```bash
# 端口 18260（环境变量 SERVER_PORT 可覆盖）
mvn spring-boot:run

# 打包（含 process-aot）
mvn package

# 普通 JVM 模式
java -jar target/springboot4-native-1.0.0.jar

# AOT 模式：走构建期生成的 ApplicationContextInitializer 刷新上下文
java -Dspring.aot.enabled=true -jar target/springboot4-native-1.0.0.jar

# CDS 模式：先解包再两步法（extracted 布局下 CDS 才生效）
java -Djarmode=tools -jar target/springboot4-native-1.0.0.jar extract --destination extracted
java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -jar extracted/springboot4-native-1.0.0.jar
java -Xshare:on -XX:SharedArchiveFile=app.jsa -jar extracted/springboot4-native-1.0.0.jar
```

## 实测口径与三组对比数据（JDK 21.0.10，Windows 11，2026-09-20）

口径：boot 自报取日志 `Started NativeDemoApplication in X seconds` 的中位数；
内存取 `/actuator/metrics/jvm.memory.used`（heap used）就绪后取值；
类数取 `jcmd <pid> VM.class_hierarchy` 行数；每组连续启动 3 次。
完整取值单在仓库 `.workbuddy/verify-native/取值单.txt`，验证脚本 `.workbuddy/verify-native.sh`。

| 方式 | boot 自报启动(中位) | wall 首请求 200(中位) | 已加载类数 | heap used(中位) |
|---|---|---|---|---|
| 普通 JVM（fat jar） | 2.034s | 3157ms | 8796 | 96.1MB |
| CDS（extract + 两步法 + -Xshare:on） | 1.791s | 2624ms | 8529 | 87.0MB |
| AOT 模式（spring.aot.enabled=true） | 1.911s | 3204ms | 8722 | 77.6MB |

AOT 模式下 `VM.class_hierarchy` 中出现 371 行 `__BeanDefinitions` /
`__ApplicationContextInitializer` 等 AOT 生成类，普通模式为 0，这是上下文走了
AOT 路径的直接证据。三组接口行为一致（`/api/orders/stats` 均返回
`shop=xncoding-shop, vipDiscount=0.88, bulkThreshold=9999, bulkOrders=1`），
AOT 对 `@ConfigurationProperties` 反射绑定的处理正确。

## native-image 编译的环境前提（本机未实测，机制取证自官方文档）

GraalVM Native Image 编译需要 **GraalVM 发行版 JDK**（当前 LTS 线为 GraalVM 25.0，
另有 GraalVM for JDK 21 LTS 可对齐本系列），且：

- Windows：Visual Studio 2022 MSVC（x64 Native Tools 命令行）或 WSL2
- Linux：gcc/ld
- 或走 buildpacks：`mvn spring-boot:build-image -Pnative`（需要 Docker/Podman）

命令：`mvn -Pnative native:compile`（starter parent 4.1.1 已内置 native profile，
内含 process-aot + native-maven-plugin）。

本机侦察结论（2026-09-20）：`where cl.exe` 无结果、无 Visual Studio 目录、
无 GraalVM 目录、`GRAALVM_HOME` 未设置、无 Docker（可用 Docker 在树莓派 ARM 机器上），
native 编译在本机不可行，因此本文 native 部分只做机制取证，不做实测，不编造数据。

## 测试

```bash
mvn test
```

6 个测试：3 接口的端到端断言（含 404 分支）、`ShopProperties` 绑定断言、核心 Bean 存在性断言。

---
作者：Xiong Neng
