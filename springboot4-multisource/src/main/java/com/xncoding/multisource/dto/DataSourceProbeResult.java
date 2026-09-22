package com.xncoding.multisource.dto;

import com.xncoding.multisource.context.DataSourceKey;

/**
 * 一次查询落到哪个库的事实快照。
 *
 * @param label        这一步在做什么（同一个场景里会有好几步，靠它区分）
 * @param expected     注解声明的库；为 {@code null} 表示当时线程上压根没有标识
 * @param actualDatabase 连接自己报出来的库名（MySQL 的 {@code DATABASE()}）
 * @param username     {@code t_user} 里 id=1 的用户名
 * @param realName     同上，姓名
 * @param connectionId 连接号，可以确认两次查询是不是同一条物理连接
 */
public record DataSourceProbeResult(
        String label,
        DataSourceKey expected,
        String actualDatabase,
        String username,
        String realName,
        Long connectionId) {

    /**
     * 路由结果和声明是否一致。
     *
     * <p>判定依据是 {@code DATABASE()} 而不是 {@code username} ——
     * 数据可以被改，连接所在的库名不能。这个方法是给测试和演示接口自己判断用的，
     * 免得「切没切对」要靠人肉比对两个字符串。
     */
    public boolean routed() {
        return expected != null && expected.matches(actualDatabase);
    }
}
