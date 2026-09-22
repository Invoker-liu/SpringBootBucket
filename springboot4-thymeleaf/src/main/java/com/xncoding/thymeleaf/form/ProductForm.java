package com.xncoding.thymeleaf.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 商品表单对象（表单与实体分离：模板只面对这个类型做绑定与回显）。
 * <p>
 * 校验注解覆盖六种常用形态：非空、长度、正则枚举、数值区间、小数下限、整数下限。
 * 提示文案直接写在注解里，校验失败后由 th:errors 逐字段回显。
 */
public class ProductForm {

    /** 编辑时携带，新增时为 null */
    private Long id;

    @NotBlank(message = "商品名称不能为空")
    @Size(min = 2, max = 20, message = "商品名称长度需在 {min} 到 {max} 个字符之间")
    private String name;

    @NotBlank(message = "商品分类不能为空")
    @Pattern(regexp = "DIGITAL|BOOKS|CLOTHING|FOOD", message = "商品分类必须是预置的四类之一")
    private String category;

    @NotNull(message = "售价不能为空")
    @DecimalMin(value = "0.01", message = "售价必须大于 0")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    @Size(max = 50, message = "简介不能超过 {max} 个字符")
    private String summary;

    public static ProductForm empty() {
        return new ProductForm();
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
}
