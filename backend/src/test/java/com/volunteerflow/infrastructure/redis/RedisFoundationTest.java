package com.volunteerflow.infrastructure.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "volunteerflow.redis.environment=test"
})
@ActiveProfiles("test")
class RedisFoundationTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisKeyFactory redisKeyFactory;

    @Test
    void contextStartsWithoutConnectingAndProvidesStringTemplate() {
        assertThat(stringRedisTemplate).isNotNull();
    }

    @Test
    void createsNamespacedKeysAndRejectsBlankSegments() {
        assertThat(redisKeyFactory.key("activity", "detail:123"))
                .isEqualTo("volunteerflow:test:activity:detail:123");
        assertThatThrownBy(() -> redisKeyFactory.key(" ", "123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("module");
        assertThatThrownBy(() -> redisKeyFactory.key("activity", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("businessKey");
    }
}
