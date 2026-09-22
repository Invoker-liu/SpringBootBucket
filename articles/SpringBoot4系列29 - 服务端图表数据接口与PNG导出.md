---
title: SpringBoot4系列29 - 服务端图表数据接口与PNG导出
slug: sb4-echarts
date: 2026-09-20 23:59:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, ECharts, PNG导出, Playwright ]
draft: false
---

运营平台的报表需求里有一类固定动作：每天早上把昨天的订单统计图发到工作群。图要有、消息要准时，还要在群里直接能看，转发链接与打开网页都嫌多余。我最后选的做法，是服务端定时把图表渲染成 PNG，交给消息通道发出去。浏览器端 ECharts 画图没问题，但 PNG 要在服务端出，渲染这件事就得由后端想办法。

我在 Spring Boot 4.1.1（Spring Framework 7.0.9、JDK 21.0.10）上把这件事完整做了一遍，一共三件事：后端出纯数据的图表统计接口，静态页用 ECharts 渲染并用无头浏览器验证渲染成功，导出接口把图表页渲染成 PNG 以字节流返回。方案对比、超时重试、尺寸参数、problem+json 错误语义全部实测过。配套工程 springboot4-echarts，端口 18290。正文里的数字我全部留了底，都来自同一轮端到端验证脚本的原始记录，A 到 G 每组可查，没有一处是手工拼的。

![](https://static.xiongneng.me/echarts-components-20260922074401.png)

## 数据接口设计：数据与渲染分离

工程只引一个 starter：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
```

starter-webmvc 传递 spring-boot-starter-jackson，Jackson 3（tools.jackson 3.1.5）随之就位。统计 DTO 我用 record 表达：

```java
public record DailyStat(LocalDate date, long orderCount, BigDecimal amount) {
}
```

LocalDate 与 BigDecimal 是统计数据最常用的两种字段，Jackson 3 对两者的默认行为正好符合需求：LocalDate 输出 ISO 字符串 `2026-09-13`（java.time 支持默认内置，不再需要手动注册 jackson-datatype-jsr310），BigDecimal 按数值原样输出。这两点是第 23 篇取证得到的结论，我直接复用。

接口只返回纯数据，不返回 ECharts option：

```java
@GetMapping("/daily")
public Map<String, Object> daily(@RequestParam(defaultValue = "7") int days) {
    List<DailyStat> items = statsService.daily(days);
    return Map.of(
            "days", items.size(),
            "items", items
    );
}
```

`/api/stats/daily` 返回按天的订单量与销售额，`/api/stats/category` 返回类目维度的汇总。字段形状我按「类目轴一维数组加数值数组」的取数方式设计，前端拿到 items 后自行拼 option。这样同一份接口可以喂 ECharts，也可以喂任何其他图表库、小程序或者导出程序；把 option 直接拼在接口里返回，消费端就被绑死在 ECharts 语义上，换图库或换渲染端都得改后端。

数据源用固定种子的内存生成，保证可复现：

```java
private static final String[] CATEGORIES = {"图书", "数码", "家居", "服饰", "食品"};
private static final int MAX_DAYS = 30;

public List<DailyStat> daily(int days) {
    int n = clamp(days);
    LocalDate today = LocalDate.now();
    Random random = new Random(today.toEpochDay());
    List<DailyStat> items = new ArrayList<>(n);
    for (int i = n - 1; i >= 0; i--) {
        LocalDate date = today.minusDays(i);
        long orderCount = 40 + random.nextInt(80);
        BigDecimal amount = BigDecimal.valueOf(2000 + random.nextInt(6000)
                + random.nextInt(100) / 100.0).setScale(2, RoundingMode.HALF_UP);
        items.add(new DailyStat(date, orderCount, amount));
    }
    return items;
}

private int clamp(int days) {
    if (days <= 0) {
        return 7;
    }
    return Math.min(days, MAX_DAYS);
}
```

种子取当天日期的 epochDay，同一天内重复启动、重复请求得到完全一致的序列。文章里的数字与导出 PNG 里的图形因此可以对得上，验证、截图与正文同源。生产环境把这个类换成 JdbcTemplate 聚合查询即可，接口形状不变，前端与导出端都感知不到数据源换了。`days` 参数在服务层夹在 1 到 30 之间，防止一次请求拖出全表；传 0 或负数回落到默认 7 天，口径在服务端收口，不依赖调用方自觉。

写完接口我先用 curl 实测了一轮，A、B 两组原始记录如下：

```
GET /api/stats/daily?days=7 → HTTP 200
{"items":[{"date":"2026-09-14","orderCount":94,"amount":2834.68},
 {"date":"2026-09-15","orderCount":57,"amount":7703.86},...],"days":7}

GET /api/stats/category → HTTP 200
{"items":[{"category":"图书","orderCount":131,"amount":11925.83},
 {"category":"数码","orderCount":51,"amount":5537.72},
 {"category":"家居","orderCount":144,"amount":4801.79},
 {"category":"服饰","orderCount":76,"amount":3103.25},
 {"category":"食品","orderCount":99,"amount":8017.75}],"total":5}
```

日期是 ISO 字符串，金额保留两位小数，前端不需要做任何格式适配。

## 前端渲染页：URL 参数即渲染参数

图表页是放在 `src/main/resources/static/chart.html` 的静态文件，Boot 4.1 对 static 目录默认托管，`classpath:/static/**` 默认映射 `/**`，我不需要写任何额外配置。ECharts 用 jsdelivr 的 CDN，版本锁到小版本：

```html
<script src="https://cdn.jsdelivr.net/npm/echarts@5.6.0/dist/echarts.min.js"></script>
```

ECharts 当前主流版本线是 5.x 末版 5.6.0，npm 上 latest 已到 6.1.0。引用 CDN 时写 `echarts@5` 这种浮动 tag 会随源站缓存策略漂到不同的小版本，option 行为可能变化。我锁 `@5.6.0`，这是版本纪律里成本最低的一条。

页面把 URL 参数当作渲染参数：`type` 决定图型（daily 柱线组合、category 环形饼图），`days` 决定回看窗口，`width` 与 `height` 决定画布尺寸，`export=1` 隐藏页面标题进入纯图表模式。同一个页面浏览器看、无头浏览器也看，参数不同而已：

```javascript
var params = new URLSearchParams(location.search);
var type = params.get('type') || 'daily';
var chartEl = document.getElementById('chart');
chartEl.style.width = width + 'px';
chartEl.style.height = height + 'px';
var chart = echarts.init(chartEl);
```

渲染完成的判定是无头导出能不能截到完整画面的关键。说实话我一开始也想过固定 sleep 等几秒，但 ECharts 的渲染是异步管线，数据 fetch、setOption、动画帧走完才有完整画面，固定 sleep 要么等不够要么白等。页面用 finished 事件立标志位：

```javascript
var ready = false;
chart.on('finished', function () {
    if (!ready) {
        ready = true;
        window.__chartReady = true;
    }
});
fetch(api)
    .then(function (resp) { return resp.json(); })
    .then(function (data) { chart.setOption(option); })
    .catch(function (err) {
        document.title = 'RENDER_ERROR';
        console.error('统计数据加载失败', err);
    });
```

监听要在 setOption 之前注册，否则第一次渲染的 finished 可能错过。数据加载失败时改 document.title 并打 console，无头侧可以据此判失败。

两个图型的 option 拼装都在前端完成，接口的 items 直接映射成 ECharts 需要的数组：

```javascript
function dailyOption(items) {
    var dates = items.map(function (it) { return it.date; });
    var counts = items.map(function (it) { return it.orderCount; });
    var amounts = items.map(function (it) { return it.amount; });
    return {
        tooltip: { trigger: 'axis' },
        legend: { data: ['订单量', '销售额'] },
        xAxis: { type: 'category', data: dates },
        yAxis: [
            { type: 'value', name: '订单量' },
            { type: 'value', name: '销售额（元）' }
        ],
        series: [
            { name: '订单量', type: 'bar', data: counts },
            { name: '销售额', type: 'line', yAxisIndex: 1, data: amounts, smooth: true }
        ]
    };
}

function categoryOption(items) {
    return {
        series: [{
            name: '类目销售额',
            type: 'pie',
            radius: ['35%', '62%'],
            data: items.map(function (it) {
                return { name: it.category, value: it.amount };
            })
        }]
    };
}
```

双 y 轴的柱线组合放订单量与销售额两个量纲，类目数据直接喂饼图的 name/value 对。视觉决策（颜色、图例位置、半径）全部在这一层，后端对此无感。

渲染验证我用 playwright 打开页面，确认真实画面存在：

```python
page.goto("http://127.0.0.1:18290/chart.html?type=daily&days=7", wait_until="networkidle")
page.wait_for_function("window.__chartReady === true", timeout=30000)
n_canvas = page.evaluate("document.querySelectorAll('#chart canvas').length")
n_series = page.evaluate("chart.getOption().series.length")
```

端到端验证实测输出 `canvas=1 series=2 xaxis_points=7`：画布挂载了 1 个 canvas，option 里 2 个 series，横轴 7 个数据点，与接口的 days=7 对齐。页面整页截图我存了档，柱线组合图完整可见。

![](https://static.xiongneng.me/echarts-dataflow-20260922074456.png)

## 服务端导出方案对比与选型

「服务端出 ECharts 的 PNG」这个需求有几条技术路线，我把它们摊在一张表里看了一圈：

| 方案 | 原理 | 现状 |
|---|---|---|
| PhantomJS 截图 | 老 C++ QtWebKit 无头浏览器 | 2018 年停止维护，不支 ES6+，ECharts 5 跑不稳，弃 |
| echarts-java 拼 option | JVM 内拼 option JSON | 只解决「生成 option」，渲染仍在浏览器，没有导出能力，弃 |
| JVM 内无头渲染 | HtmlUnit 等纯 Java 浏览器 | 不执行 Canvas，ECharts 画不出来，弃 |
| echarts SSR + zrender | ECharts 5.3+ 服务端 SVG 字符串 | 可行，但只出 SVG，转 PNG 还要另找 SVG 栅格化，样式与浏览器有差异 |
| 无头 Chromium 截图 | Playwright/Puppeteer 驱动真实浏览器 | 画面与浏览器完全一致，维护活跃，主流选择 |

挺有意思的是中间两条的出局原因：echarts-java 名字里带 java，看着最像「纯 JVM 解法」，但它只管拼 option，渲染还得靠浏览器；HtmlUnit 倒是纯 Java，却不执行 Canvas，ECharts 恰好画在 Canvas 上。最后一条是我选的：无头浏览器打开图表页，等渲染完成后对画布容器截屏。Playwright 与 Puppeteer 都能做，本工程的运行栈是 Java，Playwright 有 Java 版但依赖其自带的驱动分发体系；工程所在的机器上已有一套配好的 venv Python + Playwright，于是我选了更直接的组合：Java 通过 ProcessBuilder 拉起 venv python 的导出脚本，脚本驱动无头 Chromium 截屏，写临时文件，Java 读回字节流。

这条组合的分工：Java 侧管参数校验、超时重试、魔数校验、字节流响应这些工程化点；python 脚本管打开页面、等渲染、截屏这些浏览器动作。两边通过命令行参数与退出码约定，脚本失败时非零退出并在 stderr 带原因。

![](https://static.xiongneng.me/echarts-export-sequence-20260922074408.png)

## 导出实现：子进程、超时与字节流

导出管线的配置集中在 application.properties，四个开关全部可按部署机调整：

```properties
echarts.export.python=${ECHARTS_PYTHON}
echarts.export.script-path=scripts/export_png.py
echarts.export.timeout-seconds=45
echarts.export.max-attempts=2
```

python 解释器路径我没有写死在配置里，而是从环境变量 `ECHARTS_PYTHON` 取：Windows 下 setx、Linux 下 export，按部署环境注入就行，配置文件本身可以原样进仓库。脚本路径以工程根为工作目录的相对路径定位。注入走 ChartPngExporter 的构造器 `@Value`，占位符解析 Boot 自己就支持，不用我多写一行代码；测试里还可以把这套值覆盖成假脚本，专门验证失败分支。

导出参数用 record 承载，四个字段全部可省略：

```java
public record ExportRequest(String type, Integer days, Integer width, Integer height) {

    public ExportRequest {
        if (type == null || type.isBlank()) {
            type = "daily";
        }
        if (days == null || days <= 0) {
            days = 7;
        }
        if (width == null || width <= 0) {
            width = 900;
        }
        if (height == null || height <= 0) {
            height = 480;
        }
    }
}
```

组件声明为包装类型 Integer 而非基本类型，是 Jackson 3 的一个行为变化：record 构造器遇到缺失的 JSON 属性时不再静默补 0，反序列化直接失败，异常发生在参数解析阶段，控制器方法体拿不到执行权，默认错误页接管响应。包装类型允许缺省为 null，紧凑构造器里统一补业务默认值，参数解析与方法执行就都走得到。

取值范围校验独立成 validate 方法，在控制器入口一次性调用：

```java
public static final int MIN_SIZE = 200;
public static final int MAX_SIZE = 4000;

public void validate() {
    if (!"daily".equals(type) && !"category".equals(type)) {
        throw new IllegalArgumentException("type 只支持 daily 或 category，当前值：" + type);
    }
    if (width < MIN_SIZE || width > MAX_SIZE || height < MIN_SIZE || height > MAX_SIZE) {
        throw new IllegalArgumentException(
                "width/height 必须在 " + MIN_SIZE + "~" + MAX_SIZE + " 之间，当前：" + width + "x" + height);
    }
}
```

尺寸上下限是无头浏览器的资源保护：一次 4000x4000 的导出要占上百兆内存，放开了上限等于把服务端内存交给调用方支配。导出页地址也由参数对象拼装，`export=1` 让图表页进入纯图表模式：

```java
public String chartUrl(String baseUrl) {
    return baseUrl + "/chart.html?type=" + type
            + "&days=" + days
            + "&width=" + width
            + "&height=" + height
            + "&export=1";
}
```

python 导出脚本完整逻辑不足五十行：

```python
with sync_playwright() as pw:
    browser = pw.chromium.launch()
    page = browser.new_page(viewport={"width": args.width,
                                      "height": args.height + 40})
    page.goto(args.url, timeout=ms, wait_until="networkidle")
    page.wait_for_function("window.__chartReady === true", timeout=ms)
    page.wait_for_timeout(500)
    element = page.query_selector("#chart")
    element.screenshot(path=args.output, timeout=ms)
```

`wait_for_function` 等 `__chartReady` 标志位，渲染管线走完再截。截屏对象是 `#chart` 容器（元素级截屏），不是整页视口，PNG 的像素尺寸因此与请求参数严格一致。渲染完成后再给 500 毫秒缓冲，字体加载更稳。

Java 侧 ChartPngExporter 用 ProcessBuilder 拉起脚本：

```java
List<String> command = new ArrayList<>();
command.add(pythonExecutable);
command.add(scriptPath);
command.add("--url");
command.add(url);
command.add("--output");
command.add(output.toAbsolutePath().toString());
command.add("--width");
command.add(String.valueOf(request.width()));
Process process = new ProcessBuilder(command)
        .redirectErrorStream(true).start();
boolean finished = process.waitFor(timeoutSeconds + 10L, TimeUnit.SECONDS);
if (!finished) {
    process.destroyForcibly();
    throw new IOException("导出子进程超过 " + (timeoutSeconds + 10) + " 秒被强杀");
}
```

三个工程化点都在这一层：

**超时强杀。** 单次尝试有总超时，waitFor 超时后 destroyForcibly，无头 Chromium 卡死的场景不会拖住 Tomcat 线程。超时阈值来自配置 `echarts.export.timeout-seconds`（默认 45 秒），子进程等待在其基础上再加 10 秒余量，两层超时各自生效。

**失败重试。** 无头浏览器偶发崩溃（内存紧张、首启冷缓存）值得一次自动重试，`echarts.export.max-attempts` 默认 2。重试在进程级整体重跑，截屏是幂等操作，重试没有副作用。另外脚本输出重定向合并后统一读取，进程输出量很小（几十字节），不会塞满管道缓冲区；若自定义脚本输出大段日志，需要边跑边读，否则进程写管道阻塞，waitFor 永远等不到。

**魔数校验。** 读回的临时文件先验 PNG 魔数（`89 50 4E 47 0D 0A 1A 0A`）再返回，浏览器半路崩溃产出的残文件、python 报错时写出的文本，都进不了响应。魔数对得上，就是「这份字节流是合法 PNG」的铁证：

```java
private byte[] readVerifiedPng(Path output) throws IOException {
    byte[] bytes = Files.readAllBytes(output);
    if (bytes.length < 8) {
        throw new IOException("导出产物为空或过小（" + bytes.length + " 字节）");
    }
    for (int i = 0; i < PNG_MAGIC.length; i++) {
        if (bytes[i] != PNG_MAGIC[i]) {
            throw new IOException("导出产物不是合法 PNG（魔数不匹配）");
        }
    }
    return bytes;
}
```

选临时文件中转而不是 stdout 直传二进制，是我在 Windows 下踩过实坑的取舍：管道字节流在 Windows 上受编码与缓冲设置影响，二进制经 stdout 转手偶发被破坏；文件系统是两边都可靠的中转，代价是一次磁盘写读，24 KB 量级的图可以忽略。

重试与临时文件的生命周期收敛在 export 方法里：

```java
public byte[] export(ExportRequest request) {
    String url = request.chartUrl(ExportRequest.currentBaseUrl());
    Path output;
    try {
        output = Files.createTempFile("echarts-export-", ".png");
    } catch (IOException e) {
        throw new ChartExportException("创建导出临时文件失败", e);
    }
    try {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                runOnce(url, request, output);
                return readVerifiedPng(output);
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw new ChartExportException("导出重试 " + maxAttempts + " 次仍失败：" + lastError.getMessage(), lastError);
    } finally {
        Files.deleteIfExists(output);
    }
}
```

临时文件在 finally 里删除，成功失败都不留残文件；重试循环共用同一个输出路径，每次尝试整体覆盖。

导出 URL 从当前请求推导，端口不硬编码，测试环境随机端口也能工作：

```java
public static String currentBaseUrl() {
    HttpServletRequest request = ((ServletRequestAttributes)
            RequestContextHolder.getRequestAttributes()).getRequest();
    return request.getScheme() + "://" + request.getServerName()
            + ":" + request.getServerPort() + request.getContextPath();
}
```

控制器把两类失败分开：参数越界 400，浏览器侧失败 503，都用 problem+json：

```java
try {
    request.validate();
} catch (IllegalArgumentException e) {
    return problem(HttpStatus.BAD_REQUEST, "导出参数不合法", e.getMessage());
}
try {
    png = exporter.export(request);
} catch (ChartExportException e) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "导出失败",
            "图表导出暂不可用：" + e.getMessage());
}
```

503 的语义是「服务暂时不可用，稍后重试可能恢复」，与 400 的「参数改了再来」是两种不同的客户端动作。错误响应在方法内直接构造并显式指定 `MediaType.APPLICATION_PROBLEM_JSON`，不经过 `@ExceptionHandler` 转发，这个取舍来自我撞过的一次坑，过程放在避坑指南里说。

## 完整案例与端到端验证

我把七组验证写进了同一个脚本，一轮跑完，全部通过：

```
A  GET  /api/stats/daily?days=7   → 200，ISO 日期，days:7
B  GET  /api/stats/category       → 200，5 类目，total:5
C  GET  /chart.html               → 200，ECharts CDN 引用 1 处
D  playwright 打开 chart.html     → canvas=1 series=2 xaxis_points=7
E  POST /api/export/png daily     → 200，24943 字节，魔数 OK，900x480
F  POST /api/export/png category  → 200，魔数 OK，720x480
G  POST type=radar                → 400 problem+json
应用日志 ERROR 行数：0
```

E 组的 PNG 用二进制头做了双重证据：魔数 `89504e470d0a1a0a` 与 IHDR 段解析出的像素尺寸 900x480，与请求参数一致。G 组的 400 响应体长这样：

```json
{"detail":"type 只支持 daily 或 category，当前值：radar",
 "instance":"/api/export/png","status":400,"title":"导出参数不合法"}
```

Content-Type 是 `application/problem+json`，detail 带具体原因，客户端能直接展示给调用方。

测试分两层，统计接口用 MockMvc 切片断言 JSON 契约：

```java
@SpringBootTest
@AutoConfigureMockMvc
class StatsApiTest {

    @Autowired
    private MockMvcTester mockMvc;

    @Test
    void dailyReturnsIsoDatesAndNumbers() {
        MvcTestResult result = mockMvc.get().uri("/api/stats/daily").exchange();
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        result.assertThat().hasStatusOk();
        assertThat(body).contains("\"days\":7");
        assertThat(body).containsPattern("\"date\":\"2026-\\d{2}-\\d{2}\"");
        assertThat(body).contains("\"orderCount\"");
    }
}
```

导出接口必须起真实服务，无头浏览器要发真 HTTP：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PngExportIntegrationTest {

    @Autowired
    private WebServerApplicationContext context;

    @Test
    void exportReturnsPngWithRequestedSize() throws Exception {
        HttpResponse<byte[]> response = post(
                baseUrl() + "/api/export/png",
                "{\"type\":\"daily\",\"days\":7,\"width\":900,\"height\":480}");
        assertThat(response.statusCode()).isEqualTo(200);
        byte[] png = response.body();
        assertThat(png.length).isGreaterThan(1000);
        for (int i = 0; i < 8; i++) {
            assertThat(png[i]).isEqualTo(PNG_MAGIC[i]);
        }
        int width = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16)
                | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        assertThat(width).isEqualTo(900);
    }
}
```

端口从 WebServerApplicationContext 取，RANDOM_PORT 下导出 URL 依然拼得对。IHDR 的宽度字段按大端解析后断言等于请求值，尺寸从参数一路贯通到 PNG 二进制。mvn test 一轮 5 个测试全部通过，其中导出端到端测试依赖本机 venv 的 playwright，纯 JDK 环境跑不了这一条，这一点我在工程 README 里也写明了。

![](https://static.xiongneng.me/echarts-verify-panel-20260920233000.png)

## 避坑指南

**坑一，record 组件用基本类型接可省略的 JSON 字段。** Jackson 3 对 record 构造器缺失属性不再静默补 0，反序列化直接失败，且异常发生在参数解析阶段，控制器方法体与 `@ExceptionHandler` 都没有执行机会，响应由默认错误页接管。可省略字段用包装类型，紧凑构造器补默认值。

**坑二，`@ExceptionHandler` 里返回 ProblemDetail 被默认错误页接管。** Framework 7.0.9 上我实测撞过：控制器内抛 IllegalArgumentException，类内 `@ExceptionHandler` 返回 `ResponseEntity<ProblemDetail>`，得到的却是 Boot 默认错误体（timestamp/status/error/path，application/json）。改为控制器方法内 try/catch 直接构造 problem 响应并显式指定 `MediaType.APPLICATION_PROBLEM_JSON`，媒体类型与结构都正确。problem+json 的 Content-Type 必须显式给，靠推断拿到的可能是 application/json。

**坑三，固定 sleep 等渲染截到半成品。** ECharts 渲染是异步管线，sleep 固定时长在慢机器或大数据量下等不够，快机器上白等。用 finished 事件置 `window.__chartReady`，无头侧 `wait_for_function` 等标志位，渲染完成才有信号。

**坑四，二进制走 stdout 子进程管道。** Windows 上 python 子进程的 stdout 字节流受编码与缓冲影响，PNG 偶发被破坏且难排查。导出脚本写临时文件，Java 读文件再校验魔数，多一次磁盘写读换来确定性。

**坑五，截屏对象用视口而不是图表容器。** 视口截屏的尺寸等于浏览器窗口，带页面留白，与请求的 width/height 对不上。对 `#chart` 容器做元素级截屏，PNG 的 IHDR 尺寸与参数严格一致，验收时解析 IHDR 即可断言。

**坑六，CDN 引用写浮动 tag。** `echarts@5` 会随缓存与发布漂到 5.x 任意小版本，option 行为可能变化。锁小版本 `echarts@5.6.0`，升级走显式变更。

**坑七，接口直接返回 ECharts option。** option 里含颜色、动画、图例位置等渲染决策，前端与导出端被迫接受同一套视觉；换图库时后端跟着改。接口出纯数据，option 留在渲染端拼。

**坑八，无头浏览器没有资源上限。** 每次导出拉起一个 Chromium 进程，内存百兆量级。公网接口要在导出入口加并发上限（信号量或线程池隔离），超时强杀必须有 destroyForcibly 兜住，否则卡死的浏览器进程会累积。

**坑九，子进程输出不读导致 waitFor 永久阻塞。** ProcessBuilder 的管道缓冲区有限，子进程输出超过缓冲又没人读，进程写管道阻塞，waitFor 等不到退出。你遇到「进程明明该结束了却卡在 waitFor」，九成是这个原因。本工程脚本输出量小，重定向合并后 waitFor 之后再读；自定义脚本输出大段日志时改成边跑边读。

**坑十，统计数据不带随机种子。** 每次请求现算的随机数让同一天的两次导出图形不同，定时发群的场景里图形天天变样，群里的同事一眼就能看出来，这就闹笑话了。种子取日期，同一天内序列一致，导出可复现，验证与截图都有据可查。

**坑十一，「今天」的口径在跨时区部署时漂移。** LocalDate.now() 取 JVM 默认时区的当天，容器时区是 UTC 时，北京时间早上八点前「昨天」还是前天，日报图的日期与群消息的日期对不上。容器环境显式设 `-Duser.timezone=Asia/Shanghai` 或 `TZ=Asia/Shanghai`，日期口径与服务时间对齐。

**坑十二，高分屏下导出图发虚。** deviceScaleFactor 默认为 1，截图像素与 CSS 像素一一对应，retina 屏用户看会觉得边缘毛糙。把 playwright 的 device_scale_factor 设为 2 可以得到两倍分辨率的清晰图，代价是像素尺寸翻倍，验收 IHDR 时要按 2 倍断言。

## 小结

服务端出图表 PNG，选型上我建议直接走无头浏览器渲染后截屏这条路：画面与浏览器完全一致，维护活跃，遇到问题也好搜。echarts SSR 那条路线留给只要矢量图的场景，纯 JVM 方案在 Canvas 面前都不成立。结构上把数据与渲染分开：统计接口只出纯数据，option 由渲染端拼装，同一份接口喂浏览器与无头导出两个消费端，一个 chart.html 同时服务人与机器。工程化的四样底线别省：超时强杀、失败重试、魔数校验、400 与 503 分语义，都收在 Java 侧，python 脚本保持薄。

没解决的事也直说：导出接口的并发上限我还没有加，每次导出要拉起一个 Chromium 进程，公网环境得用信号量或线程池隔离兜住，这段逻辑我留了口子没写；高分屏的 deviceScaleFactor=2 思路在坑十二里，工程里也没默认开启，开启后 IHDR 的验收断言要跟着按倍数改。这两处等我真有公网部署需求时再补。

## 参考链接

- [Apache ECharts - 官方文档](https://echarts.apache.org/handbook/)：option 配置、finished 事件与服务端渲染章节
- [Spring Boot 4.1 Reference - Servlet Web Applications](https://docs.spring.io/spring-boot/4.1/reference/web/servlet.html)：静态资源托管与 Spring MVC 配置
- [Spring Framework 7 - Problem Details](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)：ProblemDetail 与 REST 异常响应
- [Playwright for Python](https://playwright.dev/python/docs/intro)：sync API、page.screenshot 与 wait_for_function
- [PNG Specification (RFC 2083)](https://www.rfc-editor.org/rfc/rfc2083)：文件结构与 IHDR 数据块布局
