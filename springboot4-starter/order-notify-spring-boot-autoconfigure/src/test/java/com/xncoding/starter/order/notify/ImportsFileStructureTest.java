package com.xncoding.starter.order.notify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册文件结构检查：自动配置靠 META-INF/spring/ 下的 imports 文件被发现，
 * 路径或文件名错一个字符，启动不报错但自动配置静默缺失。
 */
class ImportsFileStructureTest {

    private static final String IMPORTS_PATH =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void imports_file_exists_with_exact_path_and_name() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(IMPORTS_PATH)) {
            assertThat(in).as("imports 文件必须挂在 META-INF/spring/ 的确切路径下").isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains(OrderNotifyAutoConfiguration.class.getName());
        }
    }

    @Test
    void autoconfiguration_annotation_is_meta_annotated() {
        // @AutoConfiguration 元注解含 @Configuration(proxyBeanMethods=false)，
        // 自动配置类不必再标 @Configuration；顺序属性 before/after 按需声明
        AutoConfiguration ann = OrderNotifyAutoConfiguration.class
                .getAnnotation(AutoConfiguration.class);
        assertThat(ann).isNotNull();
        assertThat(ann.before()).isEmpty();
        assertThat(ann.after()).isEmpty();
    }

    @Test
    void import_candidates_load_reads_exactly_the_imports_file() {
        List<String> candidates = org.springframework.boot.context.annotation.ImportCandidates
                .load(org.springframework.boot.autoconfigure.AutoConfiguration.class,
                        Thread.currentThread().getContextClassLoader())
                .getCandidates();
        assertThat(candidates).contains(OrderNotifyAutoConfiguration.class.getName());
    }
}
