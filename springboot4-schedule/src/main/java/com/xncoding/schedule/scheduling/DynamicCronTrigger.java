package com.xncoding.schedule.scheduling;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 动态 cron 触发器：cron 表达式存在内存里，HTTP 接口改它，下一次调度生效。
 *
 * @Scheduled(cron=...) 的表达式在启动时定死；要运行期改节奏，
 * 就不用注解上的 cron，改用 SchedulingConfigurer 注册 TriggerTask，
 * Trigger 的实现每次都按「当前值」算下一次执行时间。
 */
public class DynamicCronTrigger implements Trigger {

    private final AtomicReference<String> cron;

    public DynamicCronTrigger(String initialCron) {
        this.cron = new AtomicReference<>(validate(initialCron));
    }

    /** 换表达式：非法表达式当场抛 IllegalArgumentException，上层翻成 400。 */
    public String update(String newCron) {
        String validated = validate(newCron);
        cron.set(validated);
        return validated;
    }

    public String current() {
        return cron.get();
    }

    @Override
    public Instant nextExecution(TriggerContext triggerContext) {
        return new CronTrigger(cron.get()).nextExecution(triggerContext);
    }

    private String validate(String value) {
        CronExpression.parse(value);
        return value;
    }
}
