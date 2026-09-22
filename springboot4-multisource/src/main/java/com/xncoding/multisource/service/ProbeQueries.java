package com.xncoding.multisource.service;

import com.xncoding.multisource.context.DataSourceKey;
import com.xncoding.multisource.domain.ProbeUser;
import com.xncoding.multisource.dto.DataSourceProbeResult;
import com.xncoding.multisource.mapper.probe.ProbeUserMapper;

/**
 * 探针查询的实际执行者。
 *
 * <p>故意做成不注册成 bean 的静态工具：它只是「查一行、包装一下」，
 * 没有任何状态，也不该被 AOP 代理。如果把它做成 {@code @Service} 再注进
 * {@code ProbeService}，那每次调用都多一层代理，排查切面顺序时反而多一个变量。
 */
final class ProbeQueries {

    private ProbeQueries() {
    }

    /**
     * @param expected 调用方<b>声明</b>的库。注意它是调用方传进来的，
     *                 不是从线程上下文读的 —— 两者不等的时候，
     *                 恰恰就是这个方法最有价值的时候
     */
    static DataSourceProbeResult query(ProbeUserMapper mapper, String label, DataSourceKey expected) {
        ProbeUser user = mapper.selectProbe();
        return new DataSourceProbeResult(
                label,
                expected,
                user.getDatabaseName(),
                user.getUsername(),
                user.getRealName(),
                user.getConnectionId());
    }
}
