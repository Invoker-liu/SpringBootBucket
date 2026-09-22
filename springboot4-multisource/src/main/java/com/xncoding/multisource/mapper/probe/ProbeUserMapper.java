package com.xncoding.multisource.mapper.probe;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xncoding.multisource.domain.ProbeUser;

/**
 * 探针 mapper。
 *
 * <p>和另外两个不同，它<b>不绑定固定的库</b> —— 它绑的是路由数据源，
 * 每次执行时落到哪个库由 {@code DataSourceContextHolder} 里当前的标识决定。
 *
 * <p>正因为如此，它的 mapper 包必须单独隔离
 * （{@code @MapperScan(…, sqlSessionFactoryRef = "dynamicSqlSessionFactory")}），
 * 不能和静态的 pos / biz 混在一起扫。
 */
public interface ProbeUserMapper extends BaseMapper<ProbeUser> {

    /**
     * 查 id=1 的那行，并把连接自己报出来的库名和连接号一起带回来。
     *
     * <p>SQL 里用了 MySQL 的 {@code DATABASE()} 和 {@code CONNECTION_ID()}：
     * 这两个是<b>连接</b>的属性而不是数据的属性，所以拿它们当证据最硬，
     * 哪怕两个库的数据被人改得一模一样也骗不过去。
     */
    ProbeUser selectProbe();
}
