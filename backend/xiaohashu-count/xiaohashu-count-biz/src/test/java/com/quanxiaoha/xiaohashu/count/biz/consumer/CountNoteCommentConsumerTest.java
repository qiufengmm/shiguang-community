package com.quanxiaoha.xiaohashu.count.biz.consumer;

import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentCountEventMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;

class CountNoteCommentConsumerTest {

    @Test
    void aggregatesCommentCountsByNoteId() {
        List<String> messageBodies = List.of(
                "[{\"noteId\":1001,\"commentId\":11},{\"noteId\":1001,\"commentId\":12}]",
                "[{\"noteId\":2002,\"commentId\":13}]"
        );

        Map<Long, Integer> counts = CountNoteCommentConsumer.aggregateCommentCounts(messageBodies);

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(1001L, 2, 2002L, 1));
    }

    @Test
    void returnsNoUpdatesWhenThereAreNoMessages() {
        assertThat(CountNoteCommentConsumer.aggregateCommentCounts(List.of())).isEmpty();
    }

    @Test
    void incrementsCachedNoteCommentTotalBeforeUpdatingDatabase() {
        NoteCountDOMapper mapper = mock(NoteCountDOMapper.class);
        CommentCountEventMapper eventMapper = mock(CommentCountEventMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        CountNoteCommentConsumer consumer = new CountNoteCommentConsumer();
        ReflectionTestUtils.setField(consumer, "noteCountDOMapper", mapper);
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "commentCountEventMapper", eventMapper);
        ReflectionTestUtils.setField(consumer, "transactionTemplate", transactionTemplate);
        when(eventMapper.selectTombstoneForUpdate(any())).thenReturn(null);
        when(eventMapper.insertIfAbsent(any(), any(), any(), any(), any(), any(Integer.class))).thenReturn(1);
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        consumer.onMessage("[{\"noteId\":1001,\"commentId\":11},{\"noteId\":1001,\"commentId\":12}]");

        verify(eventMapper).insertIfAbsent("comment-count-publish:11", "NOTE_COMMENT_TOTAL", 11L, 1001L, null, 1);
        verify(eventMapper).insertIfAbsent("comment-count-publish:12", "NOTE_COMMENT_TOTAL", 12L, 1001L, null, 1);
        verify(mapper).insertOrUpdateCommentTotalByNoteId(2, 1001L);
        verify(redisTemplate).delete("count:note:1001");
    }

    @Test
    void redeliveryDoesNotApplySqlCountTwiceButRetriesCacheInvalidation() {
        NoteCountDOMapper mapper = mock(NoteCountDOMapper.class);
        CommentCountEventMapper eventMapper = mock(CommentCountEventMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        CountNoteCommentConsumer consumer = new CountNoteCommentConsumer();
        ReflectionTestUtils.setField(consumer, "noteCountDOMapper", mapper);
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "commentCountEventMapper", eventMapper);
        ReflectionTestUtils.setField(consumer, "transactionTemplate", transactionTemplate);
        when(eventMapper.selectTombstoneForUpdate(any())).thenReturn(null);
        when(eventMapper.insertIfAbsent(any(), any(), any(), any(), any(), any(Integer.class))).thenReturn(0);
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        consumer.onMessage("[{\"noteId\":1001,\"commentId\":11}]");

        verify(mapper, never()).insertOrUpdateCommentTotalByNoteId(any(Integer.class), any(Long.class));
        verify(redisTemplate).delete("count:note:1001");
    }
}
