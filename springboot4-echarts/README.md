# springboot4-echarts

Spring Boot 4 教程第 29 篇配套工程：服务端图表数据接口 + 前端 ECharts 渲染 + 服务端导出 PNG。

> 系列教程，作者 Xiong Neng。基线：Spring Boot 4.1.1 / Java 21 / Maven 3.9，端口 **18290**。

## 功能组成

| 模块 | 位置 | 说明 |
|---|---|---|
| 按天统计接口 | `GET /api/stats/daily?days=7` | 纯数据 JSON，LocalDate 输出 ISO 字符串 |
| 类目统计接口 | `GET /api/stats/category` | 5 个固定类目的单量与金额 |
| 图表页 | `GET /chart.html` | ECharts 5.6.0 CDN 渲染，URL 参数即渲染参数 |
| PNG 导出 | `POST /api/export/png` | 无头浏览器渲染后返回 image/png 字节流 |

导出接口参数体（全部可省略，括号内为默认值）：

```json
{"type": "daily", "days": 7, "width": 900, "height": 480}
```

`type` 支持 `daily`（柱线组合图）与 `category`（环形饼图）；尺寸限制 200~4000。
参数越界返回 400，导出失败（超时/崩溃/魔数不符）返回 503，均为 `application/problem+json`。

## 运行前提

导出接口依赖 **venv Python + Playwright**（无头 Chromium）：

1. Python 3.10+，安装 playwright 并下载 chromium：
   ```bash
   pip install playwright
   playwright install chromium
   ```
2. 设置环境变量 `ECHARTS_PYTHON` 指向装好 playwright 的 python 可执行文件
   （Windows 下 `setx ECHARTS_PYTHON <路径>`，Linux 下 `export ECHARTS_PYTHON=<路径>`；
   `application.properties` 通过 `${ECHARTS_PYTHON}` 占位符引用它）。
3. 保持工作目录为工程根（`scripts/export_png.py` 以相对路径定位），或把
   `echarts.export.script-path` 改为绝对路径。

只跑统计接口与图表页不需要 Python；不调用 `/api/export/png` 时该依赖不生效。

## 运行

```bash
mvn spring-boot:run
# 或
mvn package && java -jar target/springboot4-echarts-1.0.0.jar
```

导出验证：

```bash
curl -X POST http://127.0.0.1:18290/api/export/png \
  -H "Content-Type: application/json" \
  -d '{"type":"daily","days":7,"width":900,"height":480}' \
  -o chart.png
```

## 测试

```bash
mvn test
```

含统计接口 JSON 断言（MockMvcTester）与导出接口端到端断言
（RANDOM_PORT 起真实服务，校验 PNG 魔数与 IHDR 尺寸；该测试需要 Python + Playwright 环境）。
