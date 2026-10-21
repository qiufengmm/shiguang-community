package com.quanxiaoha.xiaohashu.user.relation.biz.consumer;

import com.quanxiaoha.xiaohashu.user.relation.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.CountFollowUnfollowMqDTO;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class FollowUnfollowConsumerTest {

    @Test
    void publishesFollowingAndFansRefreshSynchronously() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        FollowUnfollowConsumer consumer = new FollowUnfollowConsumer();
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", template);
        CountFollowUnfollowMqDTO event = CountFollowUnfollowMqDTO.builder()
                .userId(17L).targetUserId(29L).type(1).build();

        ReflectionTestUtils.invokeMethod(consumer, "sendMQ", event);

        org.mockito.InOrder ordered = inOrder(template);
        ordered.verify(template).syncSend(eq(MQConstants.TOPIC_COUNT_FOLLOWING), any(Message.class));
        ordered.verify(template).syncSend(eq(MQConstants.TOPIC_COUNT_FANS), any(Message.class));
    }
}
