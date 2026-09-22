package com.xncoding.schedule.service;

import com.xncoding.schedule.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 对账文件登记与扫描处理。
 */
@Service
public class ReconService {

    private final JdbcClient jdbc;

    public ReconService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> register(String fileName) {
        try {
            jdbc.sql("INSERT INTO recon_file (file_name) VALUES (?)")
                    .param(fileName).update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "对账文件已登记过，不能重复登记: " + fileName);
        }
        return jdbc.sql("SELECT id, file_name, status, received_at FROM recon_file WHERE file_name = ?")
                .param(fileName)
                .query()
                .singleRow();
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.sql("SELECT id, file_name, status, received_at, processed_at " +
                        "FROM recon_file ORDER BY id DESC LIMIT 50")
                .query()
                .listOfRows();
    }

    /** 把所有 PENDING 的对账文件处理完，返回处理条数。 */
    @Transactional
    public int processPending() {
        List<Long> pendingIds = jdbc.sql("SELECT id FROM recon_file WHERE status = 'PENDING' ORDER BY id")
                .query(Long.class)
                .list();
        for (Long id : pendingIds) {
            // 真实项目里这里是：读文件 → 逐行对账 → 差异落库，见第 10 篇 Spring Batch
            jdbc.sql("UPDATE recon_file SET status = 'DONE', processed_at = NOW(3) WHERE id = ?")
                    .param(id).update();
        }
        return pendingIds.size();
    }
}
