package com.xncoding.security.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 审计查询：路径级规则直接限 ADMIN，OPERATOR 到不了控制器。 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> latest(@RequestParam(defaultValue = "20") int limit) {
        return audit.latest(limit);
    }
}
