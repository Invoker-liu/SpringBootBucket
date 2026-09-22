package com.xncoding.echarts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 服务端图表数据接口与 PNG 导出示例工程。
 * <p>
 * 职责边界：统计接口只产纯数据（面向 ECharts option 的形状，但不绑死 ECharts 语义），
 * 渲染交给前端 chart.html，导出交给无头浏览器（venv python + playwright 子进程）。
 */
@SpringBootApplication
public class EchartsApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchartsApplication.class, args);
    }
}
