package io.github.jeonchaerim.schedule_api.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class RedissonConfig {

    // 캐시(CacheConfig, Lettuce 기반)와 분산락(Redisson)은 서로 다른 클라이언트 라이브러리라
    // 별도로 연결을 맺어야 함. 같은 Redis 서버를 가리키도록 spring.data.redis.* 값을 그대로 재사용
    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    // Redisson은 Lettuce와 달리 빈 생성 시점에 즉시 연결을 시도하므로, 실제로
    // 락을 처음 사용하는 시점까지 연결을 미루도록 @Lazy로 등록 (Redis 없이도
    // 컨텍스트가 뜰 수 있게 하기 위함 — ScheduleService의 주입부도 함께 @Lazy)
    @Lazy
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort);
        return Redisson.create(config);
    }
}
