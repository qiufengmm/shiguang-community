package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMapper;
import com.quanxiaoha.xiaohashu.data.align.rpc.CountNotePublishReconciliationRpcService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CountReconciliationRepairServiceContextTest {

    @Test
    void injectsStringRedisTemplateWhenGenericRedisTemplateBeanAlsoExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
            RedisTemplate redisTemplate = new RedisTemplate();
            redisTemplate.setConnectionFactory(connectionFactory);
            StringRedisTemplate stringRedisTemplate = new StringRedisTemplate();
            stringRedisTemplate.setConnectionFactory(connectionFactory);
            context.getDefaultListableBeanFactory().registerSingleton("redisTemplate", redisTemplate);
            context.getDefaultListableBeanFactory().registerSingleton("stringRedisTemplate", stringRedisTemplate);
            context.getDefaultListableBeanFactory().registerSingleton("countAuditMapper", mock(CountAuditMapper.class));
            context.getDefaultListableBeanFactory().registerSingleton("countNotePublishReconciliationRpcService",
                    mock(CountNotePublishReconciliationRpcService.class));
            context.register(CountReconciliationRepairService.class);

            context.refresh();

            Object injectedTemplate = ReflectionTestUtils.getField(
                    context.getBean(CountReconciliationRepairService.class), "redisTemplate");
            assertThat(injectedTemplate).isSameAs(context.getBean("stringRedisTemplate"));
        }
    }
}
