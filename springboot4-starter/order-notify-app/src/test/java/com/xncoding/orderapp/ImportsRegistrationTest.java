package com.xncoding.orderapp;

import com.xncoding.starter.order.notify.OrderNotifyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * imports 注册文件的反例实证：把自家 jar 贡献的那份 imports 资源从 classloader
 * 里滤掉（等价于打包时路径或文件名写错），启动过程零报错，本 starter 自动配置静默缺失，
 * 而框架自身的自动配置不受影响。
 */
class ImportsRegistrationTest {

    private static final String IMPORTS_PATH =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /**
     * 只从资源枚举里滤掉 order-notify 自己那份 imports、其余全部放行的 classloader。
     * 注意不能按资源名整体隐藏：框架 jar 里的 imports 与它同名，全藏会把 Boot
     * 自身的自动配置一并干掉，与打包错位的真实故障不符。
     */
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
    void imports_file_registers_our_autoconfiguration() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class,
                Thread.currentThread().getContextClassLoader()).getCandidates();
        assertThat(candidates)
                .contains("com.xncoding.starter.order.notify.OrderNotifyAutoConfiguration");
    }

    @Test
    void hidden_imports_file_means_silent_missing_autoconfiguration() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class,
                new HidingClassLoader(Thread.currentThread().getContextClassLoader()))
                .getCandidates();
        // 自家条目消失，框架自身的自动配置条目不受影响
        assertThat(candidates)
                .doesNotContain("com.xncoding.starter.order.notify.OrderNotifyAutoConfiguration")
                .contains("org.springframework.boot.autoconfigure.aop.AopAutoConfiguration");

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(
                    new HidingClassLoader(original));
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(
                    OrderNotifyAppApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.main.banner-mode=off",
                            "--order.notify.enabled=true")) {
                // jar 在 classpath 上、属性全开，唯一差别是 imports 文件读不到：
                // 启动无任何告警，但 starter 装配的 bean 一个都没有；
                // 应用自己的 flakySmsClient 不受影响，照常存在
                assertThat(ctx.getBeansOfType(OrderNotifyService.class)).isEmpty();
                assertThat(ctx.containsBean("loggingSmsClient")).isFalse();
                assertThat(ctx.containsBean("noopSmsClient")).isFalse();
                assertThat(ctx.containsBean("flakySmsClient")).isTrue();
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
