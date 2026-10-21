package com.quanxiaoha.xiaohashu.comment.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentLikeDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.LikeUnlikeCommentMqDTO;
import org.junit.jupiter.api.Test;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LikeUnlikeComment2DBConsumerTest {

    private static final String CONSUMER_CLASS_NAME =
            "com.quanxiaoha.xiaohashu.comment.biz.consumer.LikeUnlikeComment2DBConsumer";

    @Test
    void declaresConsumerWithDedicatedSpringBeanName() throws Exception {
        assertThat(ClassUtils.isPresent(CONSUMER_CLASS_NAME, getClass().getClassLoader())).isTrue();

        Class<?> consumerClass = ClassUtils.forName(CONSUMER_CLASS_NAME, getClass().getClassLoader());
        Method consumerFactory = consumerClass.getDeclaredMethod("mqPushConsumer");
        Bean bean = consumerFactory.getAnnotation(Bean.class);

        assertThat(bean).isNotNull();
        assertThat(bean.name()).containsExactly("LikeUnlikeComment2DBConsumer");
    }

    @Test
    void mergesOperationsByCommentAndUserThenPersistsOnlyLatestOperations() {
        CommentLikeDOMapper mapper = mock(CommentLikeDOMapper.class);
        LikeUnlikeComment2DBConsumer consumer = new LikeUnlikeComment2DBConsumer();
        ReflectionTestUtils.setField(consumer, "commentLikeDOMapper", mapper);

        LocalDateTime time = LocalDateTime.of(2026, 10, 17, 12, 0);
        LikeUnlikeCommentMqDTO user1Comment1Like = operation(11L, 21L, 1, time);
        LikeUnlikeCommentMqDTO user1Comment1Unlike = operation(11L, 21L, 0, time.plusSeconds(1));
        LikeUnlikeCommentMqDTO user1Comment1FinalLike = operation(11L, 21L, 1, time.plusSeconds(2));
        LikeUnlikeCommentMqDTO user2Comment1Unlike = operation(12L, 21L, 0, time.plusSeconds(3));
        LikeUnlikeCommentMqDTO user1Comment2Unlike = operation(11L, 22L, 0, time.plusSeconds(4));

        consumer.processMessages(List.of(
                toMessage(user1Comment1Like),
                toMessage(user1Comment1Unlike),
                toMessage(user1Comment1FinalLike),
                toMessage(user2Comment1Unlike),
                toMessage(user1Comment2Unlike)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LikeUnlikeCommentMqDTO>> likesCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LikeUnlikeCommentMqDTO>> unlikesCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(mapper).batchInsert(likesCaptor.capture());
        verify(mapper).batchDelete(unlikesCaptor.capture());

        assertThat(likesCaptor.getValue()).containsExactly(user1Comment1FinalLike);
        Map<String, Integer> finalUnlikes = unlikesCaptor.getValue().stream().collect(Collectors.toMap(
                dto -> dto.getUserId() + ":" + dto.getCommentId(), LikeUnlikeCommentMqDTO::getType,
                (first, ignored) -> first));
        assertThat(finalUnlikes).containsExactlyInAnyOrderEntriesOf(Map.of("12:21", 0, "11:22", 0));
    }

    private static LikeUnlikeCommentMqDTO operation(Long userId, Long commentId, Integer type,
                                                    LocalDateTime createTime) {
        return LikeUnlikeCommentMqDTO.builder()
                .userId(userId)
                .commentId(commentId)
                .type(type)
                .createTime(createTime)
                .build();
    }

    private static MessageExt toMessage(LikeUnlikeCommentMqDTO dto) {
        MessageExt message = new MessageExt();
        message.setTags(Objects.equals(dto.getType(), 1) ? "Like" : "Unlike");
        message.setBody(JsonUtils.toJsonString(dto).getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
