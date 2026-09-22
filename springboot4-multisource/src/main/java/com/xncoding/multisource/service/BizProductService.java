package com.xncoding.multisource.service;

import com.xncoding.multisource.domain.Product;
import com.xncoding.multisource.exception.ResourceNotFoundException;
import com.xncoding.multisource.mapper.biz.ProductMapper;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 方案 A 的演示服务：静态绑定运营库。
 *
 * <p>是 {@link PosOrderService} 的镜像，只是库和 mapper 不同。
 * 两个类各自注入自己的 mapper，谁也不知道对方连的是哪个库 ——
 * 这正是静态方案的形态：<b>数据源的选择被固化在了依赖注入关系里</b>。
 */
@Service
public class BizProductService {

    private final ProductMapper productMapper;

    public BizProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    public Product detail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new ResourceNotFoundException("商品", id);
        }
        return product;
    }

    public List<Product> lowStock(int threshold) {
        return productMapper.selectLowStock(threshold);
    }

    public int count() {
        return Math.toIntExact(productMapper.selectCount(null));
    }

    /** 运营库这个 mapper 实际连到的库名，用来核对静态绑定有没有配错。 */
    public String currentDatabase() {
        return productMapper.currentDatabase();
    }
}
