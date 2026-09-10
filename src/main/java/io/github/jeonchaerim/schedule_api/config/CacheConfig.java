package io.github.jeonchaerim.schedule_api.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.DefaultTyping;

import java.time.Duration;

@Configuration
@EnableCaching    // Redis — 메인 애플리케이션 클래스가 아닌 여기서 활성화 (슬라이스 테스트 격리)
public class CacheConfig {

    // 캐시매니저를 스프링 빈에 수동 등록
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        ObjectMapper objectMapper = JsonMapper.builder()
                // JSON에 클래스명을 함께 저장 → 역직렬화 시 원래 타입으로 복원 가능
                .activateDefaultTyping(
                        // 어떤 타입까지 복원을 허용할지 검증 (역직렬화 취약점 방어)
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType(Object.class) // ← 어떤 타입까지 복원을 허용할지
                         //     .allowIfSubType("io.github.jeonchaerim.schedule_api.dto")   // 내 DTO 패키지만
                                .build(),
                        DefaultTyping.NON_FINAL_AND_RECORDS)  // ← 어떤 타입에 클래스명을 붙일지
                .build();

        RedisSerializer<Object> valueSerializer =
                new GenericJacksonJsonRedisSerializer(objectMapper);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}