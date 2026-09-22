---
title: SpringBoot4系列17 - 自己写Starter
slug: sb4-starter
date: 2026-10-23 20:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, Starter, 自动配置 ]
draft: false
---

上个月我干了一件特别没有技术含量的事：把订单服务、库存服务、售后服务三个仓库里各一份的短消息通知代码翻出来对了一遍。发送逻辑差不多，重试次数是三个魔法数字，渠道配置三个 yml 各写各的。产品说把重试从 2 次调成 3 次，我改一处配置的活儿硬是变成了改三个仓库、发三次版、回归三遍。这类代码不属于任何一个业务，属于「所有服务都要用一份配置管理起来」的公共设施，starter 就是为它准备的封装形态。

![](https://static.xiongneng.me/starter-module-component-20260922035109.png)

这篇我用 Spring Boot 4.1.1 把通知能力收进一个自研 starter：双模块结构是我对着官方制品解包反推出来的，属性类、条件装配、注册文件一个不少；自动配置的每个条件分支我都用 `ApplicationContextRunner` 写了断言，注册文件错位会怎么样我也起了真实应用做反例，全部数字来自同一次运行。看完能带走一套可以直接抄的双模块工程骨架。

## starter 的解剖事实

动手写之前我先拆官方制品，想看一个标准的 starter 到底由什么组成。下面这些全部来自本地仓库 4.1.1 制品解包，不是文档转述。

`spring-boot-starter-aspectj` 和 `spring-boot-starter-batch-jdbc` 两个 jar 解开，内容少得出乎我的意料：

```text
spring-boot-starter-aspectj-4.1.1.jar
├── META-INF/LICENSE.txt
├── META-INF/NOTICE.txt
└── META-INF/MANIFEST.MF        Spring-Boot-Jar-Type: dependencies-starter
```

零 class、零配置文件。starter 本体是空壳，MANIFEST 里的 `Spring-Boot-Jar-Type: dependencies-starter` 写明了它的角色：只做依赖聚合。引一个 starter 坐标，等于把一组版本受管的依赖带进 classpath。

代码在另一半。我把 `spring-boot-autoconfigure-4.1.1.jar` 解开，`META-INF/spring/` 目录下就两个文件：

```text
META-INF/spring/
├── aot.factories
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

`AutoConfiguration.imports` 是自动配置的注册文件，每行一个全限定类名，`#` 开头是注释。Boot 启动时按这个固定路径扫描所有 jar，把列出的类交给容器处理。jar 里没有 `spring.factories`，自动配置注册只认这一个文件。

自动配置类本身长什么样，`@AutoConfiguration` 注解的字节码给出答案，我用 javap 解出的属性清单：

```text
org.springframework.boot.autoconfigure.AutoConfiguration
    String value()                          注册名，缺省用类名
    Class<?>[] before()   String[] beforeName()   排在其他自动配置之前
    Class<?>[] after()    String[] afterName()    排在其他自动配置之后
```

注解元标注了 `@Configuration(proxyBeanMethods = false)`，自动配置类不必再写 `@Configuration`。条件注解都在 `org.springframework.boot.autoconfigure.condition` 包：`@ConditionalOnClass` 类路径检查、`@ConditionalOnMissingBean` 容器让位、`@ConditionalOnProperty` 属性开关。属性类的 `@ConfigurationProperties` 和 `@EnableConfigurationProperties` 在 `org.springframework.boot.context.properties` 包，位于 spring-boot jar。

这么拆下来，一个自研 starter 要做的事就三件：autoconfigure 模块写代码加注册文件，starter 模块写 pom 聚合依赖，条件注解控制装配。工程骨架我照这个结论搭。

![](https://static.xiongneng.me/starter-starter-anatomy-20260922035109.png)

## 双模块结构与依赖

工程三个模块：autoconfigure 是主角，starter 是空壳，app 是演示应用。

```text
springboot4-starter/
├── order-notify-spring-boot-autoconfigure   代码与注册文件
├── order-notify-spring-boot-starter         空壳聚合，只有 pom
└── order-notify-app                         引用 starter 的演示应用
```

aggregator 的 pom 继承 `spring-boot-starter-parent:4.1.1`，三个模块的版本全部交给 BOM：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
</parent>
<groupId>com.xncoding</groupId>
<artifactId>springboot4-starter</artifactId>
<version>1.0.0</version>
<packaging>pom</packaging>
<modules>
    <module>order-notify-spring-boot-autoconfigure</module>
    <module>order-notify-spring-boot-starter</module>
    <module>order-notify-app</module>
</modules>
```

autoconfigure 模块的依赖清单是整个 starter 里最值得照抄的部分。`spring-boot-autoconfigure` 提供全部注解，两个 optional 依赖各管一件事：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-autoconfigure</artifactId>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <optional>true</optional>
</dependency>
<dependency>
    <groupId>tools.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <optional>true</optional>
</dependency>
```

slf4j 不随 spring-boot-autoconfigure 传递，要日志就得自己声明。Jackson 3 标了 optional：编译期用得到，引用方不会传递得到它，classpath 上有没有 Jackson 由引用方自己决定，这个差别后面会变成一个真实的条件分支。starter 模块只有一个依赖：

```xml
<dependencies>
    <dependency>
        <groupId>com.xncoding</groupId>
        <artifactId>order-notify-spring-boot-autoconfigure</artifactId>
        <version>${project.version}</version>
    </dependency>
</dependencies>
```

引用方写 `order-notify-spring-boot-starter` 一个坐标，自动配置模块随它进入 classpath。和官方 starter 的分工完全一致：starter 管依赖聚合，autoconfigure 管装配。

## 核心代码

### 属性类：order.notify 前缀三个属性

属性是 starter 的配置面。属性类用 `@ConfigurationProperties` 标前缀，字段加 setter，绑定交给 Boot：

```java
@ConfigurationProperties(prefix = "order.notify")
public class OrderNotifyProperties {

    /** 是否启用订单通知，false 时自动配置整体退场，容器里不会出现任何相关 bean */
    private boolean enabled = true;

    /** 发送失败后的重试次数，0 表示只发一次不重试 */
    private int maxRetries = 2;

    /** 短信渠道：logging 走日志打桩，noop 直接丢弃（starter 内置两种，业务可整体覆盖） */
    private String channel = "logging";

    // getter / setter 省略
}
```

三个属性各管一段：`enabled` 是总开关，`max-retries` 控制重试，`channel` 决定内置网关用哪个。默认值我都写在字段上，引用方不配置时行为也是确定的。yml 里写 `order.notify.max-retries` 或 `order.notify.maxRetries` 都绑得上，松散绑定是属性机制自带的。

### 服务接口与默认实现

对外暴露一个接口，默认实现把渲染、重试、发送三步串起来：

```java
public interface OrderNotifyService {

    void notify(String event, String orderNo, String phone);
}

public class DefaultOrderNotifyService implements OrderNotifyService {

    private final SmsClient smsClient;
    private final MessageRenderer renderer;
    private final OrderNotifyProperties properties;
    private String prefix = "";

    @Override
    public void notify(String event, String orderNo, String phone) {
        String content = prefix + renderer.render(event, orderNo);
        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                smsClient.send(phone, content);
                if (attempt > 1) {
                    log.info("NOTIFY_RETRY_OK event={} orderNo={} attempt={}/{}",
                            event, orderNo, attempt, attempts);
                }
                return;
            } catch (RuntimeException ex) {
                if (attempt == attempts) {
                    throw ex;
                }
                log.warn("NOTIFY_RETRY event={} orderNo={} attempt={}/{} reason={}",
                        event, orderNo, attempt, attempts, ex.getMessage());
            }
        }
    }
}
```

重试语义我取的是「失败后再试 maxRetries 次」，总尝试次数等于 maxRetries 加 1。每次失败记 WARN，最后一次直接把异常抛回调用方，让失败可见。`SmsClient` 和 `MessageRenderer` 是两个单方法接口，前者抽象短信网关，后者抽象内容渲染，默认实现都由自动配置按条件提供。

### 自动配置：四个条件各管一个分支

自动配置类是 starter 的装配核心，全文贴出来：

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "order.notify", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OrderNotifyProperties.class)
public class OrderNotifyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SmsClient.class)
    @ConditionalOnProperty(prefix = "order.notify", name = "channel",
            havingValue = "logging", matchIfMissing = true)
    public SmsClient loggingSmsClient() {
        return new LoggingSmsClient();
    }

    @Bean
    @ConditionalOnMissingBean(SmsClient.class)
    @ConditionalOnProperty(prefix = "order.notify", name = "channel",
            havingValue = "noop")
    public SmsClient noopSmsClient() {
        return new NoopSmsClient();
    }

    @Bean
    @ConditionalOnMissingBean(OrderNotifyService.class)
    public OrderNotifyService defaultOrderNotifyService(OrderNotifyProperties properties,
                                                        SmsClient smsClient,
                                                        MessageRenderer renderer,
                                                        ObjectProvider<OrderNotifyCustomizer> customizers) {
        DefaultOrderNotifyService service =
                new DefaultOrderNotifyService(smsClient, renderer, properties);
        customizers.orderedStream().forEach(c -> c.customize(service));
        return service;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ObjectMapper.class)
    static class JacksonRendererConfiguration {

        @Bean
        @ConditionalOnMissingBean(MessageRenderer.class)
        MessageRenderer jacksonMessageRenderer(ObjectMapper objectMapper) {
            return new JacksonMessageRenderer(objectMapper);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("tools.jackson.databind.ObjectMapper")
    static class PlainRendererConfiguration {

        @Bean
        @ConditionalOnMissingBean(MessageRenderer.class)
        MessageRenderer plainMessageRenderer() {
            return new PlainMessageRenderer();
        }
    }
}
```

条件分四层看。类上的 `@ConditionalOnProperty` 管 `order.notify.enabled`，`matchIfMissing = true` 表示属性缺省按开启算，配成 false 时整类跳过，容器里不会出现本 starter 的任何 bean。两个 `SmsClient` 方法靠 `channel` 属性二选一，`@ConditionalOnMissingBean` 保证业务注册了自己的网关时内置实现退位。`defaultOrderNotifyService` 方法上的 `@ConditionalOnMissingBean` 是「业务可覆盖默认实现」的机关：容器里已有同类型 bean 时这个方法不执行。customizer 通过 `ObjectProvider` 逐个应用，构造完默认服务后做最后微调，业务想加一个前缀不必整体换掉实现。

渲染器的分支我拆成了两个内部配置类。`@ConditionalOnClass` 只判断类在不在 classpath，Jackson 在就走 JSON 渲染，不在就走纯文本回退。写成内部类而不是直接标在 `@Bean` 方法上，是因为类级条件在类加载前判定，方法签名里引用不存在的类不会引发 `NoClassDefFoundError`。

### 注册文件：一行定生死

注册文件放在 autoconfigure 模块的 resources 下，路径和文件名一个字符都不能差：

```text
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

文件内容一行：

```text
com.xncoding.starter.order.notify.OrderNotifyAutoConfiguration
```

Boot 启动时调用 `ImportCandidates.load()`，按 `META-INF/spring/<注解全名>.imports` 的固定格式调 `classLoader.getResources()` 收集候选。这条机制有个容易被忽略的事实：所有 jar 的注册文件共享同一个资源路径，框架 jar 和业务 jar 的条目全靠逐行汇总。路径写错、文件名拼错、漏打一个包，启动过程没有任何告警，自动配置静默缺失，表象就是「引了 starter 却什么都没发生」。

这件事反直觉的地方在于它完全静默。编译不报错，启动不报错，日志里一个字都不提，只有业务功能凭空消失。我在后面专门做了一个反例测试，把这个故障形态实拍下来。

### 演示应用：引用方写多少代码

app 模块引一个 starter 坐标，yml 配三个属性：

```yaml
server:
  port: ${SERVER_PORT:18170}

order:
  notify:
    enabled: true
    max-retries: 2
    channel: logging
```

业务代码只有两段。一段注册自己的打桩网关，前两次发送必失败，顺便替掉 starter 内置实现：

```java
@Bean
public SmsClient flakySmsClient() {
    return new FlakySmsClient(totalCalls, failedCalls);
}
```

另一段用 customizer 做收口，给所有通知补一个业务前缀：

```java
@Bean
public OrderNotifyCustomizer retailPrefixCustomizer() {
    return service -> service.setPrefix("[零售]");
}
```

控制器注入 `OrderNotifyService` 直接发通知，业务代码感知不到网关、渲染器、重试这些细节：

```java
@PostMapping("/api/orders/{orderNo}/notify")
public Map<String, Object> notify(@PathVariable String orderNo) {
    notifyService.notify("ORDER_CREATED", orderNo, "13800001234");
    // ...
}
```

有一处我要点名：控制器的包名用 `com.xncoding.orderapp`，与 starter 的 `com.xncoding.starter.order.notify` 完全错开。`@SpringBootApplication` 默认扫描主类所在包，两者有重叠时组件扫描会把自动配置类再注册一遍，自动配置和组件扫描双份生效，条件判断的次序就乱了。

![](https://static.xiongneng.me/starter-class-diagram-20260922035109.png)

## 完整案例

我把应用起起来，按场景打一轮。打桩网关前两次发送必失败，正好把重试撑满：

```text
POST /api/orders/SK-9001/notify   网关前两次失败，第 3 次成功   -> 200
POST /api/orders/SK-9002/notify   网关已恢复，一次成功          -> 200
GET  /api/ops/sms-calls           网关调用对账
```

第一条通知的响应体：

```json
{
  "orderNo": "SK-9001",
  "status": "NOTIFIED",
  "content": "[零售]{\"event\":\"ORDER_CREATED\",\"orderNo\":\"SK-9001\"}",
  "smsClient": "FlakySmsClient"
}
```

`content` 这一个字段就把两个装配结果摆出来了：`[零售]` 前缀来自 customizer，JSON 结构来自 Jackson 渲染器。`smsClient` 显示的是 `FlakySmsClient`，业务注册的网关生效，starter 内置的 `loggingSmsClient` 如实让位。响应耗时 52 ms，里面含着两次失败重试。说实话，重试、前缀、渲染三件事在同一条响应里同时对上号，挺有意思。

![](https://static.xiongneng.me/starter-notify-sequence-20260922035109.png)

日志里的重试序列与配置对得上，`max-retries: 2` 换算成 3 次尝试：

```text
NOTIFY_RETRY event=ORDER_CREATED orderNo=SK-9001 attempt=1/3 reason=模拟网关抖动 #1
NOTIFY_RETRY event=ORDER_CREATED orderNo=SK-9001 attempt=2/3 reason=模拟网关抖动 #2
NOTIFY_RETRY_OK event=ORDER_CREATED orderNo=SK-9001 attempt=3/3
STUB_SMS fail #1 phone=13800001234
STUB_SMS fail #2 phone=13800001234
STUB_SMS ok call#3 phone=13800001234 content=[零售]{"event":"ORDER_CREATED","orderNo":"SK-9001"}
STUB_SMS ok call#4 phone=13800001234 content=[零售]{"event":"ORDER_CREATED","orderNo":"SK-9002"}
```

再调对账接口逐笔核：网关共 4 次调用，失败 2 次，成功 2 次。第一条通知占 3 次（失败两次加成功一次），第二条占 1 次，一笔一笔都对得上。两条通知加一轮重试跑完，应用日志 ERROR 0 行，失败全程走 WARN。装配正确与否，这份数字就是铁证，全部来自同一次运行，我没有手工拼过任何一个数。

![](https://static.xiongneng.me/starter-notify-panels-20260920095322.png)

## 测试怎么写

15 个用例我分两组。autoconfigure 模块用 `ApplicationContextRunner` 起最小上下文断言条件分支，app 模块用 MockMvc 和真实启动覆盖端到端行为。

`ApplicationContextRunner` 是条件装配测试的标准工具，一个 runner 反复配属性跑断言。有个细节我第一次跑就栽在这里：要把 `JacksonAutoConfiguration` 一起装配，否则 `ObjectMapper` bean 不存在，`@ConditionalOnClass` 只查类不查 bean，上下文会起不来：

```java
private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OrderNotifyAutoConfiguration.class,
                org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class));

@Test
void default_enables_full_stack_with_jackson_renderer() {
    runner.run(ctx -> {
        assertThat(ctx).hasSingleBean(OrderNotifyService.class);
        assertThat(ctx).hasSingleBean(JacksonMessageRenderer.class);
        assertThat(ctx.getBean(OrderNotifyProperties.class).getMaxRetries()).isEqualTo(2);
    });
}

@Test
void enabled_off_removes_every_bean_of_this_starter() {
    runner.withPropertyValues("order.notify.enabled=false").run(ctx -> {
        assertThat(ctx).doesNotHaveBean(OrderNotifyService.class);
        assertThat(ctx).doesNotHaveBean(SmsClient.class);
        assertThat(ctx).doesNotHaveBean(OrderNotifyProperties.class);
    });
}

@Test
void user_provided_service_backs_off_default() {
    OrderNotifyService mine = (event, orderNo, phone) -> { };
    runner.withBean("mine", OrderNotifyService.class, () -> mine).run(ctx -> {
        assertThat(ctx.getBean(OrderNotifyService.class)).isSameAs(mine);
        assertThat(ctx).hasSingleBean(SmsClient.class);
    });
}

@Test
void jackson_missing_falls_back_to_plain_renderer() {
    runner.withClassLoader(new FilteredClassLoader(ObjectMapper.class)).run(ctx -> {
        assertThat(ctx).hasSingleBean(PlainMessageRenderer.class);
        assertThat(ctx).doesNotHaveBean(JacksonMessageRenderer.class);
    });
}
```

四个用例对应四条分支：默认全装配、开关关闭整组退场、用户 bean 优先、缺类回退。`FilteredClassLoader` 从 classpath 里藏掉指定类，一行就能模拟「引用方没引 Jackson」的部署形态。同款写法我再覆盖 `channel=noop` 切换网关与属性松散绑定，autoconfigure 模块共 10 个用例全绿。

imports 注册文件的反例是我觉得本篇最值钱的测试。我自定义了一个 classloader，从资源枚举里滤掉自家 jar 贡献的那份 imports，再起一个真实的 SpringApplication：

```java
static class HidingClassLoader extends ClassLoader {
    HidingClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        Enumeration<URL> delegate = super.getResources(name);
        if (!IMPORTS_PATH.equals(name)) {
            return delegate;
        }
        List<URL> kept = new ArrayList<>();
        while (delegate.hasMoreElements()) {
            URL url = delegate.nextElement();
            if (!url.toString().contains("order-notify")) {
                kept.add(url);
            }
        }
        return Collections.enumeration(kept);
    }
}

@Test
void hidden_imports_file_means_silent_missing_autoconfiguration() {
    List<String> candidates = ImportCandidates.load(AutoConfiguration.class,
            new HidingClassLoader(Thread.currentThread().getContextClassLoader()))
            .getCandidates();
    assertThat(candidates)
            .doesNotContain("com.xncoding.starter.order.notify.OrderNotifyAutoConfiguration")
            .contains("org.springframework.boot.autoconfigure.aop.AopAutoConfiguration");

    Thread.currentThread().setContextClassLoader(new HidingClassLoader(original));
    try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(
            OrderNotifyAppApplication.class)
            .web(WebApplicationType.NONE)
            .run("--order.notify.enabled=true")) {
        assertThat(ctx.getBeansOfType(OrderNotifyService.class)).isEmpty();
        assertThat(ctx.containsBean("loggingSmsClient")).isFalse();
    }
}
```

跑下来有个细节是我踩过才明白的：过滤条件必须按 URL 挑出自家那份，不能按资源名整体隐藏。所有 jar 的 imports 文件都同名，整体藏掉会把框架 jar 里的几百条自动配置一并干掉，故障形态就失真了。过滤后的结果正反两面都干净：候选列表里自家条目消失，`AopAutoConfiguration` 这些框架条目还在；上下文照常启动、零告警，starter 装配的 bean 一个都不存在。一个反例证明了两件事：注册文件按这个路径被读取，读不到时故障静默。

app 模块另外 5 个用例走 MockMvc：通知端点 200、响应里的前缀与 JSON 与配置同源、customizer 前缀生效、starter 内置网关让位给业务 bean。15 个用例合计全绿。

![](https://static.xiongneng.me/starter-condition-flow-20260922035109.png)

## 避坑指南

**坑一，注册文件路径错一个字符，一切静默。** 文件必须叫 `org.springframework.boot.autoconfigure.AutoConfiguration.imports`，放在 `META-INF/spring/` 下。路径或文件名不对，启动零告警，自动配置不装配，表象是「引了依赖没效果」。排查时先确认 jar 里这个文件的确切路径，再看内容里的全限定类名有没有拼错。

**坑二，`@ConditionalOnClass` 只查类不查 bean。** 类路径上有 Jackson 不等于容器里有 `ObjectMapper`。被依赖的 bean 来自别的自动配置时，测试里要把那个自动配置一起 `AutoConfigurations.of(...)` 进 runner，生产环境靠 starter 传递依赖保证。

**坑三，条件分支写在方法级和类级的判定时机不同。** 方法级 `@ConditionalOnClass` 的方法签名引用了缺失类时，类加载阶段就抛 `NoClassDefFoundError`，条件没有机会判定。回退分支要拆成独立的内部配置类，类级条件在类加载前完成判定。

**坑四，optional 依赖决定引用方看到什么。** autoconfigure 模块里 Jackson 标 optional，编译期可用但不传递。引用方引了 web 就有 Jackson，走 JSON 渲染；没引就走纯文本。不标 optional 会把 Jackson 强塞给所有引用方，标了不写回退分支则会在缺类环境直接崩，两头都要顾。

**坑五，组件扫描不能覆盖自动配置的包。** `@SpringBootApplication` 默认扫描主类所在包，业务包与 starter 包有重叠时，自动配置类被组件扫描再注册一遍，`@ConditionalOnMissingBean` 的判定次序被打乱。业务包名与 starter 包名保持完全错开，或者显式限定扫描范围。

**坑六，`@ConditionalOnMissingBean` 依赖注册顺序。** 它只在「轮到自己时容器还没有同类型 bean」的条件下成立，所以只适合放在自动配置类里。业务 `@Configuration` 里同类 bean 先注册，自动配置后处理，让位逻辑天然成立；两个自动配置类之间互让位时，用 `@AutoConfiguration` 的 `before` / `after` 控制次序。

**坑七，重试语义要写进属性注释。** `max-retries` 是「失败后再试几次」还是「总共尝试几次」，两种理解差一次，容易闹笑话。本工程取前者，总尝试次数等于 maxRetries 加 1，注释里写明，测试里断言，使用方少一轮猜谜。

**坑八，starter 模块不要写代码。** starter 只留 pom，把依赖聚齐。往 starter 里塞类，引用方就绕过了 autoconfigure 的条件体系，代码在不在 classpath 都会被强行加载，条件装配形同虚设。

**坑九，验收清单里加一条 bean 存在性检查。** 启动成功不代表装配成功。集成测试或验收环境里断言关键 bean 存在（或对着 `/actuator/beans` 查一眼），starter 该出现的东西出现了，才算装配通过。

## 小结

什么时候该自己写一个 starter？我的判断标准就一条：这段能力是不是在两个以上服务里以「同一份逻辑、各自一份配置」的形态重复出现。是，就收进 starter；只在一个服务里用的，一个普通配置类就够了，别为了模式而模式。真要动手，把纪律守住：starter 模块只留 pom，注册文件路径一个字符都不能错，条件注解按开关、类路径、bean 让位分层摆好，测试用 runner 把每个分支钉死，验收清单里加一条 bean 存在性检查。同一套骨架装防重令牌、审计埋点这类横切能力都适用，代价是维护者要一直盯着注册文件与条件注解这两处，starter 的静默故障全从这里来。

没解决的事也有两件。一是更复杂的条件组合，比如多个 `@Conditional` 叠加、自定义 Condition 类的场景，这次的 runner 写法够不够用我没测过；二是 starter 发到私服后的版本升级策略，属性改名的兼容窗口怎么留，工程里只能靠纪律没有机制保障。这两件事等我真的被坑到了，再补一篇。

## 参考链接

- [Spring Boot Reference - Creating Your Own Auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)：自写自动配置与 starter 的官方指南，条件注解与测试工具的权威定义
- [Spring Boot Reference - Auto-configuration Classes](https://docs.spring.io/spring-boot/appendix/auto-configuration-classes.html)：Boot 全部自动配置类与 `AutoConfiguration.imports` 的清单
- [@AutoConfiguration 源码](https://github.com/spring-projects/spring-boot/blob/main/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfiguration.java)：注解属性与元注解的源码，本文取证结论的直接出处
- [spring-boot-starter-aspectj 4.1.1](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-aspectj/4.1.1/)：空壳 starter 结论的制品实证来源
- [spring-boot-autoconfigure 4.1.1](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-autoconfigure/4.1.1/)：注册文件与条件注解的解包来源
- [ApplicationContextRunner Javadoc](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/test/context/runner/ApplicationContextRunner.html)：条件装配测试工具的 API 说明
