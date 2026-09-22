package com.xncoding.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码编码实测：BCrypt 注册与匹配、DelegatingPasswordEncoder 的前缀分发、
 * sec_users 表里的真实密文校验。
 */
@SpringBootTest
class PasswordEncodingTest {

    @Autowired
    private PasswordEncoder delegating;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bcrypt_encoder_roundtrip() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        String hash = bcrypt.encode("admin123");
        assertThat(hash).startsWith("$2a$10$");
        assertThat(bcrypt.matches("admin123", hash)).isTrue();
        assertThat(bcrypt.matches("wrong-pass", hash)).isFalse();
    }

    @Test
    void delegating_encoder_defaults_to_bcrypt_prefix() {
        String hash = delegating.encode("admin123");
        assertThat(hash).startsWith("{bcrypt}$2a$10$");
        assertThat(delegating.matches("admin123", hash)).isTrue();
        assertThat(delegating.matches("wrong-pass", hash)).isFalse();
    }

    @Test
    void delegating_encoder_dispatches_by_prefix() {
        // {noop} 明文前缀照原样校验，迁移期数据可用
        assertThat(delegating.matches("plain", "{noop}plain")).isTrue();
        assertThat(delegating.matches("plain", "{noop}other")).isFalse();
    }

    @Test
    void stored_user_hashes_in_mysql_match_real_passwords() {
        String adminHash = jdbc.queryForObject(
                "SELECT password FROM sec_users WHERE username = 'admin'", String.class);
        String operatorHash = jdbc.queryForObject(
                "SELECT password FROM sec_users WHERE username = 'operator'", String.class);

        assertThat(adminHash).startsWith("{bcrypt}$2a$10$");
        assertThat(delegating.matches("admin123", adminHash)).isTrue();
        assertThat(delegating.matches("admin124", adminHash)).isFalse();
        assertThat(delegating.matches("operator123", operatorHash)).isTrue();
        System.out.println("SEC_TAKEAWAY adminHash=" + adminHash
                + " matchesAdmin=" + delegating.matches("admin123", adminHash)
                + " matchesWrong=" + delegating.matches("admin124", adminHash));
    }
}
