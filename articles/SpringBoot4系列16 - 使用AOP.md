---
title: SpringBoot4系列16 - 使用AOP

slug: sb4-aop

date: 2026-10-22 20:00:00 +0800

toc: true

categories: [ java ]

tags: [ SpringBoot, SpringBoot4, AOP, 切面 ]

draft: false

---

我给订单服务定过一个小目标：每个接口统计耗时、关键操作留审计、下单支付防重复提交。这三件事都不难，难在它们跟业务一点关系都没有。没有切面的写法是把它们复制进每个方法：`System.currentTimeMillis()` 抬头低头各一次，审计字段在返回前手动塞库，幂等靠前端按钮置灰。我见过三个月后的下场：没人说得清哪些接口漏了计时，审计表里缺哪天的行，重复提交打穿库存的客诉截图还挂在群里。这三件事的共性是它们不属于任何业务方法，属于「所有方法都要过一遍」的横切关注点，切面正是为这类代码准备的。

![](https://static.xiongneng.me/aop-aspect-sequence-20260922033245.png)

这篇我把整套方案在 Spring Boot 4.1.1 上落了一遍：订单服务带四个切面，按 `@Order` 排成洋葱链，计时用 `@within`、链路跟踪用 `execution`、审计用 `@annotation`、幂等用注解加指纹。审计落的是真实 MySQL，幂等拒绝走 RFC 9457 返回 409，`@Around` 的两个经典坑我各准备了一个最小复现。文中的数字全部来自同一次运行，验证脚本的原始记录我都留了底，你可以放心对着抄。

## 编程模型与自动配置的事实

写代码之前我先把坐标和自动配置确认了一遍，做法笨但扎实：从 Central 把制品解包，再用字节码工具翻一遍。

第一件事就推翻了我的记忆。说实话，starter 改名这事我一开始是不信的，直到亲手解包才认：`spring-boot-starter-aop` 在 Central 的 4.1.1 目录里 404，现行坐标是 `spring-boot-starter-aspectj`。组成（POM 实测）：

```text
spring-boot-starter-aspectj      = spring-boot-starter + org.springframework:spring-aop:7.0.9
                                   + org.aspectj:aspectjweaver:1.9.25.1
spring-boot-starter-aspectj-test = spring-boot-starter-aspectj + spring-boot-starter-test
```

两个细节值得记住。aspectjweaver 的版本由 BOM 属性 `aspectj.version=1.9.25.1` 管理，同一属性管 aspectjrt 与 aspectjtools，工程里不用写版本号。starter 只带 weaver 不带 aspectjrt：纯 Spring 代理模型在运行期用 weaver 里的 `@Aspect` 注解就够了，不需要 AspectJ 编译器参与编译。

`spring-boot-aop` 这样的独立模块不存在（Central 404 实证），自动配置类仍在 spring-boot-autoconfigure 里：`org.springframework.boot.autoconfigure.aop.AopAutoConfiguration`，登记在该模块的 `AutoConfiguration.imports`。条件结构用 javap 解出来是三层：

```text
AopAutoConfiguration
  @ConditionalOnBooleanProperty(name="spring.aop.auto", matchIfMissing=true)
  ├─ AspectJAutoProxyingConfiguration        @ConditionalOnClass(org.aspectj.weaver.Advice)
  │   ├─ CglibAutoProxyConfiguration
  │   │     @EnableAspectJAutoProxy(proxyTargetClass=true)
  │   │     @ConditionalOnBooleanProperty(name="spring.aop.proxy-target-class", matchIfMissing=true)
  │   └─ JdkDynamicAutoProxyConfiguration
  │         @EnableAspectJAutoProxy(proxyTargetClass=false)
  │         @ConditionalOnBooleanProperty(name="spring.aop.proxy-target-class", havingValue=false)
  └─ ClassProxyingConfiguration              @ConditionalOnMissingClass("org.aspectj.weaver.Advice")
```

类路径上有 aspectjweaver（引 starter 即有），走上半支：自动配置替工程贴上 `@EnableAspectJAutoProxy`，`proxy-target-class` 缺省按 true 算，代理落 CGLIB 分支。类路径没有 weaver 的工程（只用 `@Async`、`@Cacheable` 这类注解）走下半支，一个 `BeanFactoryPostProcessor` 把代理方式切成类代理，不贴 `@EnableAspectJAutoProxy`。

`spring.aop.*` 前缀下只有两条属性，并且没有对应的 `@ConfigurationProperties` 属性类，条件注解按属性名读 Environment：

```text
spring.aop.auto                  默认 true    设 false 则整个 AOP 自动配置关闭
spring.aop.proxy-target-class    默认 true    true 为 CGLIB 类代理，false 为 JDK 接口代理
```

配置写在 `aop.proxy-target-class` 或 `spring.aop.proxytargetclass` 下都绑不上，这是排查代理不生效时我建议你先查的一处。`@EnableAspectJAutoProxy` 与自动配置的关系也清楚了：引了 starter 就不必手写，注解默认 `proxyTargetClass=false`，Boot 替工程贴的版本是 true，与「Boot 下代理实际是 CGLIB」的日常经验一致。唯一值得手贴的场景是 `exposeProxy=true`，配合 `AopContext.currentProxy()` 解决自调用。

![](https://static.xiongneng.me/aop-starter-map-20260922033245.png)

## 依赖和配置

工程四块依赖：`starter-webmvc` 撑起订单 HTTP 面，`starter-aspectj` 是本篇主角，`starter-jdbc` 加 MySQL 驱动管审计落库：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aspectj</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

测试侧一个 starter 够用。我对比过 gRPC 那套进程内传输，`aspectj-test` 里没有专属测试设施，常规 webmvc-test 自带的 MockMvc 与 OutputCapture 就覆盖了切面测试的全部需求：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
```

配置只有一处与本篇强相关：`spring.mvc.problemdetails.enabled=true` 让错误响应统一走 RFC 9457 的 `application/problem+json`，切面里抛出的异常最后由它渲染：

```yaml
spring:
  datasource:
    url: jdbc:mysql://192.168.1.97:3306/springboot4_aop?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: root123456
  mvc:
    problemdetails:
      enabled: true
  sql:
    init:
      mode: always
      schema-locations: classpath:sql/schema.sql
```

审计表一张，八列：模块、操作、方法签名、参数摘要、成败、异常类名、耗时毫秒、时间戳。

```sql
CREATE TABLE IF NOT EXISTS t_audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    module     VARCHAR(32)  NOT NULL,
    action     VARCHAR(64)  NOT NULL,
    method     VARCHAR(128) NOT NULL,
    args       VARCHAR(255),
    status     VARCHAR(8)   NOT NULL,
    error      VARCHAR(64),
    cost_ms    INT,
    created_at DATETIME(3)  NOT NULL
);
```

## 核心代码

四个切面按职责分工，注解只定义不实现。先看两个自定义注解，它们是切面的「配置面」：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /** 防重窗口秒数：同一指纹在这个窗口内只放行一次 */
    int windowSeconds() default 60;
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {
    /** 业务模块，例如「订单」 */
    String module();
    /** 操作名，例如「创建订单」 */
    String action();
}
```

`@Retention(RetentionPolicy.RUNTIME)` 必须显式写，默认的 CLASS 保留策略在运行期读不到，`@annotation` 切点会静默失配。这是我踩过才敢这么写的：漏了它编译正常、启动正常，就是一条切点都匹配不上。业务代码这边零改动，观测逻辑全搬进切面，这笔买卖稳稳的赚。

![](https://static.xiongneng.me/aop-aspect-component-20260922033245.png)

### 计时切面：@within 圈住整个 web 层

`@Pointcut` 声明切点，`@Around` 包住目标方法，`proceed()` 前后就是计时窗口：

```java
@Aspect
@Component
@Order(2)
public class MetricsAspect {

    private static final Logger log = LoggerFactory.getLogger(MetricsAspect.class);

    @Pointcut("@within(org.springframework.web.bind.annotation.RestController)")
    public void webLayer() {
    }

    @Around("webLayer()")
    public Object timeRequest(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.nanoTime();
        String target = pjp.getSignature().toShortString();
        log.info("AOP_METRICS enter {}", target);
        try {
            Object result = pjp.proceed();
            log.info("AOP_TIME {} costMs={} status=OK", target, elapsedMs(t0));
            return result;
        } catch (Throwable t) {
            log.info("AOP_TIME {} costMs={} status=ERROR({})",
                    target, elapsedMs(t0), t.getClass().getSimpleName());
            throw t;
        } finally {
            log.info("AOP_METRICS exit {}", target);
        }
    }

    private long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
```

切点 `@within(RestController)` 命中所有标注 `@RestController` 的类里的全部方法，控制器不用贴任何额外注解。catch 分支记完状态原样 `throw t`，计时切面只观测不干预，异常留给全局处理器渲染。实测一轮：create 首单 51 ms，同单重复请求（幂等打回路径）之后第二单 14 ms，pay 11 ms，pack 142 ms，查不存在订单的 404 响应 16 ms。

### 跟踪切面：execution 免注解覆盖 service 层

四种 advice 各干一件事，`@Before` 进场记参数，`@AfterReturning` 记返回值，`@AfterThrowing` 记异常，`@After` 成败都走：

```java
@Aspect
@Component
@Order(3)
public class TraceAspect {

    private static final Logger log = LoggerFactory.getLogger(TraceAspect.class);

    @Pointcut("execution(* com.xncoding.aop.service..*.*(..))")
    public void serviceLayer() {
    }

    @Before("serviceLayer()")
    public void enter(JoinPoint jp) {
        log.info("AOP_TRACE enter {} args={}",
                jp.getSignature().toShortString(), Arrays.toString(jp.getArgs()));
    }

    @AfterReturning(pointcut = "serviceLayer()", returning = "result")
    public void onReturn(JoinPoint jp, Object result) {
        log.info("AOP_TRACE return {} result={}", jp.getSignature().toShortString(), result);
    }

    @AfterThrowing(pointcut = "serviceLayer()", throwing = "ex")
    public void onThrow(JoinPoint jp, Throwable ex) {
        log.info("AOP_TRACE throws {} ex={}",
                jp.getSignature().toShortString(), ex.getClass().getSimpleName());
    }

    @After("serviceLayer()")
    public void exit(JoinPoint jp) {
        log.info("AOP_TRACE exit {}", jp.getSignature().toShortString());
    }
}
```

`execution(* com.xncoding.aop.service..*.*(..))` 按包名匹配，service 包下新增方法自动进入跟踪范围，这是它和注解切点最大的分工差异：按包圈范围用 execution，按标记圈范围用注解。

### 审计切面：@annotation 加 finally 落库

审计挂在 `@OperationLog` 上，成败两个分支都在 `finally` 里写库，失败行带异常类名：

```java
@Aspect
@Component
@Order(4)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final JdbcTemplate jdbc;

    public AuditAspect(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Around("@annotation(operationLog)")
    public Object audit(ProceedingJoinPoint pjp, OperationLog operationLog) throws Throwable {
        long t0 = System.nanoTime();
        String method = pjp.getSignature().toShortString();
        String args = summarize(pjp.getArgs());
        String status = "OK";
        String error = null;
        log.info("AOP_AUDIT enter {} {}", operationLog.module(), operationLog.action());
        try {
            return pjp.proceed();
        } catch (Throwable e) {
            status = "ERROR";
            error = e.getClass().getSimpleName();
            throw e;
        } finally {
            long costMs = (System.nanoTime() - t0) / 1_000_000;
            jdbc.update("""
                            INSERT INTO t_audit_log(module, action, method, args, status, error, cost_ms, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, NOW(3))
                            """,
                    operationLog.module(), operationLog.action(), method, args, status, error, (int) costMs);
            log.info("AOP_AUDIT done {} {} status={} costMs={}",
                    operationLog.module(), operationLog.action(), status, costMs);
        }
    }

    private String summarize(Object[] args) {
        String joined = Arrays.stream(args)
                .map(a -> a == null ? "null" : a.toString())
                .collect(Collectors.joining(", "));
        return joined.length() > 200 ? joined.substring(0, 200) : joined;
    }
}
```

切点写法 `@annotation(operationLog)` 把注解实例绑定进 advice 参数，`module()` 与 `action()` 从方法上的注解取值，业务代码除了贴注解零改动。我数过这轮落库：审计落了 5 行，创建与支付成功 3 行、打包 1 行、查不存在订单 1 行 ERROR，error 列记着 `OrderNotFoundException`。

有个对账细节挺有意思，我单独拎出来说：打包接口业务逻辑 sleep 120 ms，审计表 cost_ms 记 124，多出的 4 ms 是方法内 JSON 组装。这个对得上，恰恰说明审计切面的计时口径只含业务方法自身，中间各层切面的开销不在它里面。

![](https://static.xiongneng.me/aop-audit-dataflow-20260922033245.png)

### 幂等切面：指纹加 putIfAbsent

幂等放在切面链最外层，指纹取方法签名加入参，命中重复指纹抛自定义异常：

```java
@Aspect
@Component
@Order(1)
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    /** 请求指纹 -> 过期时间戳。单机演示够用，分布式场景要换成 Redis 这类共享存储 */
    private final ConcurrentHashMap<String, Long> tokens = new ConcurrentHashMap<>();

    @Around("@annotation(idempotent)")
    public Object guard(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String key = pjp.getSignature().toShortString() + ":" + Arrays.deepToString(pjp.getArgs());
        long now = System.currentTimeMillis();
        long deadline = now + idempotent.windowSeconds() * 1000L;
        tokens.entrySet().removeIf(e -> e.getValue() <= now);
        if (tokens.putIfAbsent(key, deadline) != null) {
            log.warn("AOP_IDEM reject key={} window={}s", key, idempotent.windowSeconds());
            throw new IdempotentRejectedException(idempotent.windowSeconds());
        }
        log.info("AOP_IDEM enter key={}", key);
        try {
            return pjp.proceed();
        } finally {
            log.info("AOP_IDEM exit key={}", key);
        }
    }
}
```

指纹可靠的前提是入参有稳定的 `toString`。控制器里的请求体用 record 定义，编译器生成的 `toString` 逐字段输出，同内容的两次请求指纹一致。`putIfAbsent` 的原子性保证并发到达的两个相同请求只有一个放行。被拒绝的异常沿调用链抛给全局处理器：

```java
@ExceptionHandler(IdempotentRejectedException.class)
public ProblemDetail handleIdempotentRejected(IdempotentRejectedException ex, HttpServletRequest req) {
    ProblemDetail pd = problem(HttpStatus.CONFLICT, "重复请求", ex.getMessage(), req,
            "urn:problem-type:duplicate-request");
    pd.setProperty("windowSeconds", ex.getWindowSeconds());
    return pd;
}
```

![](https://static.xiongneng.me/aop-idempotent-activity-20260922033245.png)

### 顺序如何落到日志

四个 `@Order` 数字小在外层，链路按洋葱结构展开。一次创建订单的日志实测序列：

```text
AOP_IDEM enter    OrderController.create
AOP_METRICS enter OrderController.create
AOP_AUDIT enter   订单 创建订单
AOP_TRACE enter   OrderService.createOrder
AOP_TRACE return  OrderService.createOrder
AOP_TRACE exit    OrderService.createOrder
AOP_AUDIT done    订单 创建订单 status=OK costMs=2
AOP_METRICS exit  OrderController.create
AOP_IDEM exit     OrderController.create
```

有个容易想错的地方，我第一次也想错了：Metrics 是 2，Trace 是 3，但 Trace 的日志夹在 Audit 中间。原因是 Trace 织入的是 service 层 bean 的代理，Audit 织入的是 controller 层 bean 的代理，两层代理嵌套，Trace 所在的内层链整体位于 Audit 的 `proceed()` 之内。`@Order` 只在同一层代理内部定序，跨层比较没有意义。这段日志序列就是洋葱结构的铁证，日志前缀 `AOP_` 就是为这类断言准备的，测试一节直接按序列断言。

## 完整案例

业务三件套：`OrderStore` 用 `AtomicInteger` 管库存 50 件，CAS 扣减；`OrderService` 管下单、查询、支付、打包；`OrderController` 暴露四个接口，create 与 pay 标 `@Idempotent`，四个方法全标 `@OperationLog`。启动应用按场景打一轮：

```text
POST /api/orders  {"orderNo":"SK-AOP-1","amount":"129.00","itemCount":1}   -> 201
POST /api/orders  同一请求体原样重发                                        -> 409
GET  /api/orders/SK-NOPE                                                   -> 404
POST /api/orders  {"orderNo":"SK-AOP-2","amount":"88.00","itemCount":4}    -> 201
POST /api/orders/SK-AOP-1/pay                                            -> 200
POST /api/orders/SK-AOP-1/pay  窗口内重发                                  -> 409
POST /api/orders/SK-AOP-2/pack                                           -> 200
GET  /api/ops/stats                                                       -> 对账
```

![](https://static.xiongneng.me/aop-orders-panels-20260920085416.png)

两次 409 的响应体都是 problem+json，`type` 字段区分问题类别，`windowSeconds` 带回防重窗口：

```json
{
  "type": "urn:problem-type:duplicate-request",
  "title": "重复请求",
  "status": 409,
  "detail": "相同请求在 60 秒内已受理，请稍后再试",
  "path": "/api/orders",
  "windowSeconds": 60
}
```

对账接口读库读内存，数字逐项对上：审计 5 行，其中 4 行 OK、1 行 ERROR；库存从 50 降到 45（两单合计 5 件）；订单 2 单；幂等拒绝 2 次。被幂等打回的请求不产生审计行，因为拒绝发生在切面链最外层，内层审计切面没进到。

![](https://static.xiongneng.me/aop-audit-panels-20260920085416.png)

## 测试怎么写

四个测试类覆盖四种能力，9 个用例全绿。

切面顺序用 OutputCapture 断言日志序列。`CapturedOutput` 在 Boot 4 里是接口不是注解，按参数类型注入，`OutputCaptureExtension` 负责赋值：

```java
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AspectOrderTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fourAspects_wrap_like_onion(CapturedOutput output) throws Exception {
        String orderNo = "SK-ORD-" + System.nanoTime();
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderNo":"%s","amount":"66.00","itemCount":1}
                                """.formatted(orderNo)))
                .andExpect(status().isCreated());

        List<String> marks = Arrays.stream(output.toString().split("\\r?\\n"))
                .filter(l -> l.contains("AOP_"))
                .map(l -> l.replaceAll(".*(AOP_[A-Z]+ (?:enter|done|exit|return|reject)).*", "$1"))
                .toList();

        assertThat(marks).containsSubsequence(
                "AOP_IDEM enter",
                "AOP_METRICS enter",
                "AOP_AUDIT enter",
                "AOP_TRACE enter",
                "AOP_TRACE return",
                "AOP_TRACE exit",
                "AOP_AUDIT done",
                "AOP_METRICS exit",
                "AOP_IDEM exit");
    }
}
```

这里有两个我踩过的坑。其一，`split("\\n")` 在 Windows 上会留下行尾的 `\r`，正则里的点号不匹配 `\r`，`replaceAll` 的尾部 `.*` 吞不掉回车，抽出来的标记尾部挂着不可见字符，`containsSubsequence` 断言全挂，我盯着满屏红字愣了半天才发现是回车捣的乱。写成 `split("\\r?\\n")` 一劳永逸。其二，`containsSubsequence` 断言的是子序列不是连续子串，中间夹其他切面的日志不干扰判定。

幂等的 HTTP 行为用 MockMvc 直测：第一单 201，同请求体第二发 409，响应体标题与窗口字段都在断言里。审计落库用真实 MySQL，`@BeforeEach` 清表，请求后 `JdbcTemplate` 查行数与最后一行的字段：

```java
@Test
void business_exception_writes_error_row_with_exception_name() throws Exception {
    mockMvc.perform(get("/api/orders/{orderNo}", "SK-NOPE-" + System.nanoTime()))
            .andExpect(status().isNotFound());

    assertThat(auditRows()).isEqualTo(1);
    Map<String, Object> row = lastAuditRow();
    assertThat(row.get("status")).isEqualTo("ERROR");
    assertThat(row.get("error")).isEqualTo("OrderNotFoundException");
}

@Test
void idempotent_rejected_request_writes_no_audit_row() throws Exception {
    // 第一次 201 落 1 行；第二次 409 后行数不变：
    // 幂等切面在最外层打回，审计切面没进到
}
```

`@Around` 的两个坑用最小上下文复现，`AspectJProxyFactory` 手动织入，不起 Spring 容器：

```java
@Aspect
static class SwallowAspect {
    @Around("execution(* com.xncoding.aop..*.doWork(..))")
    Object swallow(ProceedingJoinPoint pjp) {
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            return null;
        }
    }
}

@Test
void swallow_aspect_hides_exception_and_returns_null() {
    FragileService service = new FragileService();
    AspectJProxyFactory factory = new AspectJProxyFactory(service);
    factory.addAspect(new SwallowAspect());
    FragileService proxy = factory.getProxy();

    assertThatCode(proxy::doWork).doesNotThrowAnyException();
    assertThat(service.executed).isEqualTo(1);
}
```

被代理的方法执行就抛异常，计数器 `executed` 证明方法体确实跑过：异常被切面吃掉，调用方拿到 `null`，排查时唯一的线索是少了一行 ERROR 日志。坑二更隐蔽，advice 里忘调 `proceed()` 而是返回兜底值，计数器保持 0，方法体压根没执行。这两个坑的解法一致：`@Around` 里要么 `proceed()` 要么抛异常，不做第三种收场。

## 避坑指南

**坑一，starter 坐标已经改名。** `spring-boot-starter-aop` 在 4.1.1 的 Central 目录里 404，写老坐标构建直接失败。我第一反应是网络问题，重试了两次才去查目录，九成是这个原因。现行坐标 `spring-boot-starter-aspectj`，组成是 starter 加 spring-aop 7.0.9 加 aspectjweaver 1.9.25.1，版本全部由 BOM 管。

**坑二，切点注解的保留策略必须是 RUNTIME。** 自定义注解漏写 `@Retention(RetentionPolicy.RUNTIME)` 时编译正常、启动正常，`@annotation` 切点一条都匹配不上，所有 advice 静默失职。排查手段是把切点临时换成 `execution` 看是否恢复，我就是这么定位的。

**坑三，`@within` 与 `@annotation` 圈的范围不同。** `@within` 匹配标注在类上的注解，覆盖类的全部方法；`@annotation` 只匹配方法上贴了注解的方法。想给 `@RestController` 全类生效用前者，想按方法粒度开关注解用后者，写反了要么范围过大要么一条不中。

**坑四，自调用不经过代理。** 同类里方法 A 调方法 B，B 上的切面不生效，因为这次调用没走代理对象。解法是注入自身代理（`AopContext.currentProxy()` 配 `exposeProxy=true`）或把 B 挪到另一个 bean。

**坑五，`@Around` 吞异常与漏 proceed。** catch 之后返回 `null` 或兜底值不上抛，调用方无从知晓失败；不调 `proceed()` 则方法体根本不执行。两条都有最小复现测试，`@Around` 的出口只有 `return pjp.proceed()` 的结果或重抛的异常。

**坑六，`@Order` 只管同层代理内部。** 本例 Trace 是 `@Order(3)`，日志却夹在 `@Order(4)` 的审计中间，因为它织在 service 层代理，不在 controller 层的链上。跨层排顺序看调用链不看注解数字，我在这上面闹过笑话。计时切面要放最外层，放内层统计不到外层切面的开销。

**坑七，属性前缀只有 `spring.aop.*` 两条。** `spring.aop.auto` 关自动配置，`spring.aop.proxy-target-class` 切代理模式，没有属性类，写 `aop.proxy-target-class` 或驼峰变体都绑不上。排查代理模式问题时先确认属性名没写错。

**坑八，切面抛异常要走全局处理器。** 切面里的异常沿调用链穿透，最终由 `@RestControllerAdvice` 接住渲染成 problem+json，切面自身不需要感知 HTTP。前提是异常类型在处理器里有对应的 `@ExceptionHandler`，没有的话 409 会变成 500。

**坑九，切面里的计时口径要分清。** 外层切面统计的耗时包含内层全部切面与业务；审计切面在最里层，cost_ms 只含业务方法自身。本例 pack 接口 AOP_TIME 记 142 ms，审计表 cost_ms 记 124，差的 18 ms 是中间各层开销。对外报耗时用外层口径，性能归因用内层口径。

## 小结

选型上我的建议是三句话。要把计时、审计、幂等这类横切逻辑从业务代码里摘出来，starter-aspectj 一个依赖就够，业务代码只贴注解，接口加审计从改代码变成加一行注解。切点选择按范围挑：按包圈用 execution，按类圈用 `@within`，按方法粒度用 `@annotation`，三种都上手过才知道各自的顺手之处。幂等这条，单机起步用 `ConcurrentHashMap` 足够，上多实例时把存储换成 Redis，切面代码其余部分不用动。

没解决的事也交代两件。其一，指纹依赖入参的 `deepToString`，遇到没有稳定 `toString` 的参数类型会误判，除了约定 record 我还没找到更省心的办法。其二，切面内的异常目前只有「重抛给全局处理器」一条路，切面内部想做降级或重试还得自己再包一层。这两件我记下了，等有结论再展开。

## 参考链接

- [Spring Framework - Aspect Oriented Programming](https://docs.spring.io/spring-framework/reference/core/aop.html)：AOP 编程模型官方文档，切点表达式与五种 advice 的权威定义
- [Spring Boot Reference - AOP](https://docs.spring.io/spring-boot/reference/aop.html)：Boot 的 AOP 自动配置说明与 `spring.aop.*` 属性
- [AspectJ 5 Development Kit Developer's Notebook](https://www.eclipse.org/aspectj/doc/released/adk15notebook/index.html)：@AspectJ 注解风格的原始文档，切点语义细节在这里
- [AopAutoConfiguration 源码](https://github.com/spring-projects/spring-boot/blob/main/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/aop/AopAutoConfiguration.java)：条件结构的源码，本文取证结论的直接出处
- [RFC 9457 - Problem Details](https://www.rfc-editor.org/rfc/rfc9457)：HTTP 问题详情规范，本文 409 与 404 响应体的格式依据
- [spring-boot-starter-aspectj 4.1.1](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-aspectj/4.1.1/)：本文 starter 组成与版本结论的制品实证来源
