---
title: SpringBoot4系列30 - 用Spring AI把业务接口暴露成MCP工具
slug: sb4-ai-mcp
date: 2026-09-21 12:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, SpringAI, MCP, LLM ]
draft: false
---

客服后台常有这类请求：「ORD-1001 到哪一步了」「客户 C-001 最近买了什么」「这周下了多少单」。对应的查询接口系统里都有，但入口藏在运营后台的几个页面里，客服要在页面之间跳转、记订单号、再拼出答案。说实话，我一开始想的是给客服再做一个问答页面，后来发现大型语言模型宿主（Claude Desktop、Cursor、Claude Code）已经能理解这类自然语言问题，也能在理解之后调用外部函数，缺的只是一个标准协议，把订单系统的查询方法递到模型手里。Model Context Protocol（MCP）就是这个协议。

这一篇我把订单域的 4 个业务方法用 Spring AI 2.0.1 的注解暴露成 MCP 工具，跑在 Spring Boot 4.1.1（Spring Framework 7.0.9、JDK 21.0.10）上，全程不依赖任何 LLM API key。验证方式是 MCP 协议客户端直连：initialize 握手、tools/list、tools/call 我都实测了一遍，官方 Python SDK 与手写 JSON-RPC 两个客户端断言的是同一轮实测留下的原始记录。配套工程 springboot4-ai-mcp，端口 18300，正文全部数字来自同一轮端到端验证。

![](https://static.xiongneng.me/aimcp-architecture-20260922080751.png)

## MCP 是什么：server、client 与 tool 三个角色

MCP 在 2024 年底由 Anthropic 开放，2025-06-18 版规范已经是多家厂商共同遵循的标准。协议把参与者分成三个角色：server 是工具提供方，把一组带名字、带描述、带参数 schema 的函数挂出去；client 是协议对端的执行者，嵌在宿主应用（Claude Desktop、Cursor 这类产品）里，负责与 server 建立连接、转发请求；模型本身不直接说话，宿主把 server 提供的工具清单注入模型上下文，模型决定「调哪个工具、填什么参数」，client 把这个决定转成协议请求发给 server，拿到结果再交回模型继续推理。

server 侧要实现的协议方法只有两个核心：`tools/list` 返回工具清单，`tools/call` 执行一次调用。连接建立前还有一个 `initialize` 握手，双方交换协议版本与能力声明，服务器在响应里给出名称与版本。这套形状与 LLM 的函数调用（function calling）完全对齐：JSON Schema 描述参数，文本描述解释用途。

协议报文走 JSON-RPC 2.0，一次 `tools/call` 的请求体形如 `{"name": "get_order_by_id", "arguments": {"orderId": "ORD-1001"}}`，与调用一个本地函数没有语义差别。想明白这一点之后，MCP 的定位就清楚了：它不搬运模型，也不搬运数据，只搬运「函数签名加一次调用」。REST API 面向的是写代码的集成方，路径、动词、分页、错误码都是给人设计的；MCP 工具面向的是做选择的模型，一切元数据都服务于「让模型在几十个候选里挑对这一个，并把参数填对」。

适用边界也要先划清。需求是「自然语言问答加上现成查询动作」时，MCP 是合适的通道；需求是固定的报表推送、毫秒级延迟的交易路径，直接走 API 或消息队列更合适，多一层模型推理只添延迟与不确定性。工具适合包裹「一次问答里会用到的原子动作」，包裹不了整条业务流程的编排，编排仍是服务端代码的事。

传输层有两种主流选择。stdio 传输把 server 当作宿主拉起的本地子进程，协议报文走标准输入输出，适合文件系统访问、本地脚本这类与机器绑定的工具；Streamable HTTP 与 SSE 传输面向常驻服务，宿主通过 HTTP 端点远程连接，请求发到 `POST /mcp`，响应可以是普通 JSON 也可以是事件流。订单查询是典型的常驻业务服务，数据在公司内网，走 Streamable HTTP，宿主在谁的电脑上都能连。

Streamable HTTP 与 SSE 在 Spring AI 2.0 里的关系挺有意思，我把这条沿革翻了一遍：2025-03-26 版规范之前，远程传输只有 SSE，GET `/sse` 建立下行事件流、POST `/mcp/message` 发送上行报文，两条通道各管一头；Streamable HTTP 加入之后，单个 POST 端点既能收请求也能按需返回流，会话凭证放响应头，服务器还可以无状态响应。Spring AI 2.0 把默认协议切到了 STREAMABLE，SSE 端点与相关配置类（`McpServerSseProperties`）都标了 deprecated。我的选择是新工程直接用默认值，旧宿主要求 SSE 时再显式把 `protocol` 切回 `sse`。

![](https://static.xiongneng.me/aimcp-sequence-20260922080751.png)

## 依赖与自动配置：先核对事实再写代码

Spring AI 有两条在维护的版本线：1.1.x 面向 Boot 3.x，2.0.x 面向 Boot 4.x。我的习惯是先核对再动手：打开 Maven Central 上 `spring-ai-starter-mcp-server-webmvc:2.0.1` 的 POM，它明确依赖 `spring-boot-starter-web:4.1.1`，与本系列 parent 同一个版本，BOM 就定在 spring-ai-bom 2.0.1，它同时托管 MCP Java SDK（`io.modelcontextprotocol.sdk:mcp-core:2.0.1`）。

这条核对路径值得写成习惯：任何第三方 starter 接进 Boot 工程之前，打开它的 POM 看一行，确认它依赖的 spring-boot-starter-* 是哪个版本。版本线错配在启动期就会暴露（自动配置类找不到、Framework 版本冲突），我见过的错配九成是没看这一眼，暴露方式是一长串堆栈，定位成本远高于事先看一眼 POM。

工程依赖只有两项主体：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>2.0.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    </dependency>
</dependencies>
```

starter 按传输方式分三个坐标，选型时对照部署形态挑：

| 坐标 | 传输 | 适用形态 |
|---|---|---|
| `spring-ai-starter-mcp-server-webmvc` | Streamable HTTP / SSE | 常驻 Web 服务，宿主远程连 |
| `spring-ai-starter-mcp-server-webflux` | Streamable HTTP / SSE | 响应式栈的常驻服务 |
| `spring-ai-starter-mcp-server` | stdio | 宿主拉起的本地子进程 |

常驻服务选 webmvc 变体，它传递引入三样东西：`spring-ai-mcp-annotations`（注解所在模块）、`mcp-spring-webmvc`（传输适配）、MCP Java SDK。

jar 解包核对出的自动配置分两层。公共层在 `org.springframework.ai.mcp.server.common.autoconfigure` 包，`McpServerAutoConfiguration` 装配 MCP server 核心，`McpServerAnnotationScannerAutoConfiguration` 扫描业务 bean 上的注解方法；WebMVC 层在 `org.springframework.ai.mcp.server.webmvc.autoconfigure` 包，按协议变体注册 `McpServerStreamableHttpWebMvcAutoConfiguration`、`McpServerSseWebMvcAutoConfiguration`、`McpServerStatelessWebMvcAutoConfiguration` 三选一。配置前缀是 `spring.ai.mcp.server`，javap 核对出的属性包括 name、version、instructions、type（SYNC/ASYNC，默认 SYNC）、protocol（SSE/STREAMABLE/STATELESS，2.0 默认 STREAMABLE）、capabilities.tool，Streamable HTTP 端点默认 `/mcp`。工程配置如下：

```yaml
spring:
  ai:
    mcp:
      server:
        name: order-mcp-server
        version: 1.0.0
        instructions: 订单域工具集：按ID查订单、按客户查订单列表、创建订单、近7天订单汇总。
        protocol: streamable
        type: sync
        capabilities:
          tool: true
```

`instructions` 字段会随握手响应发给宿主，作用是给模型一句话说明「这台 server 里有什么」。我把它写成四个工具的一句话清单，模型在握手阶段就能拿到这份概览。

![](https://static.xiongneng.me/aimcp-components-20260922080751.png)

## 注解暴露工具：@McpTool 加在业务方法上

订单域保持普通三层结构：`OrderService` 持有内存数据与业务方法，`OrderMcpTools` 是工具层，注解加在这里，业务代码一个注解都不碰。这是我最看重的一点：接入 MCP 不要求动任何已有服务代码。

```java
@Service
public class OrderMcpTools {

    private final OrderService orderService;

    public OrderMcpTools(OrderService orderService) {
        this.orderService = orderService;
    }

    @McpTool(
            name = "get_order_by_id",
            description = "按订单号查询单笔订单详情，返回订单ID、客户ID、商品、金额、状态与下单时间。用户给出具体订单号（如 ORD-1001）时使用。"
    )
    public Order getOrderById(
            @McpToolParam(description = "订单号，格式 ORD-数字，例如 ORD-1001", required = true)
            String orderId
    ) {
        return orderService.getOrderById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));
    }

    @McpTool(
            name = "create_order",
            description = "为新客户或老客户创建一笔新订单，需要客户ID、商品名与金额三项，创建成功返回完整订单（状态为 CREATED）。"
    )
    public Order createOrder(
            @McpToolParam(description = "客户ID，例如 C-001", required = true) String customerId,
            @McpToolParam(description = "商品名称，例如 无线耳机", required = true) String product,
            @McpToolParam(description = "订单金额（元），例如 199.00", required = true) BigDecimal amount
    ) {
        return orderService.createOrder(customerId, product, amount);
    }
}
```

`@McpTool` 的包名是 `org.springframework.ai.mcp.annotation`，来自 `spring-ai-mcp-annotations` 模块，属性经 javap 核对有六个：name、description、title、annotations、generateOutputSchema、metaProvider，日常只用 name 与 description 两个。参数级说明用 `@McpToolParam`，只接受 description 与 required。方法返回的 record 直接按 Jackson 序列化进工具结果。

订单实体与另外两个查询工具的形状如下：

```java
public record Order(
        String id,
        String customerId,
        String product,
        BigDecimal amount,
        String status,
        LocalDateTime createdAt
) {
}

@McpTool(
        name = "list_orders_by_customer",
        description = "按客户ID查询该客户的全部订单列表，按下单时间倒序返回。想看某个客户买过什么、下过几单时使用。"
)
public List<Order> listOrdersByCustomer(
        @McpToolParam(description = "客户ID，格式 C-数字，例如 C-001", required = true)
        String customerId
) {
    return orderService.listOrdersByCustomer(customerId);
}
```

返回类型选 record 有一个额外好处：字段清单就是返回结构的自我说明，模型拿到结果后引用字段名作答，不会引用不存在的东西。我的做法是让字段名与 description 里列出的名字保持一致（描述里说「返回订单ID、客户ID」，record 组件就叫 id、customerId），模型对照两边不会错位。

第四个工具「近 7 天订单汇总」展示了组合形态：模型要回答「这周订单情况」需要算时间窗、筛选、求和、数总数，每一步单独成一个工具就是四次调用、四次上下文往返；把整段逻辑封进一个工具，一次调用返回统计结果。

```java
@McpTool(
        name = "weekly_order_summary",
        description = "查询近 7 天订单汇总：近 7 天订单数、近 7 天订单总金额、历史订单总数。用户问最近一周订单情况、销售概况时使用，无需任何参数。"
)
public OrderService.WeeklySummary weeklyOrderSummary() {
    return orderService.weeklySummary();
}
```

扫描与注册的机制值得一笔：`McpServerAnnotationScannerAutoConfiguration` 通过一个 BeanPostProcessor 收集所有带 `@McpTool` 方法的 bean，为每个 bean 生成一个 `SyncMcpToolProvider`，provider 把每个方法转成 MCP Java SDK 的 `SyncToolSpecification`（工具元数据加一个执行函数）；`McpServerAutoConfiguration` 再把这些 specification 注册进 server。注解式与编程式最终汇入同一条路，手工构造 `ToolCallback` 或 `SyncToolSpecification` 的编程式写法仍然可用，区别只是元数据写在哪。对绝大多数业务方法，注解式的信息密度已经够用。

编程式的形状用一段对照看清：

```java
@Bean
public ToolCallbackProvider manualToolProvider(OrderService orderService) {
    var tool = McpSchema.Tool.builder()
            .name("get_order_by_id")
            .description("按订单号查询单笔订单详情……")
            .inputSchema(JSON_SCHEMA_STRING)
            .build();
    var spec = McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> {
                String orderId = (String) request.arguments().get("orderId");
                Order order = orderService.getOrderById(orderId).orElseThrow();
                return McpSchema.CallToolResult.builder()
                        .addTextContent(toJson(order))
                        .build();
            })
            .build();
    return new SyncMcpToolProvider(List.of());
}
```

手工注册要自己写 JSON Schema 字符串、自己做参数抽取、自己做序列化，三件事在注解式里分别由方法签名、`@McpToolParam` 和框架序列化接管。我试写过一遍手写 schema，少一个引号就是一次调用失败，注解式把这类笔误从工程里消掉了。工具行为需要动态元数据（比如按租户隐藏工具）时再回到编程式，其余场景注解优先。

## 工具设计要领：description 和参数命名是给模型看的

工具清单注入模型上下文后，模型挑选工具的依据只有三样：工具名、description、参数 schema。这三样是接口文档，读者是模型。同一个订单域，两种写法的差别可以用一组对比看清：

```text
❌ @McpTool(name = "queryOrder", description = "查询订单")
❌ @McpTool(name = "orderInfo2", description = "订单信息查询工具，本工具用于查询订单相关信息")

✅ @McpTool(name = "get_order_by_id",
          description = "按订单号查询单笔订单详情，返回订单ID、客户ID、商品、金额、状态与下单时间。用户给出具体订单号（如 ORD-1001）时使用。")
```

第一种的三个字没有区分度，模型无法判断它与「查订单列表」的差异；第二种是同义反复，装作有信息量；第三种给触发条件、给返回清单，模型在四个工具之间做选择时靠的就是这些字。参数描述同理，`orderId` 的描述写「格式 ORD-数字，例如 ORD-1001」，模型见到用户说「查一下 1001」也知道该拼成 `ORD-1001` 再传入。

工具名用蛇形加动词开头（`get_order_by_id`、`create_order`），动词对齐操作语义：get 查单个、list 查集合、create 写入、summary 聚合。模型对这些动词的分辨比对中文短语更稳，我给自己定的规矩是同一域内动词保持一套词表，不混用 query/find/fetch 表达同一动作。

参数类型也有讲究。`BigDecimal` 生成 `number` 类型的 schema，客户端传字符串会在服务器侧被 JSON Schema 校验拒绝，实测报错文本是「/amount: 已找到 string，必须是 number」。模型传错参数类型时读到这条错误能自行改参重试，但更经济的做法是选模型不容易传错的类型，能收窄就收窄；枚举语义用描述枚举出合法值（「状态：CREATED、PAID、SHIPPED」），比放任自由文本准得多。

组合工具是工具层最值钱的一类设计。模型要回答「这周订单情况」需要算时间窗、筛选、求和、数总数，每一步单独成一个工具就是四次调用、四次上下文往返；把整段逻辑封进 `weekly_order_summary`，一次调用返回统计结果。判断标准是调用频率：一个问题要连调三个以上工具时，把这段流程收进一个工具。

工具数量同样要克制。每个工具的名称、描述、schema 都占宿主的上下文窗口，也都在拉高模型选错工具的概率。一个 service 有 20 个方法，不等于要暴露 20 个工具；按「客服会问什么」圈定清单，低频操作留在后台页面里。

描述质量有一道低成本自检：把 tools/list 的 JSON 原样打印出来，遮住工具名只看描述，试着判断每个工具「什么时候该用」。我拿这招检查过自己的清单，判断不出来的那个，模型也一样判断不出来。这段 JSON 就是模型看到的一切，检查工具层等价于检查这份文本。

## 协议实测：握手、tools/list、tools/call

MCP server 侧的验证不需要 LLM 参与：任何 MCP 客户端都能按协议直连，我觉得这比模拟一个模型更接近真实使用。配套工程的验证脚本起服务、跑客户端、关服务，一轮完成。官方 Python SDK 客户端（mcp 2.2.0）走完整链路：

```python
async with streamable_http_client("http://127.0.0.1:18300/mcp") as (read, write):
    async with ClientSession(read, write) as session:
        info = await session.initialize()
        listed = await session.list_tools()
        res = await session.call_tool("get_order_by_id", {"orderId": "ORD-1001"})
```

三行调用分别对应协议的三个阶段：initialize 完成握手并拿到 serverInfo 与 instructions，list_tools 发出 tools/list 拿到工具清单与 schema，call_tool 发出 tools/call 拿到执行结果。断言写在这三段之间：工具数量等于 4、名称集合逐个比对、描述非空、查询结果的 id 与 customerId 符合种子数据。

手写客户端用 urllib 直接 POST JSON-RPC，把协议细节摊开。核心的 rpc 函数二十行：

```python
def rpc(method, params=None, notify=False, session=None):
    body = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        body["params"] = params
    if not notify:
        _id[0] += 1
        body["id"] = _id[0]
    req = urllib.request.Request(URL, json.dumps(body).encode("utf-8"), HEADERS)
    if session:
        req.add_header("mcp-session-id", session)
    with urllib.request.urlopen(req, timeout=30) as resp:
        sid = resp.headers.get("mcp-session-id")
        raw = resp.read().decode("utf-8")
    # 解析普通 JSON 或 SSE 流（data: 行），略
    return payload, sid
```

initialize 请求带协议版本 `2025-06-18` 与客户端信息，响应头里的 `mcp-session-id` 是后续请求的会话凭证；`notifications/initialized` 是一个无 id 的通知，服务器返回 202 空体。这段代码还有一个用处是排障参照：宿主连不上时，我用 curl 或这段脚本绕开宿主直接打协议，能把问题边界切在「server 侧」与「宿主侧」之间。两个客户端的断言全部通过，同一份验证脚本打出来的原始记录在下面。

initialize 握手返回的 serverInfo 与配置一致：

```json
{
  "protocolVersion": "2025-06-18",
  "serverInfo": {"name": "order-mcp-server", "version": "1.0.0"}
}
```

tools/list 返回 4 个工具，名称与描述齐全，`get_order_by_id` 的参数 schema 如下：

```json
{
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string",
      "description": "订单号，格式 ORD-数字，例如 ORD-1001"
    }
  },
  "required": ["orderId"]
}
```

tools/call 查种子订单 ORD-1001，返回业务方法的原样序列化：

```json
{
  "id": "ORD-1001", "customerId": "C-001", "product": "机械键盘",
  "amount": 399.0, "status": "PAID", "createdAt": "2026-09-20T03:45:42.6059372"
}
```

创建订单后回读验证写入生效：`create_order` 传客户 C-007、商品机械臂、金额 4500.00，返回新订单 ORD-1004（状态 CREATED），再调 `get_order_by_id` 用新单号查到同一条数据。组合工具 `weekly_order_summary` 在建单之后调用，返回近 7 天 3 笔、近 7 天金额 6198.0、历史总数 4，与种子数据加新单的口径一致。

有一处反直觉的结果值得单独说：整轮跑完应用日志 ERROR 0 行，而探活的 GET 请求打 `/mcp` 返回的是 400。按「4xx 就是服务没起」的老经验，第一眼很容易看岔，我一开始也愣了一下，细看协议才确认这恰恰是端点已挂载的铁证：Streamable HTTP 端点只接受协议要求的 POST。这轮实测我留了底，上面的每个数字都能在同一份运行记录里对上。

![](https://static.xiongneng.me/aimcp-verify-panel-20260921093000.png)

## 接入宿主：一段配置的事

server 跑起来之后，宿主侧只需要一段配置。Cursor 的 mcp.json 直接指向 HTTP 端点：

```json
{
  "mcpServers": {
    "order-mcp-server": {
      "url": "http://localhost:18300/mcp"
    }
  }
}
```

Claude Desktop 目前以 stdio 为主，用官方 `mcp-remote` 桥接把远程端点转成本地子进程：

```json
{
  "mcpServers": {
    "order-mcp-server": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:18300/mcp"]
    }
  }
}
```

配置生效后，在宿主里问「ORD-1001 是什么订单」，模型从工具清单里挑出 `get_order_by_id`，填参调用，把返回的 JSON 组织成回答；问「最近一周订单情况」命中 `weekly_order_summary`，一次调用出汇总。工具描述写得越清楚，这个选择过程越少出错。

宿主界面能看到连接状态与工具清单：Cursor 在设置页的 MCP 面板里列出已连接 server 与每个 server 暴露的工具数，Claude Desktop 在对话输入框的工具入口里展示同类信息。接入排障的第一步就是看这个清单：清单里有 4 个工具说明握手与 tools/list 已通，问题出在调用环节；清单是空的先查端点 URL 与网络，不急着怀疑工具实现。

Claude Code 用户走命令行注册，一行完成：

```bash
claude mcp add --transport http order-mcp-server http://localhost:18300/mcp
```

配置后新建一个会话，先问一句「你能看到哪些订单工具」，宿主会列出 `get_order_by_id`、`list_orders_by_customer`、`create_order`、`weekly_order_summary` 四个名字，列出四个名字说明握手与 tools/list 已经打通，再问业务问题就能看到工具调用发生。

三种宿主连同一个 server，工具清单来自同一份 tools/list 响应，描述写得如何，三种宿主里的表现就如何。

## 测试设施：不起宿主也能断言工具注册

协议实测覆盖端到端，日常回归还需要跑得快的单测。装配完成后，容器里有一个 `McpSyncServer` bean（MCP Java SDK 的同步 server 门面），它的 `listTools()` 返回已注册的工具元数据，注解扫描是否生效、名称描述是否齐全，一个断言就能钉住：

```java
@SpringBootTest
class McpToolRegistrationTest {

    @Autowired
    private McpSyncServer mcpSyncServer;

    @Test
    void toolsAreRegistered() {
        List<McpSchema.Tool> tools = mcpSyncServer.listTools();

        assertThat(tools).hasSize(4);
        Set<String> names = tools.stream()
                .map(McpSchema.Tool::name)
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(
                "get_order_by_id", "list_orders_by_customer",
                "create_order", "weekly_order_summary");
    }
}
```

第二个断言盯 description：遍历全部工具，逐个检查描述非空且长度超过 10 个字符。这条断言防的是最隐蔽的回归：有人新增工具忘了写描述，宿主里表现为模型总选不对工具，排查半天定位不到描述为空这一行。把「描述非空」做成测试，问题在 CI 阶段就拦截。

工具背后的业务逻辑照常测服务层：创建订单后按 ID 查到、按客户查列表的排序正确、汇总的数字与种子数据吻合。唯一要处理的是状态污染，我测试就栽在这里：内存服务是单例，创建类用例写入的数据会影响后续汇总断言，汇总数字莫名多了新单，排查半天才想到是上一个用例建的。测试类加 `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)`，每个方法跑完重建上下文，用例之间互不污染。本工程 mvn test 一轮 6 个测试（上下文加载 1、工具注册 2、业务逻辑 3）全部通过，纯 JVM 环境，不依赖网络与任何 API key。

## 避坑指南

**坑一，版本线错配。** spring-ai 1.1.x 是 Boot 3 线的版本，Boot 4 工程引 1.1.x 会出现自动配置类缺失或 Spring Framework 版本冲突。核对方式：到 Maven Central 看目标 starter 的 POM，`spring-ai-starter-mcp-server-webmvc:2.0.1` 直接依赖 `spring-boot-starter-web:4.1.1`，这条依赖线与 parent 4.1.1 同源。

**坑二，starter 传输选错。** stdio starter 适合宿主拉起的本地工具（文件系统、本地脚本），常驻业务服务选 webmvc 或 webflux 变体。stdio 模式下还有一个硬约束：标准输出被协议报文占用，日志必须走文件或 stderr，任何 System.out 打印都会破坏协议流。

**坑三，Spring AI 2.0.1 的 starter 仍依赖旧坐标。** 它的 POM 里写的是 `spring-boot-starter-web`，这个坐标在 Boot 4 已标记弃用。功能不受影响（自动配置按模块装配），工程自己的依赖声明统一写 `spring-boot-starter-webmvc`，两个 starter 共存于 classpath 没有问题。

**坑四，description 为空或写成方法注释。** 描述是模型选工具的唯一依据，「查订单」这种三个字的描述在四个工具之间没有区分度。写清触发条件与返回内容，参照本篇的写法：什么时候用、返回什么。

**坑五，金额类参数与 number schema。** `BigDecimal` 生成 `number` 类型，客户端传 `"4500.00"` 字符串被 JSON Schema 校验拒绝，实测错误文本「/amount: 已找到 string，必须是 number」。模型能读到错误并重试，但参数设计尽量选不易传错的类型，文档示例与 schema 类型保持一致，不然联调时会闹笑话。

**坑六，GET /mcp 返回 4xx 误判为服务没起。** Streamable HTTP 端点只接受 POST，且 Accept 头必须同时带 `application/json` 与 `text/event-stream`。探活时 GET 返回 400 说明端点已挂载；手写客户端漏带 Accept 头同样拿不到响应。

**坑七，会话凭证失效。** 每次握手拿到的 `mcp-session-id` 绑定一次会话，server 重启后旧凭证全部失效，宿主需要重新 initialize。排查「刚重启就连不上」的现象，先看请求头里的会话 ID 是不是旧的。

**坑八，工具方法抛异常的行为。** 抛出的异常被转成 `is_error` 的工具结果返回给模型，HTTP 层面不会出现 500。可预期的错误（订单不存在、参数越界）写成信息明确的异常文本，模型读到后能改参重试；把错误信息写成堆栈，模型与用户都拿不到可用的提示。

**坑九，内存服务在测试间串状态。** 工具调用会写数据，单测里 create 影响后面 summary 的断言。给测试类加 `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)`，每个方法跑完重建上下文，用例之间互不污染。

**坑十，把 service 方法原样全量暴露。** 20 个方法就是 20 份描述与 schema 进模型上下文，token 成本与选错率同时上升。按「客服会问什么」圈定工具集，多步高频操作封成组合工具，本篇 4 个工具服务一类角色，就是刻意的收敛。

**坑十一，时间窗口径由 server 时钟决定。** `weekly_order_summary` 里的「近 7 天」用 `LocalDateTime.now()` 减 7 天，JVM 默认时区是 UTC 时，北京时间早上八点前「近 7 天」的边界比业务口径早八小时。容器部署显式设 `-Duser.timezone=Asia/Shanghai`，工具描述里同时写清口径（「按服务器时间的自然日计算」），模型转述给用户时口径一致。

**坑十二，公网部署把 MCP 端点裸奔。** Streamable HTTP 端点默认无鉴权，谁拿到 URL 谁就能调工具，写类工具等于开放了数据入口。上公网前至少加一层防护：网关层校验 API key 或 OAuth2 资源服务器（Boot 的 security-oauth2-resource-server starter 按常规方式配即可），内网部署也要限制到可信网段。

## 小结

我的选型建议就一句话：把 MCP server 能力当成一个 starter 的事来做。Boot 工程加一个 BOM、一个 webmvc starter、几个注解，业务代码保持普通三层结构，工具就挂出去了，值得花心思的地方在文本不在代码：name、description、参数描述就是模型的接口文档，把触发条件与返回清单写清楚，比调用侧打任何补丁都管用。验证也不必等模型，MCP 客户端按协议直连就是最真实的取证，日常回归再补一层 `McpSyncServer` 的注册断言就够了。

没解决的事也有：鉴权与多租户。Streamable HTTP 端点默认无鉴权，坑十二里我只给到网关层校验的方向；工具粒度的租户隔离（同一个 server 按调用方隐藏部分工具）要回到编程式元数据那一路，这块我没有实测场景，测不了的东西我不写。等宿主接入从个人工具走向团队共享，我再把这层补上。

## 参考链接

- [Spring AI Reference - MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)：starter 坐标、spring.ai.mcp.server 属性与传输说明
- [Spring AI Reference - MCP 注解](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations.html)：@McpTool 与 @McpToolParam 注解用法
- [MCP 规范 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18)：initialize 握手、tools/list 与 tools/call 的协议定义
- [MCP 官方文档 - 入门](https://modelcontextprotocol.io/docs/getting-started/intro)：server/client/tool 角色与宿主接入
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)：io.modelcontextprotocol.sdk 制品与 SyncToolSpecification 定义
