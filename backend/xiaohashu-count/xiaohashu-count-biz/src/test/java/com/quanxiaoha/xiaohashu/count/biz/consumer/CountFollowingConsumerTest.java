package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.xiaohashu.count.biz.service.CountSourceRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 关注数不参与 BufferTrigger 聚合：单个用户无法在短时间内关注大量用户，
 * 聚合没有收益，反而多一层延迟，因此仍按源关系表重算。
 */
class CountFollowingConsumerTest {

    @Test
    void followingEventRefreshesFromSourceAndInvalidatesUserCache() {
        CountSourceRefreshService refreshService = mock(CountSourceRefreshService.class);
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        CountFollowingConsumer consumer = new CountFollowingConsumer();
        ReflectionTestUtils.setField(consumer, "countSourceRefreshService", refreshService);
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);

        consumer.onMessage("{\"userId\":17,\"targetUserId\":29,\"type\":1}");

        verify(refreshService).refreshFollowing(17L);
        verify(redisTemplate).delete("count:user:17");
    }
}
