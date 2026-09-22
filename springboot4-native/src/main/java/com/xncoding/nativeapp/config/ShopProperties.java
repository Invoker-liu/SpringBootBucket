package com.xncoding.nativeapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 店铺订单配置。@ConfigurationProperties 是典型的反射点位：
 * 运行期按属性名绑定 yml 值到 setter，AOT 处理会为它生成 JavaBean 绑定代码与 reflect hints。
 */
@ConfigurationProperties(prefix = "shop")
public class ShopProperties {

    /** 店铺名称 */
    private String name = "default-shop";

    /** VIP 折扣率（0~1），演示数值绑定 */
    private double vipDiscount = 0.9;

    /** 大额订单阈值（元），演示整数绑定 */
    private int bulkThreshold = 10000;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getVipDiscount() {
        return vipDiscount;
    }

    public void setVipDiscount(double vipDiscount) {
        this.vipDiscount = vipDiscount;
    }

    public int getBulkThreshold() {
        return bulkThreshold;
    }

    public void setBulkThreshold(int bulkThreshold) {
        this.bulkThreshold = bulkThreshold;
    }
}
