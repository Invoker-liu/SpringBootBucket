package com.xncoding.mybatis.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单实体，直接映射 {@code t_order} 表。
 * <p>
 * 这里刻意写成可变 POJO 而不是 record。原因很实在：自动填充（{@code MetaObjectHandler}）
 * 要往对象里回写字段、乐观锁要读并自增版本号，这些动作都依赖无参构造 + setter。
 * 用 record 就得写一堆额外配置去配合 MyBatis 的反射，得不偿失。
 * <p>
 * 对外暴露的仍然是不可变的 DTO（{@code OrderResponse}），实体不会直接出现在响应体里。
 */
@TableName(value = "t_order", autoResultMap = true)
public class Order {

    /**
     * {@code IdType.AUTO} 表示交给数据库自增。
     * <p>
     * 插入后主键会回填到实体的 {@code id} 上，所以不用再查一次拿 ID。
     * 默认策略是 {@code ASSIGN_ID}（雪花算法），和这里的自增列一起用会踩坑，所以必须显式声明。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private String customerName;

    private String customerPhone;

    private BigDecimal totalAmount;

    /** 枚举名入库，明细见 {@link OrderStatus} */
    private OrderStatus status;

    /**
     * 备注。
     * <p>
     * {@code NOT_NULL} 是字段的默认更新策略——值为 null 时该列不参与 UPDATE，
     * 于是"把备注清空"这个操作永远失败。改成 {@code ALWAYS} 才会把 null 真正写进去。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    /** 插入时由 {@code AuditFieldHandler} 填充，调用方不用管 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 插入和更新时都填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 订单明细，整份列表序列化成 JSON 存在一个列里。
     * <p>
     * 用 {@code Jackson3TypeHandler} 而不是 {@code JacksonTypeHandler}：后者是 Jackson 2 时代的写法，
     * 它引用的是 {@code com.fasterxml.jackson.databind.ObjectMapper}，而 Boot 4 只带了 Jackson 3
     * （{@code tools.jackson}），用它运行时会直接找不到类。这个类在 3.5.17 才加进来，
     * 3.5.13 的 {@code mybatis-plus-extension} 里还没有。
     * <p>
     * 光在字段上标 {@code typeHandler} 还不够。MyBatis 只有在用 resultMap 映射结果时才会应用
     * 自定义 typeHandler，而 MyBatis-Plus 默认走的是自动映射。必须同时把实体上的
     * {@code @TableName(autoResultMap = true)} 打开，框架才会为它生成一个带 typeHandler 的
     * resultMap。漏掉这个开关，写入正常、读取却是 null，而且不报任何错。
     */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<OrderItem> items;

    /**
     * 逻辑删除标记。
     * <p>
     * 加了 {@code @TableLogic} 之后：{@code deleteById} 会变成 {@code UPDATE ... SET deleted = 1}，
     * 而所有 MyBatis-Plus 生成的查询都会自动带上 {@code deleted = 0}。
     * 注意这个过滤只作用于框架生成的 SQL，自己写的 XML 不会被加上，回收站查询正是利用了这一点。
     */
    @TableLogic
    private Integer deleted;

    /**
     * 乐观锁版本号，配合 {@code OptimisticLockerInnerInterceptor} 使用。
     * <p>
     * 建表时这一列有 {@code DEFAULT 0}，只靠数据库默认值也能跑起来，但插入后实体上的
     * {@code version} 仍然是 null，创建接口的响应里就没有版本号，客户端想立刻做一次
     * 带乐观锁的更新还得先查一遍。这里加 {@code FieldFill.INSERT}，配合
     * {@code AuditFieldHandler} 把它填成 0。
     * <p>
     * {@code fill} 是 {@code strictInsertFill} 生效的前提：没标这个属性，填充逻辑会被
     * 静默跳过——不抛异常、不打日志，只是响应里少一个字段。
     */
    @Version
    @TableField(fill = FieldFill.INSERT)
    private Integer version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
