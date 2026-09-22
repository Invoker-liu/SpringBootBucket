package com.xncoding.schedule.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ErrorHandler;

/**
 * 调度异常处理器：@Scheduled 方法抛出的异常最终由它接住。
 *
 * 默认行为是框架自己记一条 ERROR 日志然后放过，异常对调度器透明、
 * 应用侧拿不到任何钩子。自定义 ThreadPoolTaskScheduler 后可以换成自己的
 * ErrorHandler：这里打 WARN 并带上任务上下文，交给监控体系处理。
 * 关键语义在 {@link ErrorHandler} 的契约里：处理完返回即可，
 * 下一次调度照常触发，异常不重试也不取消任务。
 */
public class SchedulerErrorHandler implements ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(SchedulerErrorHandler.class);

    @Override
    public void handleError(Throwable t) {
        log.warn("SCHED_TASK_ERROR 定时任务执行失败，等待下一次调度: {}", t.toString());
    }
}
