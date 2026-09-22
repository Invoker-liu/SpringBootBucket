package com.xncoding.redis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * RedisTemplate 定制：key 用字符串、value 用 Jackson 3 JSON。
 * <p>
 * 自动配置给的 {@code RedisTemplate<Object, Object>} 是 JDK 序列化：
 * value 要实现 Serializable，写进 Redis 的是二进制，redis-cli 里看就是一串
 * \xac\xed 开头的乱码，跨语言没法读。所以教程工程一律自定义。
 * <p>
 * 这里有一个和 Jackson 2 时代的重要差异：老版
 * {@code GenericJackson2JsonRedisSerializer} 有无参构造，new 出来就带默认
 * 类型信息；新版 {@link GenericJacksonJsonRedisSerializer} 没有无参构造，
 * 必须通过 builder 显式决定要不要多态类型信息——安全开关自己拨，
 * 这是 Spring Data Redis 4.x 把"不安全默认值"改掉的一部分。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        // 类型校验器只放行我们自己的包，防止反序列化被塞进任意类型（反序列化 Gadgets 攻击面）
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.xncoding.")
                .build();

        GenericJacksonJsonRedisSerializer jsonSerializer = GenericJacksonJsonRedisSerializer.create(
                builder -> builder.enableDefaultTyping(typeValidator));

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        return template;
    }
}
