package com.xncoding.security.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 启动时把两个演示账号种进 sec_users（INSERT-OR-LOG 语义，已有账号不覆盖）。
 *
 * 「注册」在这里就是 BCrypt 编码后入库：明文只出现在这一处，库里永远是密文。
 */
@Component
public class UserSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeedRunner.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public UserSeedRunner(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("admin", "admin123", "ADMIN");
        seed("operator", "operator123", "OPERATOR");
    }

    private void seed(String username, String rawPassword, String role) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sec_users WHERE username = ?", Integer.class, username);
        if (exists != null && exists > 0) {
            String stored = jdbc.queryForObject(
                    "SELECT password FROM sec_users WHERE username = ?", String.class, username);
            log.info("SEED_USER exists username={} hash={} role={}", username, stored, role);
            return;
        }
        String hash = encoder.encode(rawPassword);
        jdbc.update("INSERT INTO sec_users(username, password, enabled, role) VALUES (?, ?, 1, ?)",
                username, hash, role);
        log.info("SEED_USER inserted username={} hash={} role={}", username, hash, role);
    }
}
