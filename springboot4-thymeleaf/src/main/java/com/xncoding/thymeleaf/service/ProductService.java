package com.xncoding.thymeleaf.service;

import com.xncoding.thymeleaf.domain.Product;
import com.xncoding.thymeleaf.form.ProductForm;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 商品服务：内存仓储 + 分页查询 + 表单到实体的搬运。
 */
@Service
public class ProductService {

    public static final List<String> CATEGORIES = List.of("DIGITAL", "BOOKS", "CLOTHING", "FOOD");
    private static final int MAX_PAGE_SIZE = 50;

    private final Map<Long, Product> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);

    public ProductService() {
        // 预置 12 条数据，让分页至少有两页可翻
        Object[][] seed = {
                {"无线鼠标", "DIGITAL", new BigDecimal("129.00"), 320, "2.4G 双模静音办公鼠标"},
                {"机械键盘", "DIGITAL", new BigDecimal("399.00"), 150, "87 键红轴背光键盘"},
                {"4K 显示器", "DIGITAL", new BigDecimal("1899.00"), 60, "27 英寸 IPS 显示器"},
                {"USB-C 扩展坞", "DIGITAL", new BigDecimal("269.00"), 200, "八合一扩展坞，支持 100W 供电"},
                {"西游记（上下册）", "BOOKS", new BigDecimal("59.80"), 500, "人民文学出版社权威校注本"},
                {"深入理解计算机系统", "BOOKS", new BigDecimal("139.00"), 180, "CSAPP 第三版，程序员案头经典"},
                {"三体（全三册）", "BOOKS", new BigDecimal("93.00"), 400, "雨果奖获奖科幻小说"},
                {"春秋款连帽卫衣", "CLOTHING", new BigDecimal("159.00"), 260, "纯棉毛圈面料，男女同款"},
                {"轻薄羽绒服", "CLOTHING", new BigDecimal("299.00"), 120, "90% 白鸭绒填充"},
                {"牛仔裤", "CLOTHING", new BigDecimal("199.00"), 90, "直筒版型，经典水洗"},
                {"每日坚果 30 包", "FOOD", new BigDecimal("88.00"), 600, "六种坚果科学配比"},
                {"挂耳咖啡 40 片", "FOOD", new BigDecimal("69.00"), 350, "中度烘焙，冷萃锁香"}
        };
        for (Object[] row : seed) {
            insert((String) row[0], (String) row[1], (BigDecimal) row[2], (Integer) row[3], (String) row[4]);
        }
    }

    private Product insert(String name, String category, BigDecimal price, Integer stock, String summary) {
        Product product = new Product(seq.incrementAndGet(), name, category, price, stock, summary, LocalDateTime.now());
        store.put(product.getId(), product);
        return product;
    }

    public boolean existsByName(String name, Long excludeId) {
        return store.values().stream()
                .anyMatch(p -> p.getName().equals(name) && !p.getId().equals(excludeId));
    }

    public Product create(ProductForm form) {
        Product product = new Product(seq.incrementAndGet(), form.getName(), form.getCategory(),
                form.getPrice(), form.getStock(), form.getSummary(), LocalDateTime.now());
        store.put(product.getId(), product);
        return product;
    }

    public void update(Long id, ProductForm form) {
        Product product = store.get(id);
        product.setName(form.getName());
        product.setCategory(form.getCategory());
        product.setPrice(form.getPrice());
        product.setStock(form.getStock());
        product.setSummary(form.getSummary());
    }

    public Product getById(Long id) {
        return store.get(id);
    }

    public List<Product> search(String keyword) {
        List<Product> list = new java.util.ArrayList<>(store.values());
        if (keyword != null && !keyword.isBlank()) {
            String key = keyword.trim();
            list = new java.util.ArrayList<>(list.stream()
                    .filter(p -> p.getName().contains(key))
                    .toList());
        }
        list.sort(Comparator.comparing(Product::getId).reversed());
        return list;
    }

    /**
     * 分页切片。页码从 1 开始，越界自动收敛到最后一页，size 超过上限自动收敛。
     */
    public Page page(int page, int size, String keyword) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        List<Product> all = search(keyword);
        int totalPages = Math.max(1, (all.size() + safeSize - 1) / safeSize);
        int safePage = Math.max(1, Math.min(page, totalPages));
        int from = (safePage - 1) * safeSize;
        int to = Math.min(from + safeSize, all.size());
        return new Page(all.subList(from, to), safePage, safeSize, all.size(), totalPages);
    }

    /**
     * 分页切片视图对象，模板直接遍历 items 并用 page/totalPages 渲染翻页链接。
     */
    public record Page(List<Product> items, int page, int size, int total, int totalPages) {
    }
}
