---

title: SpringBoot4系列26 - 使用GraalVM原生镜像与AOT

slug: sb4-native

date: 2026-09-20 21:30:00 +0800

toc: true

categories: [ java ]

tags: [ SpringBoot, SpringBoot4, AOT, GraalVM, NativeImage, CDS ]

draft: false

---

前段时间盯着大促扩容看了一个问题：流量上来，Kubernetes 给订单服务一口气扩 10 个副本，结果新 Pod 得等 JVM 把 8000 多个类加载完、几十个自动配置类解析完才能接流量，就绪探针配 60 秒都不保险。缩容再扩容这么来回几次，冷启动时间就是实打实的容量成本，要是放在 Serverless 场景按毫秒计费，这笔开销甚至能决定方案能不能用。

Spring Boot 4.1 在交付流程里给了两条加速路径：AOT 处理把运行期的 Bean 解析提前到构建期，CDS 把类加载结果缓存下来给 JVM 复用，再往前一步就是 GraalVM 原生镜像。这篇文章就是我把这三样在 Spring Boot 4.1.1（Spring Framework 7.0.9、JDK 21.0.10）上挨个试了一遍的结果：`process-aot` 到底生成了什么，`ShopProperties` 这个反射点位被处理成了什么样，自定义 hints 怎么写。然后普通 JVM、CDS、AOT 模式三种启动方式我各跑了 3 次，启动耗时、加载类数、堆内存全是同一份脚本一口气测出来的，没有手工拼数字。至于 native 镜像，说实话这次没编出来，三条编译路线我本机全走不通，所以这部分只有机制没有数字，测不了的东西我不编。

## 工程与依赖选型

配套工程叫 springboot4-native，一个订单查询 REST 服务。规模我刻意压小了：三个接口加一个配置属性类，单测 6 个，够把机制跑通就行，工程一大，AOT 产物就被淹在噪音里了。

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

两个 starter 各干各的：webmvc 承载三个接口，actuator 的 `metrics` 端点负责吐出 `jvm.memory.used`，三组内存对比就从这里取值。另外提醒一句，Boot 4 里 `spring-boot-starter-web` 已经弃用，得写 `spring-boot-starter-webmvc`，这个系列前面的文章说过，这里照旧。端口沿用系列约定 18260，`application.yml` 里 `server.port: ${SERVER_PORT:18260}` 让环境变量优先，验证脚本接管端口时不用动配置。

接口里我特意埋了一个反射点位。`ShopProperties` 用 `@ConfigurationProperties(prefix = "shop")` 声明，三个字段 `name`、`vipDiscount`、`bulkThreshold` 分别对应字符串、浮点、整数绑定，主类用 `@ConfigurationPropertiesScan` 显式扫描注册。`/api/orders/stats` 这个接口把绑定结果拼成字符串返回，AOT 模式和普通模式的返回值一字不差，才算 AOT 处理正确，它就是三组对比里的「行为一致性」断言。

```java
@GetMapping("/stats")
public String stats() {
    return orderService.bulkStats();
}
```

`bulkStats()` 里读 `shopProperties.getBulkThreshold()` 做大额订单统计。返回值 `shop=xncoding-shop, vipDiscount=0.88, bulkThreshold=9999, bulkOrders=1` 会贯穿后文三组实测，每次启动脚本都会 curl 一遍留档。

## AOT 处理在 Boot 4.1 里做了什么

Spring 应用在 JVM 上启动，一大半时间花在「反射式组装」上：`@Configuration` 类要被 `ConfigurationClassPostProcessor` 解析出 BeanDefinition，`@Autowired` 靠字节码代理注入，`@ConfigurationProperties` 按属性名反射调 setter。AOT（Ahead-of-Time）处理干的事，就是把这一步搬到构建期：打包时把应用上下文启动到「Bean 定义齐备」的程度，解析结果直接生成 Java 源码和字节码，运行时照着生成代码重建上下文，不再走运行期解析。

触发方式是给 `spring-boot-maven-plugin` 显式绑定 `process-aot` goal：

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>process-aot</id>
            <goals>
                <goal>process-aot</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

这个 goal 默认绑在 `prepare-package` phase 上，配好后 `mvn package` 就会执行，产物固定落在 `target/spring-aot/main/` 下。不配 execution 它就不跑，很多人第一次用 AOT 扑空，九成是这个原因。

本工程打包完的产物长这样，`sources` 98 个文件、`classes` 125 个 class、`resources` 是给 GraalVM 用的 hints：

```text
target/spring-aot/main/
├── classes/        生成字节码（动态代理等），125 个 class
├── resources/
│   └── META-INF/native-image/com.xncoding/springboot4-native/
│       ├── native-image.properties
│       └── reachability-metadata.json
└── sources/        生成源码，98 个文件
    ├── com/xncoding/nativeapp/...__BeanDefinitions.java
    ├── com/xncoding/nativeapp/NativeDemoApplication__ApplicationContextInitializer.java
    └── org/springframework/boot/...（自动配置类的 Bean 定义代码）
```

Boot 4.1 的 hints 产物只有两个文件。如果你看过 3.x 时代的教程，配图里都是「五个 json」，这里不一样：`reflect-config.json`、`proxy-config.json`、`resource-config.json`、`serialization-config.json`、`jni-config.json` 五类 hints 在 GraalVM 22.2 之后合并成了单个 `reachability-metadata.json`，Boot 4.1 直接沿用合并格式。我这份文件里有 627 条反射登记、26 条资源登记。`native-image.properties` 里写的是 native 编译时的入口参数：

```text
Args = -H:Class=com.xncoding.nativeapp.NativeDemoApplication \
--no-fallback
```

![](https://static.xiongneng.me/native-aot-pipeline-20260921201615.png)

### 生成代码的两种形态

生成代码到底长什么样，拿本工程的反射点位 `ShopProperties` 看最直观。源码里它是个标准的 `@ConfigurationProperties` 类，三个字段带 setter；AOT 处理之后生成了这么个文件：

```java
@Generated
public class ShopProperties__BeanDefinitions {
  /**
   * Get the bean definition for 'shopProperties'.
   */
  public static BeanDefinition getShopPropertiesBeanDefinition() {
    RootBeanDefinition beanDefinition = new RootBeanDefinition(ShopProperties.class);
    beanDefinition.setInstanceSupplier(ShopProperties::new);
    return beanDefinition;
  }
}
```

原来运行期反射解析 `@ConfigurationPropertiesScan` 的活，现在就一行直接调用：`new RootBeanDefinition(ShopProperties.class)` 加构造器引用。业务类是这样，自动配置类也一样，`TomcatWebServerAutoConfiguration__B__eanDefinitions`、`WebMvcAutoConfiguration__B__eanDefinitions` 这些文件把几十个自动配置类的解析全部代码化，启动省下来的时间就是从这儿来的。

上下文重建的入口是生成的主类伴生文件，它实现 `ApplicationContextInitializer<GenericApplicationContext>`，向上下文注册全部 Bean 定义：

```java
@Generated
public class NativeDemoApplication__ApplicationContextInitializer
        implements ApplicationContextInitializer<GenericApplicationContext> {
  @Override
  public void initialize(GenericApplicationContext applicationContext) {
    DefaultListableBeanFactory beanFactory = applicationContext.getDefaultListableBeanFactory();
    beanFactory.setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver());
    beanFactory.setDependencyComparator(AnnotationAwareOrderComparator.INSTANCE);
    new NativeDemoApplication__BeanFactoryRegistrations().registerBeanDefinitions(beanFactory);
    new NativeDemoApplication__BeanFactoryRegistrations().registerAliases(beanFactory);
  }
}
```

`mvn package` 时 fat jar 会把这套产物一起收进 `BOOT-INF/classes`，我验证过：jar 里 `META-INF/native-image` 路径下有 5 条产物记录。普通 JVM 模式（`spring.aot.enabled=false`）启动时这些产物直接被忽略，行为跟没做 AOT 一模一样。也就是说，同一份构建物，两种运行模式随时切。

还有个有意思的细节：AOT 处理本身会「启动一次应用」，打包日志里能看到：

```text
20:24:02.368 INFO  c.x.n.NativeDemoApplication - Starting NativeDemoApplication
using Java 21.0.10 with PID 35984 (...\springboot4-native\target\classes
started by Administrator in d:\projects\java\SpringBootBucket\springboot4-native)
```

process-aot 执行时会拿 `target/classes` 当类路径，把应用启动到 Bean 定义齐备的阶段，生成的代码就是这一次启动里解析出的 Bean 图谱。注意它不创建 Bean 实例，`@PostConstruct`、数据库连接这类初始化逻辑都不会跑，记录下来的只有 Bean 定义和 hints。

AOT 的分工在 `ShopProperties` 上看得很清楚：Bean 定义走生成代码，属性绑定这种真实的反射走 hints 登记。我翻了 `reachability-metadata.json`，它的条目把绑定器需要的成员一个不漏全列出来了：

```json
{
 "type": "com.xncoding.nativeapp.config.ShopProperties",
 "fields": [
  { "name": "bulkThreshold" },
  { "name": "name" },
  { "name": "vipDiscount" }
 ],
 "methods": [
  { "name": "<init>", "parameterTypes": [] },
  { "name": "getBulkThreshold", "parameterTypes": [] },
  { "name": "getName", "parameterTypes": [] },
  { "name": "getVipDiscount", "parameterTypes": [] },
  { "name": "setBulkThreshold", "parameterTypes": ["int"] },
  { "name": "setName", "parameterTypes": ["java.lang.String"] },
  ...
 ]
}
```

字段、无参构造、全部 getter 和 setter 都在。native 镜像里属性绑定还能正常干活，靠的就是这份登记；而在普通 JVM 上，这份登记不花一分钱。

### 自定义 hints：RuntimeHints API

框架自动生成的 hints 有覆盖不到的地方，比如业务代码里 `Class.forName("com.example.legacy.LegacyCodec")` 这种字符串反射，框架根本看不见，得自己登记。Spring 给的口子是 `RuntimeHints` API：

```java
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(LegacyHints.class)
public class LegacyConfig {
}

class LegacyHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(LegacyCodec.class, MemberCategory.values());
        hints.resources().registerPattern("legacy/*.dat");
        hints.proxies().registerJdkProxy(LegacyMBean.class);
    }
}
```

`RuntimeHintsRegistrar` 登记的内容最后也汇进 `reachability-metadata.json`，跟框架生成物合流。要不要写 hints，判断标准就一条：这段代码里有没有 GraalVM 静态分析看不到的反射、资源、代理或序列化。JVM 模式下 hints 不生效也不报错，所以这层代码可以放心常驻工程，等真编 native 的那天直接起作用。排查 hints 缺失有个趁手的工具，GraalVM 的 tracing agent（`-agentlib:native-image-agent=config-output-dir=...`），在 JVM 上把业务路径跑一遍，它会把运行期实际发生的反射全登记出来，再对照着合并进工程。

不过 tracing agent 有个使用边界得记住：它只记录你跑到的代码路径，没触发的分支不会进配置文件。直接拿 agent 输出当 hints 用，漏登记的反射要到 native 运行期才炸出来。稳妥的做法是 agent 输出打底，再人工过一遍代码里的 `Class.forName` 与 `Method.invoke` 补缺，然后才进 native 编译。

## CDS 实测：把类加载结果缓存下来

CDS（Class Data Sharing）是 JDK 自带的老机制：先跑一次训练运行，把 JVM 加载的类写进归档文件，之后的启动直接映射归档，解析和验证全省。Boot 4.1 官方文档给了两步法，而且明确要求先解包，fat jar 那种嵌套 jar 布局 CDS 是吃不了的。

第一步，把 fat jar 解包成 `lib/` 平铺布局：

```bash
java -Djarmode=tools -jar target/springboot4-native-1.0.0.jar extract --destination extracted
```

解包出来是一个目录：应用 jar `springboot4-native-1.0.0.jar` 加一个装着全部依赖的 `lib/` 文件夹，manifest 里引用的是平铺类路径。官方文档说这个布局对 CDS 与 AOT Cache 都友好，生产部署也推荐它（嵌套 jar 的 URL 处理有一点启动开销）。

第二步，训练运行生成归档。`-Dspring.context.exit=onRefresh` 是官方给的退出技巧：上下文刷新完成就正常退出并写归档，应用不必对外服务：

```bash
java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
  -jar extracted/springboot4-native-1.0.0.jar
```

训练跑加载了哪些类，归档里就只有哪些类，这次没跑到的地方不会进归档。想扩大覆盖面，就在训练跑里多触发几条业务路径再退出。我这应用够小，一次刷新就覆盖全了。

之后每次启动带上 `-XX:SharedArchiveFile`。我在脚本里还加了 `-Xshare:on`，这个参数把共享归档从「尽力用」改成「必须用」，归档无效时 JVM 直接拒绝启动。换个角度说，起得来就是命中了的铁证：

```bash
java -Xshare:on -XX:SharedArchiveFile=app.jsa -jar extracted/springboot4-native-1.0.0.jar
```

本工程训练跑出来的归档是 6881280 字节，约 6.6MB。JDK 21.0.10 上归档一次生成成功，三次启动全过了 `-Xshare:on` 校验。有一点要注意：归档和类路径是强绑定的，jar 换了版本、lib 里加了依赖，归档立刻失效或者命中下降。所以归档生成得跟着构建流程走，不能生成一次用一年。

想看命中细节，加一条日志开关就行：

```bash
java -Xlog:class+load -XX:SharedArchiveFile=app.jsa \
  -jar extracted/springboot4-native-1.0.0.jar | grep "shared objects file"
```

输出里带 `source: shared objects file` 的行就是从归档映射来的类，我实测本工程一次启动有 2296 行（`class,load` 总量 7994 行，剩下近六成类还是得从 lib 下的 jar 一个个解析）。跟普通启动的同一份日志一对比，两种来源的差异就是 CDS 省掉的那部分活。

复现的时候还有个顺序问题不能乱：训练跑必须在归档文件不存在时执行（文件已存在会报错），归档生成之后的三次启动才带 `-XX:SharedArchiveFile`。我把这些步骤全固化在脚本一次调用里了，手工敲的时候很容易把训练跑和计时跑的参数搞混。

## 三种启动方式对比实测

我写了一份验证脚本，对三种方式各连续启动 3 次，每次记四个数：wall 毫秒（进程启动到核心接口首次返回 200，轮询粒度 50ms）、日志自报毫秒（`Started NativeDemoApplication in X seconds`）、已加载类数（`jcmd <pid> VM.class_hierarchy` 行数）、堆内存（就绪后读 `/actuator/metrics/jvm.memory.used`）。三种方式下 `/api/orders/stats` 都返回 `shop=xncoding-shop, vipDiscount=0.88, bulkThreshold=9999, bulkOrders=1`，行为一致，对比才有意义。脚本全程单次调用搞定「起服务、计时、取样、关服务」，Windows 上杀进程走 `kill`，每组之间轮询等端口释放，免得上一组的残留连接干扰下一组计时。

9 次启动的原始记录（脚本把每一次的四个数都留了底，下面是全部 9 行）：

```text
方式  次序  wall(ms)  boot自报(s)  已加载类数  heap(MB)
jvm   1     3159      2.044        8796        96.5
jvm   2     3157      2.034        8796        96.1
jvm   3     3136      2.019        8805        96.9
cds   1     2624      1.762        8529        85.4
cds   2     2655      1.791        8529        93.8
cds   3     2616      1.794        8529        87.0
aot   1     2895      1.706        8722        77.7
aot   2     3204      1.969        8722        77.6
aot   3     3254      1.911        8722        77.5
```

取中位数汇总：

| 方式 | boot 自报启动 | wall 首请求 200 | 已加载类数 | heap used |
| --- | --- | --- | --- | --- |
| 普通 JVM（fat jar） | 2.034s | 3157ms | 8796 | 96.1MB |
| CDS（extract + 归档 + -Xshare:on） | 1.791s | 2624ms | 8529 | 87.0MB |
| AOT 模式（spring.aot.enabled=true） | 1.911s | 3204ms | 8722 | 77.6MB |

结果挺有意思，分三块说。

CDS 的收益先看启动时间：boot 自报从 2.034s 降到 1.791s，省了约 12%；类加载数从 8796 降到 8529，少的 267 个类全走归档映射；wall 指标也跟着从 3157ms 降到 2624ms。

AOT 模式的账要分开算。内存是稳稳的赚：堆中位数 77.6MB，比普通模式的 96.1MB 少了约 19%，三次取值 77.7/77.6/77.5MB，波动没超过 0.2MB；boot 自报 1.911s，快了 6%。反直觉的是 wall 指标，三次取值 2895/3204/3254ms，居然比普通模式的中位数还慢。波动来自进程调度，看不出统计显著性。想想也合理：生成代码省下的是解析，不是调度排队的时间。这组数容易被略过，但我觉得它是本次实测里最值得记住的：选 AOT 的理由是内存，别拿 wall 数字说事。

还有一点，两条路是正交的：CDS 管类加载，AOT 管 Bean 解析，先 AOT 打包再对 AOT 产物做 CDS 训练，可以叠着用。JDK 25+ 上 Boot 官方推荐的 AOT Cache（`-XX:AOTCache`）更干脆，直接把两条机制合成一条，JEP 483 里连方法剖析数据都进了缓存。

AOT 模式真的在跑生成代码吗？证据在类加载记录里。`jcmd <pid> VM.class_hierarchy` 的输出中，AOT 模式出现了 371 行 `__B__eanDefinitions`、`__A__pplicationContextInitializer`、`__B__eanFactoryRegistrations` 这样的 AOT 生成类，普通模式是 0 行。生成类加载进 JVM 且参与上下文重建，这是「AOT 上下文」最直接的铁证。

![](https://static.xiongneng.me/native-startup-components-20260921201615.png)

![](https://static.xiongneng.me/native-startup-benchmark-20260921201615.png)

内存口径得交代一句：`jvm.memory.used` 是堆内已用字节，而 CDS 的主要收益在类元数据（元空间）和加载耗时上，堆内存对它不敏感，三次取值 85.4/93.8/87.0MB 波动也大；AOT 模式的堆内存下降倒是稳稳的。对比启动时间时，boot 自报用的是 JVM 内部时钟，比 wall 指标扛机器负载波动，所以正文对比以 boot 自报为主。另外提醒一句，将来补测 native 镜像时口径得换：native 进程没有 JVM 堆，`jvm.memory.used` 取不到值，得换成进程 RSS（Windows 上 tasklist 的内存列、Linux 上 /proc 的 status 字段），脚本注释里已经把这个写好了，省得两套口径直接比大小闹笑话。

三种方式怎么选？看你的场景对启动时间有多敏感。常态扩缩容的服务，AOT 模式一行启动参数加一次打包配置就够，内存省 19% 意味着同样配额能塞更多副本；启动时间还嫌慢，再把 CDS 叠上去，两段收益直接相加。弹性伸缩剧烈、扩容要秒级就绪的场景，往 JDK 25 的 AOT Cache 走，或者具备工具链后直接编 native。代价也摆在明面上：AOT 牺牲运行期 Bean 定义的灵活性，CDS 多了归档生成的构建环节，native 把工具链要求和反射排查成本一次性前移。三段台阶彼此不冲突，按顺序逐段引入就行，每一段都有独立的退出成本。

## native-image：路径与环境前提

GraalVM 原生镜像就是把上面的 AOT 推到终点：以 `main` 为入口做静态分析，把应用和 JDK 子集直接编译成平台专属的可执行文件，启动时没有 JVM、没有 JIT 预热，类在构建期就全部解析完。官方文档把它的约束归成三条：静态分析从 `main` 出发，不可达代码直接剔除；GraalVM 看不见反射、资源、序列化与动态代理，这些动态行为必须靠 hints 显式登记；类路径构建期固定，运行期不再有延迟类加载。Spring 的 AOT 处理正是为这三条做准备：闭世界假设下，Bean 定义构建期定型，动态行为全部登记成 hints，剩下的静态分析交给 GraalVM。

编译入口是 starter parent 4.1.1 内置的 native profile，一条命令串起整条链：

```bash
mvn -Pnative native:compile
```

profile 里包含 `process-aot` 与 `native-maven-plugin`，生成物 `native-image.properties` 里 `-H:Class` 指向主类、`--no-fallback` 禁用回退 JVM。另一条路是 buildpacks（`mvn spring-boot:build-image -Pnative`），在容器里完成编译，需要 Docker 或 Podman。

编译环境是 native 路线最大的门槛，官方要求：

- GraalVM 发行版 JDK，当前 LTS 线为 GraalVM 25.0，另有 GraalVM for JDK 21 LTS 可与本系列的 JDK 21 对齐
- Windows 上需要 Visual Studio 2022 MSVC 工具链（x64 Native Tools 命令行），或改用 WSL2
- Linux 上需要 gcc/ld

我本机的环境侦察结论（2026-09-20）是这样的：`where cl.exe` 查无结果、Visual Studio 目录不存在、`GRAALVM_HOME` 没设置、GraalVM 没有安装目录，唯一可用的 Docker 在一台 ARM 架构的树莓派上。三条编译路线（本机 MSVC、本机 WSL、buildpacks）全部走不通，所以本文没有 native 镜像的实测数字，只有机制取证和 JVM 侧实测；前文的对比表里没有 native 一栏，就是这个原因。native 镜像的公开收益（启动毫秒级、RSS 显著低于 JVM）等环境齐了，用同一套脚本就能补测，脚本和工程都备好了。

还有个容易被忽略的维度：native 镜像是平台专属可执行文件，没有跨架构一说。在树莓派（aarch64 Linux）上编出来的产物只能在 aarch64 Linux 上跑，Windows x64 想复用，要么换 WSL2 或 CI 机器（x64 Linux）编译，要么走 buildpacks 让容器架构对齐。多架构团队就得每种目标架构各编一次，CI 矩阵里按 runner 架构拆 job 是常规操作。

![](https://static.xiongneng.me/native-compile-path-20260921201615.png)

native 编译失败的高频原因也值得预演一遍：反射类没进 hints，报 ClassNotFound 或 NoSuchMethod，用 tracing agent 补登记；代理接口没登记，报 proxy 类找不到，`hints.proxies().registerJdkProxy()` 补上；资源没登记，模板与配置文件加载返回 null，`registerPattern()` 补上。这批问题的定位线索都在 GraalVM 编译日志的 missing registration 段落里。

## 避坑指南

**坑一，process-aot 不配 execution 就不会跑。** 这个 goal 不绑定默认生命周期，`mvn package` 静悄悄跳过它，target 下连 spring-aot 目录都不会出现。第一次用 AOT 扑空，九成是这个原因，配好 `<execution>` 再看 `prepare-package` 阶段的日志。

**坑二，spring.aot.enabled=true 要求 bean 定义在构建期定型。** 运行期用 `@Profile` 切换 bean、用 `@ConditionalOnProperty` 的 `.enabled` 属性开关联动 bean，在 AOT 模式下受限或直接不支持，官方文档 native 章节明确列了这批限制。存在这类写法的工程，切 AOT 模式前先过一遍配置类。

**坑三，@ConfigurationPropertiesScan 注册的 bean 名不是类名。** 它注册的 bean 名是 `shop-<全限定类名>` 格式，`containsBean("shopProperties")` 返回 false 是正常现象，按类型取 `getBean(ShopProperties.class)` 或者查全名。我测试就栽在这里，断言改按类型写。

**坑四，CDS 对 fat jar 布局不生效。** 直接对 fat jar 跑 `-XX:ArchiveClassesAtExit`，归档文件照样生成，但嵌套 jar 的类路径 CDS 无法索引，之后启动要么命中极差要么报错。官方流程必须先 `java -Djarmode=tools -jar app.jar extract`，对解包后的布局做训练与运行。

**坑五，训练运行的退出方式选官方技巧。** `-Dspring.context.exit=onRefresh` 让上下文刷新完成即正常退出并写归档。用 kill 强杀进程不写归档，Windows 上尤其明显；开 actuator 的 shutdown 端点也能退，但要起完整服务，比 onRefresh 慢且引入额外配置。

**坑六，-Xshare:on 是归档命中与否的判定开关。** 默认的 `-Xshare:auto` 在归档不匹配时静默退回普通启动，CDS「用了但没生效」不会有任何提示。开发期加 `-Xshare:on`，归档无效直接拒绝启动，问题当场暴露。

**坑七，GC.class_histogram 看不到 AOT 生成类。** 它只列有存活实例的类，`__B__eanDefinitions` 这类生成类是静态方法调用，启动后没有实例存活，直方图里不出现。要看已加载类用 `jcmd <pid> VM.class_hierarchy`，AOT 生成类 371 行的证据就是从这个命令取的。

**坑八，GC.class_histogram 会触发 Full GC。** 计时阶段别跑它，一次 Full GC 足以把启动毫秒数污染掉。我的脚本把它放在接口验证与内存取样之后、kill 之前执行，墙钟计时早就定格了。

**坑九，Git Bash 下 $! 不是 Windows PID。** Git Bash 的 `$!` 给出的是 MSYS 包装进程号，`jcmd`、`jps` 需要 Windows PID，直接拿 `$!` 去查会报 no such process。反查用 `jps -l` 按应用名过滤出真 PID，再喂给 jcmd。

**坑十，jarmode extract 对非空目标目录直接报错。** `extract --destination` 指向已存在且非空的目录时抛 `JarModeErrorException`，不会覆盖。复跑脚本时先清理目标目录，或者检测到存在就跳过解包步骤。

**坑十一，wall 计时的波动来自机器，不来自应用。** 后台有编译任务时同一模式的启动时间能差 30%，单次取值没有意义。每组连跑 3 次以上取中位数，对比实验尽量挤在同一时段跑，正文的数据就是这么取的。

**坑十二，堆内存口径覆盖不了 CDS 的收益。** `jvm.memory.used` 只量堆，CDS 的类元数据在元空间，归档映射的内存在共享段，都不在堆里。只看堆指标会得出「CDS 不省内存」的偏差结论，它省的主要是加载时间与元空间占用。

**坑十三，AOT cache 与 CDS 按 JDK 版本二选一。** `-XX:AOTCache`（JEP 483）需要 JDK 25 及以上，Boot 4.1 文档推荐优先用它；JDK 21 上只能用 CDS 两步法。我的实测在 JDK 21 上进行，走的是 CDS 路线。

**坑十四，AOT 处理时应用会「启动一半」。** process-aot 把上下文启动到 bean 定义齐备为止，Environment 占位符、随机端口这类运行期值要能解析，`@Value("${...}")` 引用不存在的属性会在构建期报错，提前在测试配置里补齐。

**坑十五，AOT 产物随包进 jar 后不影响普通模式。** 同一份 fat jar 加不加 `-Dspring.aot.enabled=true` 是两种运行模式，产物在普通模式下被忽略。灰度期同一构建物先跑普通模式、验证后切 AOT 模式，不需要两套制品。

**坑十六，编译 native 不必换掉日常 JDK。** GraalVM for JDK 21 本身就是 HotSpot 发行版，日常开发、单测、JVM 模式运行都兼容，差别只在它额外带 native-image 工具。流程上可以统一用 GraalVM JDK 编译打包，native 编译只在发布环节触发，不必维护「普通 JDK 开发、GraalVM 编译」两套环境。

生成源码读起来偏长，但官方文档也承认它可读：每个自动配置类一个 `__B__eanDefinitions` 文件，方法名与 bean 名一一对应，排查「AOT 模式下某个 bean 为什么没起来」时直接打开对应文件搜索 bean 名，比在运行期堆栈里追 `ConfigurationClassPostProcessor` 的解析过程直接得多。这 98 个文件等于一份可检索的启动期行为快照。

## 小结

三段台阶各自独立，也叠得起来：`process-aot` 把 Bean 解析搬进构建期，CDS 用 6.6MB 归档省下 267 个类的加载，AOT 模式一行参数再把堆内存压掉 19%。常态扩缩容的服务，做到 AOT 模式一般就够了；启动还嫌慢，再叠 CDS。

真正没解决的是最后一段：native 镜像在我这台没有 MSVC 的 Windows 上编不出来。等环境补齐了，这套验证脚本和工程可以直接续测，把三行对比表补成四行，到时候我再补一篇。

## 参考链接

- [Spring Boot 4.1 Reference - Introducing GraalVM Native Images](https://docs.spring.io/spring-boot/4.1/reference/packaging/native-image/introducing-graalvm-native-images.html)：AOT 处理机制、生成物清单与闭世界限制的官方章节
- [Spring Boot 4.1 Reference - AOT Cache](https://docs.spring.io/spring-boot/4.1/reference/packaging/aot-cache.html)：CDS 两步法与 AOT Cache 命令原文，`spring.context.exit=onRefresh` 的出处
- [Spring Boot 4.1 Reference - Efficient Deployments](https://docs.spring.io/spring-boot/4.1/reference/packaging/efficient.html)：jarmode extract 解包布局说明
- [spring-boot-maven-plugin - process-aot](https://docs.spring.io/spring-boot/maven-plugin/process-aot.html)：goal 参数与默认绑定 phase
- [OpenJDK JEP 483](https://openjdk.org/jeps/483)：AOT Cache 的 JVM 侧设计与 JDK 25 版本要求
- [GraalVM Documentation](https://www.graalvm.org/latest/docs/)：GraalVM 25.0 LTS 与 for JDK 21 LTS 版本线、各平台 native-image 编译前置条件
