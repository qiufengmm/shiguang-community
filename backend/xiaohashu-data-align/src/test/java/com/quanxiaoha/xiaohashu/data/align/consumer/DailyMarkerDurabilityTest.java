package com.quanxiaoha.xiaohashu.data.align.consumer;

import com.quanxiaoha.xiaohashu.data.align.domain.mapper.InsertMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyMarkerDurabilityTest {

    private static final int TABLE_SHARDS = 3;

    @Test
    void followEventPersistsBothSourceAndTargetMarkersWithoutRedis() {
        InsertMapper mapper = mock(InsertMapper.class);
        TodayUserFollowIncrementData2DBConsumer consumer = followConsumer(mapper);

        consumer.onMessage("{\"userId\":1001,\"targetUserId\":2002,\"type\":1}");

        verify(mapper).insert2DataAlignUserFollowingCountTempTable(
                argThat(suffix -> suffix.matches("\\d{8}_2")), eq(1001L));
        verify(mapper).insert2DataAlignUserFansCountTempTable(
                argThat(suffix -> suffix.matches("\\d{8}_1")), eq(2002L));
    }

    @Test
    void likeEventPersistsNoteAndAuthorMarkers() {
        InsertMapper mapper = mock(InsertMapper.class);
        TodayNoteLikeIncrementData2DBConsumer consumer = new TodayNoteLikeIncrementData2DBConsumer();
        inject(mapper, consumer);

        consumer.onMessage("{\"userId\":3003,\"noteId\":4004,\"type\":1,\"noteCreatorId\":5005}");

        verify(mapper).insert2DataAlignNoteLikeCountTempTable(
                argThat(suffix -> suffix.matches("\\d{8}_2")), eq(4004L));
        verify(mapper).insert2DataAlignUserLikeCountTempTable(
                argThat(suffix -> suffix.matches("\\d{8}_1")), eq(5005L));
    }

    @Test
    void collectEventPersistsNoteAndAuthorMarkers() {
        InsertMapper mapper = mock(InsertMapper.class);
        TodayNoteCollectIncrementData2DBConsumer consumer = new TodayNoteCollectIncrementData2DBConsumer();
        inject(mapper, consumer);

        consumer.onMessage("{\"userId\":6006,\"noteId\":7007,\"type\":1,\"noteCreatorId\":8008}");

        verify(mapper).insert2DataAlignNoteCollectCountTempTable(
                argThat(suffix -> suffix.matches("\\d{8}_2")), eq(7007L));
        verify(mapper).insert2DataAlignUserCollectCountTempTable(
                argThat(suffix -> suffix.matches("\\d{8}_1")), eq(8008L));
    }

    @Test
    void publishEventPersistsAuthorMarker() {
        InsertMapper mapper = mock(InsertMapper.class);
        TodayNotePublishIncrementData2DBConsumer consumer = new TodayNotePublishIncrementData2DBConsumer();
        inject(mapper, consumer);

        consumer.onMessage("{\"creatorId\":9009,\"noteId\":10010,\"type\":1}");

        verify(mapper).insert2DataAlignUserNotePublishCountTempTable(
                argThat(suffix -> suffix.matches("\\d{8}_0")), eq(9009L));
    }

    @Test
    void sqlFailureEscapesSoRocketMqCanRetryTheMarker() {
        InsertMapper mapper = mock(InsertMapper.class);
        TodayNotePublishIncrementData2DBConsumer consumer = new TodayNotePublishIncrementData2DBConsumer();
        inject(mapper, consumer);
        whenInsertFails(mapper);

        assertThatThrownBy(() -> consumer.onMessage("{\"creatorId\":9009,\"noteId\":10010,\"type\":1}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("marker database unavailable");
    }

    private static TodayUserFollowIncrementData2DBConsumer followConsumer(InsertMapper mapper) {
        TodayUserFollowIncrementData2DBConsumer consumer = new TodayUserFollowIncrementData2DBConsumer();
        inject(mapper, consumer);
        return consumer;
    }

    private static void inject(InsertMapper mapper, Object consumer) {
        ReflectionTestUtils.setField(consumer, "insertRecordMapper", mapper);
        ReflectionTestUtils.setField(consumer, "tableShards", TABLE_SHARDS);
    }

    private static void whenInsertFails(InsertMapper mapper) {
        org.mockito.Mockito.doThrow(new IllegalStateException("marker database unavailable"))
                .when(mapper).insert2DataAlignUserNotePublishCountTempTable(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
