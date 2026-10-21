package com.quanxiaoha.xiaohashu.user.biz.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Arrays;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DelayDeleteUserRedisCacheConsumerTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @InjectMocks
    private DelayDeleteUserRedisCacheConsumer consumer;

    @Test
    void deletesBothUserCacheEntries() {
        consumer.onMessage("912345678901234");

        verify(redisTemplate).delete(Arrays.asList("user:info:912345678901234", "user:profile:912345678901234"));
    }
}
