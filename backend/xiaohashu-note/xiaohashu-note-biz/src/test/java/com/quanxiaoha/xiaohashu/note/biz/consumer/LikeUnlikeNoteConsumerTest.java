package com.quanxiaoha.xiaohashu.note.biz.consumer;

import com.quanxiaoha.xiaohashu.note.biz.model.dto.LikeUnlikeNoteMqDTO;
import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LikeUnlikeNoteConsumerTest {

    @Test
    void keepsTheLatestStateWhenBatchContainsAnEvenNumberOfOperations() {
        LikeUnlikeNoteMqDTO lastOperation = operation(11001L, 22001L, 0);
        List<LikeUnlikeNoteMqDTO> operations = List.of(
                operation(11001L, 22001L, 1),
                lastOperation);

        assertThat(LikeUnlikeNoteConsumer.mergeOperations(operations)).containsExactly(lastOperation);
    }

    @Test
    void keepsTheLastOperationWhenTheSameUserAndNoteHaveAnOddNumberOfOperations() {
        LikeUnlikeNoteMqDTO lastOperation = operation(11002L, 22002L, 1);
        List<LikeUnlikeNoteMqDTO> operations = List.of(
                operation(11002L, 22002L, 1),
                operation(11002L, 22002L, 0),
                lastOperation);

        assertThat(LikeUnlikeNoteConsumer.mergeOperations(operations))
                .containsExactly(lastOperation);
    }

    @Test
    void keepsOperationsForDifferentUsersAndNotesIndependent() {
        LikeUnlikeNoteMqDTO secondNoteLike = operation(11003L, 22004L, 1);
        LikeUnlikeNoteMqDTO otherUserUnlike = operation(11004L, 22003L, 0);
        List<LikeUnlikeNoteMqDTO> operations = List.of(
                operation(11003L, 22003L, 1),
                operation(11003L, 22003L, 0),
                secondNoteLike,
                otherUserUnlike);

        assertThat(LikeUnlikeNoteConsumer.mergeOperations(operations))
                .containsExactlyInAnyOrder(operations.get(1), secondNoteLike, otherUserUnlike);
    }

    @Test
    void persistsFinalLikeStateBeforeSynchronouslyPublishingCountRefresh() {
        NoteLikeDOMapper mapper = mock(NoteLikeDOMapper.class);
        RocketMQTemplate template = mock(RocketMQTemplate.class);

        LikeUnlikeNoteConsumer consumer = new LikeUnlikeNoteConsumer();
        ReflectionTestUtils.setField(consumer, "noteLikeDOMapper", mapper);
        ReflectionTestUtils.setField(consumer, "rocketMQTemplate", template);
        LikeUnlikeNoteMqDTO operation = operation(11005L, 22005L, 1);

        consumer.persistAndPublishCountEvents(List.of(operation));

        org.mockito.InOrder ordered = inOrder(mapper, template);
        ordered.verify(mapper).batchInsertOrUpdate(any());
        ordered.verify(template).syncSend(eq(MQConstants.TOPIC_COUNT_NOTE_LIKE), any(Message.class));
    }

    private static LikeUnlikeNoteMqDTO operation(long userId, long noteId, int type) {
        return LikeUnlikeNoteMqDTO.builder()
                .userId(userId)
                .noteId(noteId)
                .type(type)
                .createTime(LocalDateTime.now())
                .noteCreatorId(33001L)
                .build();
    }
}
