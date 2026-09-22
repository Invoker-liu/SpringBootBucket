package com.xncoding.apiversion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * v2 响应体：拆字段 + 金额明细。customer 聚成对象，金额拆成 items 与 amount 子结构。
 * 老客户端拿新字段不认识，这正是要拆版本的原因。
 */
public class OrderResponseV2 {

    private Long id;
    private String orderNo;
    private Customer customer;
    private List<AmountItem> items;
    private Amount amount;
    private String status;
    private LocalDateTime createdAt;

    public static class Customer {
        private String name;
        private String phone;

        public Customer(String name, String phone) {
            this.name = name;
            this.phone = phone;
        }

        public String getName() {
            return name;
        }

        public String getPhone() {
            return phone;
        }
    }

    public static class AmountItem {
        private String label;
        private BigDecimal value;

        public AmountItem(String label, BigDecimal value) {
            this.label = label;
            this.value = value;
        }

        public String getLabel() {
            return label;
        }

        public BigDecimal getValue() {
            return value;
        }
    }

    public static class Amount {
        private BigDecimal goodsAmount;
        private BigDecimal discountAmount;
        private BigDecimal shippingFee;
        private BigDecimal totalAmount;

        public Amount(BigDecimal goodsAmount, BigDecimal discountAmount,
                      BigDecimal shippingFee, BigDecimal totalAmount) {
            this.goodsAmount = goodsAmount;
            this.discountAmount = discountAmount;
            this.shippingFee = shippingFee;
            this.totalAmount = totalAmount;
        }

        public BigDecimal getGoodsAmount() {
            return goodsAmount;
        }

        public BigDecimal getDiscountAmount() {
            return discountAmount;
        }

        public BigDecimal getShippingFee() {
            return shippingFee;
        }

        public BigDecimal getTotalAmount() {
            return totalAmount;
        }
    }

    public static OrderResponseV2 of(Long id, String orderNo, String customerName, String customerPhone,
                                     BigDecimal unitPrice, Integer quantity, BigDecimal discount,
                                     BigDecimal shippingFee, BigDecimal totalAmount,
                                     String status, LocalDateTime createdAt) {
        OrderResponseV2 v = new OrderResponseV2();
        v.id = id;
        v.orderNo = orderNo;
        v.customer = new Customer(customerName, customerPhone);
        BigDecimal goodsAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal discountAmount = goodsAmount.subtract(goodsAmount.multiply(discount));
        v.items = List.of(
                new AmountItem("单价 x 数量", unitPrice.multiply(BigDecimal.valueOf(quantity))),
                new AmountItem("折扣优惠", discountAmount),
                new AmountItem("运费", shippingFee)
        );
        v.amount = new Amount(goodsAmount, discountAmount, shippingFee, totalAmount);
        v.status = status;
        v.createdAt = createdAt;
        return v;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Customer getCustomer() {
        return customer;
    }

    public List<AmountItem> getItems() {
        return items;
    }

    public Amount getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
