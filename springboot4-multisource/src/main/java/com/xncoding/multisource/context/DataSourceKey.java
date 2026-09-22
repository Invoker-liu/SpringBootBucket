package com.xncoding.multisource.context;

/**
 * 逻辑库标识。列出的是系统里<b>存在</b>的所有库，
 * 具体某次部署注册了哪几个由配置决定 —— 环境之间不一致是常态，
 * 比如测试环境没有报表库。
 *
 * <p>用枚举而不是字符串：路由数据源内部是拿这个值当 {@code Map} 的 key 的，
 * 字符串拼错了编译期查不出来，只会得到一个「当前没有对应数据源」的运行时异常。
 * 枚举至少让 IDE 能补全、编译器能拦住手滑。
 */
public enum DataSourceKey {

    /** 交易库 springboot4_pos：订单、订单明细。本环境已注册。 */
    POS("springboot4_pos"),

    /** 运营库 springboot4_biz：商品主数据。本环境已注册。 */
    BIZ("springboot4_biz"),

    /**
     * 报表库。本环境<b>故意没注册</b>。
     *
     * <p>留着它是为了让「标注了一个没配置的库会发生什么」这件事可以被真的跑一遍，
     * 而不是只在文章里描述。见 {@code DynamicDataSourceConfig} 的 targetDataSources。
     */
    REPORT(null);

    /**
     * 对应的物理库名，只用来核对路由结果，实际数据源地址仍在 application.yml 里。
     *
     * <p>真实项目里这张「逻辑库 → 物理库」的映射通常在配置中心，
     * 放在枚举里是为了让演示接口和测试能自己判断「切没切对」，
     * 不用把库名硬编在好几处。
     */
    private final String physicalDatabase;

    DataSourceKey(String physicalDatabase) {
        this.physicalDatabase = physicalDatabase;
    }

    /** 该逻辑库对应的物理库名，没配就是 {@code null}。 */
    public String physicalDatabase() {
        return physicalDatabase;
    }

    /** 某个物理库名是不是就是这个逻辑库。 */
    public boolean matches(String database) {
        return physicalDatabase != null && physicalDatabase.equals(database);
    }
}
