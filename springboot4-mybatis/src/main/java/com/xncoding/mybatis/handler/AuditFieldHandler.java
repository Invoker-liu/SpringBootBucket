package com.xncoding.mybatis.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 审计字段自动填充。
 * <p>
 * 实体上标了 {@code @TableField(fill = ...)} 的字段，会在 insert / update 时被这里回写，
 * 业务代码一行都不用碰。用 {@code strictInsertFill} / {@code strictUpdateFill} 而不是
 * {@code setFieldValByName}：前者只在字段为 null 时填，调用方显式赋了值就不会被覆盖。
 * <p>
 * 但 {@code strict} 系列有个前提——字段必须标了 {@code @TableField(fill = ...)}，
 * 否则会被静默跳过，不报错也不提示。所以 {@code version} 那种为了插入时写 0 的填充，
 * 也得在实体上补一个 {@code FieldFill.INSERT}，不然这行代码等于没写。
 */
@Component
public class AuditFieldHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = now();
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        // 插入时把版本号置 0，与表结构的默认值保持一致
        this.strictInsertFill(metaObject, "version", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, now());
    }

    /**
     * 取当前时间并截断到毫秒。
     * <p>
     * {@code LocalDateTime.now()} 带纳秒，而建表用的是 {@code DATETIME(3)}，只存到毫秒。
     * 不截断的话，插入后回写到实体上的值比落库的值更精细：创建接口返回的
     * {@code createdAt} 是 {@code ...56.3591185}，紧接着 GET 回来却是 {@code ...56.359}，
     * 客户端拿这两个值一比就是对不上。在填充这一步对齐精度，比事后解释要省事。
     */
    private LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
