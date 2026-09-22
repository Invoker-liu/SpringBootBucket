package com.xncoding.multisource.mapper.biz;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xncoding.multisource.domain.Product;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 运营库的商品 mapper。
 *
 * <p>与 {@code OrderMapper} 的关键区别：它的 {@code SqlSessionFactory} 指向
 * 完全不同的一个库（springboot4_biz），由 {@code BizDataSourceConfig} 绑定。
 */
public interface ProductMapper extends BaseMapper<Product> {

    /** 按库存阈值查商品，库存少的排前面。 */
    List<Product> selectLowStock(@Param("threshold") int threshold);

    /** 这个 mapper 连的到底是哪个库。理由见 {@code OrderMapper#currentDatabase()}。 */
    String currentDatabase();
}
