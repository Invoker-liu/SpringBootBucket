package com.xncoding.echarts.export;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 导出参数：width/height 决定无头浏览器视口与截图尺寸，type 决定渲染哪个统计图，
 * days 仅对折线图生效。取值范围在控制器入口做一次性校验，越界直接 400。
 * <p>
 * 组件用包装类型：Jackson 3 对 record 构造器缺失的 JSON 属性不再静默补 0/null，
 * 而是反序列化直接失败（异常发生在参数解析阶段，控制器方法体拿不到执行权）；
 * 包装类型允许缺省为 null，再在紧凑构造器里统一补业务默认值。
 */
public record ExportRequest(String type, Integer days, Integer width, Integer height) {

    public static final int MIN_SIZE = 200;
    public static final int MAX_SIZE = 4000;

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

    public void validate() {
        if (!"daily".equals(type) && !"category".equals(type)) {
            throw new IllegalArgumentException("type 只支持 daily 或 category，当前值：" + type);
        }
        if (width < MIN_SIZE || width > MAX_SIZE || height < MIN_SIZE || height > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "width/height 必须在 " + MIN_SIZE + "~" + MAX_SIZE + " 之间，当前：" + width + "x" + height);
        }
    }

    /** 无头浏览器要打开的图表页地址，带渲染参数，export=1 让页面进入纯图表模式 */
    public String chartUrl(String baseUrl) {
        return baseUrl + "/chart.html?type=" + type
                + "&days=" + days
                + "&width=" + width
                + "&height=" + height
                + "&export=1";
    }

    /** 从当前请求推导本服务对外地址，避免硬编码端口 */
    public static String currentBaseUrl() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs.getRequest();
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        return baseUrl + request.getContextPath();
    }
}
