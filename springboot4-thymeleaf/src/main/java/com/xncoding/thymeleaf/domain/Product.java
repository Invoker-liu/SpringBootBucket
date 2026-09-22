package com.xncoding.thymeleaf.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体（演示用，内存存储）。
 */
public class Product {

    private Long id;
    private String name;
    /** 分类代码：DIGITAL / BOOKS / CLOTHING / FOOD */
    private String category;
    private BigDecimal price;
    private Integer stock;
    private String summary;
    private LocalDateTime createdAt;

    public Product() {
    }

    public Product(Long id, String name, String category, BigDecimal price,
                   Integer stock, String summary, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = price;
        this.stock = stock;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
