package com.xncoding.starter.order.notify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动配置条件分支的逐条验证，全部用 ApplicationContextRunner 起最小上下文。
 */
class OrderNotifyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OrderNotifyAutoConfiguration.class,
                    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class));

    @Test
    void default_enables_full_stack_with_jackson_renderer() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OrderNotifyProperties.class);
            assertThat(ctx).hasSingleBean(SmsClient.class);
            assertThat(ctx).hasSingleBean(OrderNotifyService.class);
            assertThat(ctx).hasSingleBean(JacksonMessageRenderer.class);
            assertThat(ctx.getBean(OrderNotifyProperties.class).isEnabled()).isTrue();
            assertThat(ctx.getBean(OrderNotifyProperties.class).getMaxRetries()).isEqualTo(2);
            assertThat(ctx.getBean(OrderNotifyProperties.class).getChannel()).isEqualTo("logging");
        });
    }

    @Test
    void enabled_off_removes_every_bean_of_this_starter() {
        runner.withPropertyValues("order.notify.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(OrderNotifyService.class);
            assertThat(ctx).doesNotHaveBean(SmsClient.class);
            assertThat(ctx).doesNotHaveBean(MessageRenderer.class);
            assertThat(ctx).doesNotHaveBean(OrderNotifyProperties.class);
        });
    }

    @Test
    void user_provided_service_backs_off_default() {
        OrderNotifyService mine = (event, orderNo, phone) -> {
        };
        runner.withBean("mine", OrderNotifyService.class, () -> mine).run(ctx -> {
            assertThat(ctx).hasSingleBean(OrderNotifyService.class);
            assertThat(ctx.getBean(OrderNotifyService.class)).isSameAs(mine);
            // 服务让位了，但短信网关与渲染器不受影响，仍然由 starter 提供
            assertThat(ctx).hasSingleBean(SmsClient.class);
            assertThat(ctx).hasSingleBean(JacksonMessageRenderer.class);
        });
    }

    @Test
    void user_provided_sms_client_backs_off_builtin_one() {
        runner.withBean("mine", SmsClient.class, () -> (phone, content) -> {
        }).run(ctx -> {
            assertThat(ctx).hasSingleBean(SmsClient.class);
            assertThat(ctx).doesNotHaveBean(LoggingSmsClient.class);
            assertThat(ctx).doesNotHaveBean(NoopSmsClient.class);
        });
    }

    @Test
    void channel_noop_switches_builtin_gateway() {
        runner.withPropertyValues("order.notify.channel=noop").run(ctx -> {
            assertThat(ctx).hasSingleBean(NoopSmsClient.class);
            assertThat(ctx).doesNotHaveBean(LoggingSmsClient.class);
        });
    }

    @Test
    void jackson_missing_falls_back_to_plain_renderer() {
        runner.withClassLoader(new FilteredClassLoader(ObjectMapper.class)).run(ctx -> {
            assertThat(ctx).hasSingleBean(OrderNotifyService.class);
            assertThat(ctx).hasSingleBean(PlainMessageRenderer.class);
            assertThat(ctx).doesNotHaveBean(JacksonMessageRenderer.class);
        });
    }

    @Test
    void properties_bind_to_relaxed_names() {
        runner.withPropertyValues("order.notify.max-retries=5", "order.notify.channel=noop")
                .run(ctx -> {
                    OrderNotifyProperties props = ctx.getBean(OrderNotifyProperties.class);
                    assertThat(props.getMaxRetries()).isEqualTo(5);
                    assertThat(props.getChannel()).isEqualTo("noop");
                });
    }
}
