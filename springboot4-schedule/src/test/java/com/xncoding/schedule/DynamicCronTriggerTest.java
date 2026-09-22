package com.xncoding.schedule;

import com.xncoding.schedule.scheduling.DynamicCronTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动态 cron 触发器：改值立即生效、非法表达式当场拒绝。
 */
class DynamicCronTriggerTest {

    @Test
    void updateChangesNextExecution() {
        DynamicCronTrigger trigger = new DynamicCronTrigger("0/5 * * * * *");
        Instant before = trigger.nextExecution(new SimpleTriggerContext());

        trigger.update("0/30 * * * * *");
        Instant after = trigger.nextExecution(new SimpleTriggerContext());

        assertThat(before.getEpochSecond() % 60).isNotEqualTo(after.getEpochSecond() % 60);
        assertThat(after.getEpochSecond() % 60).isIn(0L, 30L);
        assertThat(trigger.current()).isEqualTo("0/30 * * * * *");
    }

    @Test
    void illegalCronRejectedOnUpdateAndInit() {
        DynamicCronTrigger trigger = new DynamicCronTrigger("0/5 * * * * *");
        assertThatThrownBy(() -> trigger.update("every minute"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamicCronTrigger("bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(trigger.current()).isEqualTo("0/5 * * * * *");
    }
}
