package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentInboxDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentTombstoneDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentCountEventMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentTombstoneMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.model.bo.CommentBO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.CommentCacheInvalidationMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.CountPublishCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.PublishCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.KeyValueRpcService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

class CommentInboxProcessorTest {
    private CommentDOMapper commentDOMapper;
    private CommentTombstoneMapper commentTombstoneMapper;
    private NoteCountDOMapper noteCountDOMapper;
    private CommentCountEventMapper commentCountEventMapper;
    private KeyValueRpcService keyValueRpcService;
    private CommentInboxService commentInboxService;
    private CommentOutboxService commentOutboxService;
    private TransactionTemplate transactionTemplate;
    private CommentInboxProcessor processor;

    @BeforeEach
    void setUp() {
        commentDOMapper = mock(CommentDOMapper.class);
        commentTombstoneMapper = mock(CommentTombstoneMapper.class);
        noteCountDOMapper = mock(NoteCountDOMapper.class);
        commentCountEventMapper = mock(CommentCountEventMapper.class);
        keyValueRpcService = mock(KeyValueRpcService.class);
        commentInboxService = mock(CommentInboxService.class);
        commentOutboxService = mock(CommentOutboxService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(commentDOMapper.insertIfAbsent(any(CommentDO.class))).thenReturn(1);
        when(keyValueRpcService.batchAddCommentContents(anyList())).thenReturn(true);

        processor = new CommentInboxProcessor();
        ReflectionTestUtils.setField(processor, "commentDOMapper", commentDOMapper);
        ReflectionTestUtils.setField(processor, "commentTombstoneMapper", commentTombstoneMapper);
        ReflectionTestUtils.setField(processor, "noteCountDOMapper", noteCountDOMapper);
        ReflectionTestUtils.setField(processor, "commentCountEventMapper", commentCountEventMapper);
        ReflectionTestUtils.setField(processor, "keyValueRpcService", keyValueRpcService);
        ReflectionTestUtils.setField(processor, "commentInboxService", commentInboxService);
        ReflectionTestUtils.setField(processor, "commentOutboxService", commentOutboxService);
        ReflectionTestUtils.setField(processor, "transactionTemplate", transactionTemplate);
    }

    @Test
    void reusesStableContentUuidAndPersistsCountFollowupBeforeApplyingInbox() {
        String eventKey = "comment-publish:901";
        String contentUuid = "14500c68-01cb-4f44-95f7-b62c8297f931";
        PublishCommentMqDTO dto = PublishCommentMqDTO.builder()
                .commentId(901L)
                .noteId(902L)
                .creatorId(903L)
                .content("stable body")
                .contentUuid(contentUuid)
                .createTime(LocalDateTime.of(2026, 10, 18, 10, 0))
                .build();
        CommentInboxDO inbox = CommentInboxDO.builder()
                .id(1L).eventKey(eventKey).eventType("PUBLISH")
                .payload(JsonUtils.toJsonString(dto)).status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(1L).build();

        processor.processInboxRecord(inbox);

        org.mockito.ArgumentCaptor<List<CommentBO>> bodyCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(keyValueRpcService).batchAddCommentContents(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).hasSize(1);
        assertThat(bodyCaptor.getValue().get(0).getContentUuid()).isEqualTo(contentUuid);
        org.mockito.ArgumentCaptor<CommentDO> commentCaptor = org.mockito.ArgumentCaptor.forClass(CommentDO.class);
        verify(commentDOMapper).insertIfAbsent(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getContentUuid()).isEqualTo(contentUuid);
        verify(commentOutboxService).enqueue(eq("comment-count-publish:901"), any(), any());
        verify(commentInboxService).markApplied(inbox);
    }

    @Test
    void kvBodyWriteFailureLeavesCommentInboxRetryable() {
        PublishCommentMqDTO dto = PublishCommentMqDTO.builder()
                .commentId(971L)
                .noteId(972L)
                .creatorId(973L)
                .content("body must survive a KV outage")
                .contentUuid("7b9a218a-d4e6-42f2-89b1-580c03da673a")
                .createTime(LocalDateTime.of(2026, 10, 19, 12, 0))
                .build();
        CommentInboxDO inbox = CommentInboxDO.builder()
                .id(12L).eventKey("comment-publish:971").eventType("PUBLISH")
                .payload(JsonUtils.toJsonString(dto)).status("PROCESSING")
                .attemptCount(2).leaseOwner("kv-outage-worker").leaseVersion(1L).build();
        when(commentInboxService.claimBatch(anyString())).thenReturn(List.of(inbox));
        when(keyValueRpcService.batchAddCommentContents(anyList()))
                .thenThrow(new IllegalStateException("Cassandra unavailable"));

        processor.processDueInbox();

        org.mockito.ArgumentCaptor<Throwable> failureCaptor =
                org.mockito.ArgumentCaptor.forClass(Throwable.class);
        verify(commentInboxService).scheduleRetry(eq(inbox), failureCaptor.capture());
        assertThat(failureCaptor.getValue()).hasMessageContaining("Cassandra unavailable");
        verify(commentInboxService, never()).markApplied(inbox);
        verify(commentDOMapper, never()).insertIfAbsent(any(CommentDO.class));
        verify(commentOutboxService, never()).enqueue(anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cacheInvalidationUsesRawStringArgumentsAndLongResultsForLua() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ReflectionTestUtils.setField(processor, "redisTemplate", redisTemplate);
        CommentCacheInvalidationMqDTO dto = CommentCacheInvalidationMqDTO.builder()
                .eventId("test-cache-invalidation")
                .commentId(901L)
                .noteId(902L)
                .build();
        CommentInboxDO inbox = CommentInboxDO.builder()
                .id(9L).eventKey("comment-cache-publish:901").eventType("CACHE_INVALIDATE")
                .payload(JsonUtils.toJsonString(dto)).status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(1L).build();

        processor.processInboxRecord(inbox);

        org.mockito.ArgumentCaptor<RedisSerializer> serializerCaptor =
                org.mockito.ArgumentCaptor.forClass(RedisSerializer.class);
        verify(redisTemplate).execute(org.mockito.ArgumentMatchers.any(RedisScript.class),
                serializerCaptor.capture(), serializerCaptor.capture(),
                eq(List.of(RedisKeyConstants.buildCommentListKey(902L),
                        RedisKeyConstants.buildCommentListGenerationKey(902L),
                        RedisKeyConstants.buildCommentListReadyKey(902L))), eq("INVALIDATE"));
        assertThat(serializerCaptor.getAllValues().get(0).serialize("INVALIDATE"))
                .isEqualTo("INVALIDATE".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(serializerCaptor.getAllValues().get(1).deserialize("1".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(1L);
        verify(commentInboxService).markApplied(inbox);
    }

    @Test
    void replyPublicationLocksTheLevelOneAncestorBeforeTheReplyTarget() {
        CommentDO root = CommentDO.builder().id(900L).noteId(902L).userId(901L).level(1).build();
        CommentDO reply = CommentDO.builder().id(905L).noteId(902L).userId(904L).level(2).parentId(900L).build();
        PublishCommentMqDTO dto = PublishCommentMqDTO.builder()
                .commentId(910L).noteId(902L).creatorId(911L).replyCommentId(905L)
                .content("reply body").contentUuid("c844e9c2-5a23-45d2-8b6c-19a6a66eef13")
                .createTime(LocalDateTime.of(2026, 10, 18, 10, 0)).build();
        CommentInboxDO inbox = CommentInboxDO.builder().id(4L).eventKey("comment-publish:910")
                .eventType("PUBLISH").payload(JsonUtils.toJsonString(dto)).status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(1L).build();
        when(commentDOMapper.selectByPrimaryKey(905L)).thenReturn(reply);
        when(commentDOMapper.selectByPrimaryKeyForUpdate(900L)).thenReturn(root);
        when(commentDOMapper.selectByPrimaryKeyForUpdate(905L)).thenReturn(reply);

        processor.processInboxRecord(inbox);

        org.mockito.InOrder order = inOrder(commentDOMapper);
        order.verify(commentDOMapper).selectByPrimaryKey(905L);
        order.verify(commentDOMapper).selectByPrimaryKeyForUpdate(900L);
        order.verify(commentDOMapper).selectByPrimaryKeyForUpdate(905L);
        org.mockito.ArgumentCaptor<CommentDO> inserted = org.mockito.ArgumentCaptor.forClass(CommentDO.class);
        verify(commentDOMapper).insertIfAbsent(inserted.capture());
        assertThat(inserted.getValue().getParentId()).isEqualTo(900L);
        assertThat(inserted.getValue().getReplyCommentId()).isEqualTo(905L);
    }

    @Test
    void replyPublicationIsDiscardedWhenItsAncestorWasTombstoned() {
        CommentDO reply = CommentDO.builder().id(905L).noteId(902L).userId(904L).level(2).parentId(900L).build();
        PublishCommentMqDTO dto = PublishCommentMqDTO.builder()
                .commentId(910L).noteId(902L).creatorId(911L).replyCommentId(905L)
                .content("reply body").contentUuid("c844e9c2-5a23-45d2-8b6c-19a6a66eef13")
                .createTime(LocalDateTime.of(2026, 10, 18, 10, 0)).build();
        CommentInboxDO inbox = CommentInboxDO.builder().id(5L).eventKey("comment-publish:910")
                .eventType("PUBLISH").payload(JsonUtils.toJsonString(dto)).status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(1L).build();
        when(commentDOMapper.selectByPrimaryKey(905L)).thenReturn(reply);
        when(commentDOMapper.selectByPrimaryKeyForUpdate(900L))
                .thenReturn(CommentDO.builder().id(900L).level(1).build());
        when(commentTombstoneMapper.selectByCommentIdForUpdate(900L))
                .thenReturn(CommentTombstoneDO.builder().commentId(900L).deleteRootId(900L).build());

        processor.processInboxRecord(inbox);

        verify(commentDOMapper, never()).insertIfAbsent(any(CommentDO.class));
        verify(keyValueRpcService, never()).batchAddCommentContents(anyList());
        verify(commentInboxService).markApplied(inbox);
    }

    @Test
    void deleteEventSubtractsTheTombstonedTreeAndAppliesInboxInTheSameTransaction() {
        CommentDO root = CommentDO.builder().id(901L).noteId(902L).level(1).parentId(902L).build();
        CommentDO child = CommentDO.builder().id(903L).noteId(902L).level(2).parentId(901L).build();
        when(commentTombstoneMapper.selectByDeleteRootId(901L)).thenReturn(List.of(
                com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentTombstoneDO.builder()
                        .commentId(901L).deleteRootId(901L).noteId(902L).level(1).build(),
                com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentTombstoneDO.builder()
                        .commentId(903L).deleteRootId(901L).noteId(902L).level(2).parentId(901L).build()));
        when(commentCountEventMapper.selectAppliedDelta(anyString(), anyString())).thenReturn(null);
        CommentInboxDO inbox = CommentInboxDO.builder()
                .id(2L).eventKey("comment-delete:901").eventType("DELETE")
                .payload(JsonUtils.toJsonString(root)).status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(2L).build();

        processor.processInboxRecord(inbox);

        verify(commentDOMapper).deleteByIds(List.of(901L, 903L));
        verify(noteCountDOMapper).updateCommentTotalByNoteId(902L, -2);
        verify(commentDOMapper).updateChildCommentTotal(901L, -1);
        verify(commentInboxService).markApplied(inbox);
    }

    @Test
    void legacyDeletePersistsTombstoneBeforeRemovingRowAndApplyingInbox() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 18, 10, 0);
        CommentDO legacyEvent = CommentDO.builder()
                .id(921L).noteId(922L).level(1).parentId(922L)
                .contentUuid("legacy-comment-content").isContentEmpty(false).createTime(createdAt)
                .build();
        CommentInboxDO inbox = CommentInboxDO.builder().id(8L).eventKey("comment-delete:921")
                .eventType("DELETE").payload(JsonUtils.toJsonString(legacyEvent)).status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(1L).build();
        when(commentTombstoneMapper.selectByDeleteRootId(921L)).thenReturn(List.of());
        when(commentDOMapper.selectByPrimaryKey(921L)).thenReturn(null);
        when(commentTombstoneMapper.insertIfAbsent(any(CommentTombstoneDO.class))).thenReturn(1);
        when(commentCountEventMapper.selectAppliedDelta(anyString(), anyString())).thenReturn(null);

        processor.processInboxRecord(inbox);

        org.mockito.ArgumentCaptor<CommentTombstoneDO> tombstoneCaptor =
                org.mockito.ArgumentCaptor.forClass(CommentTombstoneDO.class);
        verify(commentTombstoneMapper).insertIfAbsent(tombstoneCaptor.capture());
        CommentTombstoneDO tombstone = tombstoneCaptor.getValue();
        assertThat(tombstone.getCommentId()).isEqualTo(921L);
        assertThat(tombstone.getDeleteRootId()).isEqualTo(921L);
        assertThat(tombstone.getNoteId()).isEqualTo(922L);
        assertThat(tombstone.getParentId()).isEqualTo(922L);
        assertThat(tombstone.getLevel()).isEqualTo(1);
        assertThat(tombstone.getContentUuid()).isEqualTo("legacy-comment-content");
        assertThat(tombstone.getContentEmpty()).isFalse();
        assertThat(tombstone.getCreateTime()).isEqualTo(createdAt);

        org.mockito.InOrder order = inOrder(commentTombstoneMapper, commentDOMapper, noteCountDOMapper,
                commentInboxService);
        order.verify(commentTombstoneMapper).insertIfAbsent(any(CommentTombstoneDO.class));
        order.verify(commentDOMapper).deleteByIds(List.of(921L));
        order.verify(noteCountDOMapper).updateCommentTotalByNoteId(922L, -1);
        order.verify(commentInboxService).markApplied(inbox);
    }

    @Test
    void legacyDeleteIncludesOrphanRepliesWhenRootRowWasAlreadyRemoved() {
        LocalDateTime rootCreatedAt = LocalDateTime.of(2026, 10, 18, 10, 0);
        LocalDateTime replyCreatedAt = rootCreatedAt.plusMinutes(1);
        CommentDO deletedRootEvent = CommentDO.builder()
                .id(961L).noteId(962L).level(1).parentId(962L)
                .contentUuid("legacy-root-content").isContentEmpty(false).createTime(rootCreatedAt)
                .build();
        CommentDO orphanReply = CommentDO.builder()
                .id(963L).noteId(962L).level(2).parentId(961L).replyCommentId(961L)
                .contentUuid("orphan-reply-content").isContentEmpty(false).createTime(replyCreatedAt)
                .build();
        CommentInboxDO inbox = CommentInboxDO.builder().id(12L).eventKey("comment-delete:961")
                .eventType("DELETE").payload(JsonUtils.toJsonString(deletedRootEvent)).status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(1L).build();
        when(commentTombstoneMapper.selectByDeleteRootId(961L)).thenReturn(List.of());
        when(commentDOMapper.selectByPrimaryKeyForUpdate(961L)).thenReturn(null);
        // Once the recursive query is extended to seed from orphan children, it returns this reply
        // even though the level-one row is already gone.
        when(commentDOMapper.selectDeleteTreeByRootCommentId(961L)).thenReturn(List.of(orphanReply));
        when(commentTombstoneMapper.insertIfAbsent(any(CommentTombstoneDO.class))).thenReturn(1);
        when(commentCountEventMapper.selectAppliedDelta(anyString(), anyString())).thenReturn(null);

        processor.processInboxRecord(inbox);

        org.mockito.ArgumentCaptor<CommentTombstoneDO> tombstoneCaptor =
                org.mockito.ArgumentCaptor.forClass(CommentTombstoneDO.class);
        verify(commentTombstoneMapper, org.mockito.Mockito.times(2)).insertIfAbsent(tombstoneCaptor.capture());
        assertThat(tombstoneCaptor.getAllValues())
                .extracting(CommentTombstoneDO::getCommentId)
                .containsExactlyInAnyOrder(961L, 963L);
        assertThat(tombstoneCaptor.getAllValues()).anySatisfy(tombstone -> {
            assertThat(tombstone.getCommentId()).isEqualTo(963L);
            assertThat(tombstone.getContentUuid()).isEqualTo("orphan-reply-content");
        });
        verify(commentDOMapper).deleteByIds(List.of(961L, 963L));
        verify(noteCountDOMapper).updateCommentTotalByNoteId(962L, -2);
        verify(commentDOMapper).updateChildCommentTotal(961L, -1);
        verify(commentInboxService).markApplied(inbox);
    }

    @Test
    void delayedPublishIsRejectedWhenItsCommentHasADurableTombstone() {
        PublishCommentMqDTO dto = PublishCommentMqDTO.builder()
                .commentId(931L).noteId(932L).creatorId(933L).content("must not return")
                .contentUuid("legacy-comment-content")
                .createTime(LocalDateTime.of(2026, 10, 18, 10, 0)).build();
        CommentInboxDO inbox = CommentInboxDO.builder().id(9L).eventKey("comment-publish:931")
                .eventType("PUBLISH").payload(JsonUtils.toJsonString(dto)).status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(1L).build();
        when(commentTombstoneMapper.selectByCommentIdForUpdate(931L))
                .thenReturn(CommentTombstoneDO.builder().commentId(931L).deleteRootId(931L).build());

        processor.processInboxRecord(inbox);

        verify(commentDOMapper, never()).insertIfAbsent(any(CommentDO.class));
        verify(keyValueRpcService, never()).batchAddCommentContents(anyList());
        verify(commentInboxService).markApplied(inbox);
    }

    @Test
    void legacyTreeTombstonesKeepEachRepliesOwnContentMetadata() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 18, 10, 0);
        CommentDO root = CommentDO.builder().id(941L).noteId(942L).level(1).parentId(942L)
                .contentUuid("root-uuid").isContentEmpty(true).createTime(createdAt).build();
        CommentDO reply = CommentDO.builder().id(943L).noteId(942L).level(2).parentId(941L)
                .contentUuid("reply-uuid").isContentEmpty(false).createTime(createdAt).build();
        when(commentTombstoneMapper.selectByDeleteRootId(941L)).thenReturn(List.of());
        when(commentDOMapper.selectByPrimaryKeyForUpdate(941L)).thenReturn(root);
        when(commentDOMapper.selectDeleteTreeByRootCommentId(941L)).thenReturn(List.of(root, reply));
        when(commentTombstoneMapper.insertIfAbsent(any(CommentTombstoneDO.class))).thenReturn(1);
        when(commentCountEventMapper.selectAppliedDelta(anyString(), anyString())).thenReturn(null);
        CommentInboxDO inbox = CommentInboxDO.builder().id(10L).eventKey("comment-delete:941")
                .eventType("DELETE").payload(JsonUtils.toJsonString(root)).status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(1L).build();

        processor.processInboxRecord(inbox);

        org.mockito.ArgumentCaptor<CommentTombstoneDO> tombstoneCaptor =
                org.mockito.ArgumentCaptor.forClass(CommentTombstoneDO.class);
        verify(commentTombstoneMapper, org.mockito.Mockito.times(2)).insertIfAbsent(tombstoneCaptor.capture());
        assertThat(tombstoneCaptor.getAllValues()).anySatisfy(tombstone -> {
            assertThat(tombstone.getCommentId()).isEqualTo(941L);
            assertThat(tombstone.getContentUuid()).isEqualTo("root-uuid");
            assertThat(tombstone.getContentEmpty()).isTrue();
        }).anySatisfy(tombstone -> {
            assertThat(tombstone.getCommentId()).isEqualTo(943L);
            assertThat(tombstone.getParentId()).isEqualTo(941L);
            assertThat(tombstone.getContentUuid()).isEqualTo("reply-uuid");
            assertThat(tombstone.getContentEmpty()).isFalse();
        });
    }

    @Test
    void legacyDeleteWithInsufficientMetadataStaysRetryable() {
        CommentDO incompleteEvent = CommentDO.builder().id(951L).build();
        CommentInboxDO inbox = CommentInboxDO.builder().id(11L).eventKey("comment-delete:951")
                .eventType("DELETE").payload(JsonUtils.toJsonString(incompleteEvent)).status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(1L).build();
        when(commentTombstoneMapper.selectByDeleteRootId(951L)).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.processInboxRecord(inbox))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lacks tombstone metadata");

        verify(commentInboxService, never()).markApplied(inbox);
    }

    @Test
    void deletingThePreviewReplySelectsTheNextSurvivingReply() {
        CommentInboxDO inbox = childDeletionInbox();
        when(commentDOMapper.selectEarliestByParentId(900L))
                .thenReturn(CommentDO.builder().id(904L).build());

        processor.processInboxRecord(inbox);

        verify(commentDOMapper).updateFirstReplyCommentIdByPrimaryKey(904L, 900L);
    }

    @Test
    void deletingTheLastReplyClearsThePreviewAndPersistsAHeatRecalculation() {
        CommentInboxDO inbox = childDeletionInbox();
        java.util.Map<String, String> durableEvents = captureFollowupEvents();

        processor.processInboxRecord(inbox);

        verify(commentDOMapper).updateFirstReplyCommentIdByPrimaryKey(0L, 900L);
        assertThat(durableEvents.get("comment-heat-delete:901:" + MQConstants.TOPIC_COMMENT_HEAT_UPDATE))
                .isEqualTo("[900]");
        assertThat(durableEvents.get("comment-clear-first-reply-marker:901:900:"
                + MQConstants.TOPIC_COMMENT_CACHE_INVALIDATE))
                .contains("\"clearFirstReplyMarker\":true");
    }

    @Test
    void cacheInvalidationClearsTheFirstReplyMarkerWhenRequested() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ReflectionTestUtils.setField(processor, "redisTemplate", redisTemplate);
        CommentInboxDO inbox = CommentInboxDO.builder()
                .id(9L).eventKey("clear-first-reply-marker").eventType("CACHE_INVALIDATE")
                .payload("{\"eventId\":\"clear-first-reply-marker\",\"commentId\":900,"
                        + "\"parentId\":900,\"clearFirstReplyMarker\":true}").status("PROCESSING")
                .attemptCount(1).leaseOwner("owner").leaseVersion(1L).build();

        processor.processInboxRecord(inbox);

        verify(redisTemplate).delete(RedisKeyConstants.buildHaveFirstReplyCommentKey(900L));
        verify(commentInboxService).markApplied(inbox);
    }

    @Test
    void finalDeleteInvalidationHasItsOwnKeyAndEvictsTheParentPreview() {
        CommentInboxDO inbox = childDeletionInbox();
        java.util.Map<String, String> durableEvents = captureFollowupEvents();

        processor.processInboxRecord(inbox);

        assertThat(durableEvents).containsKey("comment-cache-delete-applied:901:901:"
                + MQConstants.TOPIC_COMMENT_CACHE_INVALIDATE);
        assertThat(durableEvents.get("comment-local-cache-delete-parent:901:900:"
                + MQConstants.TOPIC_DELETE_COMMENT_LOCAL_CACHE)).isEqualTo("900");
    }

    private CommentInboxDO childDeletionInbox() {
        CommentDO child = CommentDO.builder().id(901L).noteId(902L).level(2).parentId(900L).build();
        when(commentTombstoneMapper.selectByDeleteRootId(901L)).thenReturn(List.of(
                CommentTombstoneDO.builder().commentId(901L).deleteRootId(901L)
                        .noteId(902L).level(2).parentId(900L).build()));
        when(commentCountEventMapper.selectAppliedDelta(anyString(), anyString())).thenReturn(1);
        return CommentInboxDO.builder().id(3L).eventKey("comment-delete:901").eventType("DELETE")
                .payload(JsonUtils.toJsonString(child)).status("PROCESSING").attemptCount(1)
                .leaseOwner("owner").leaseVersion(1L).build();
    }

    private java.util.Map<String, String> captureFollowupEvents() {
        java.util.Map<String, String> events = new java.util.HashMap<>();
        doAnswer(invocation -> {
            events.put(invocation.getArgument(0, String.class) + ":" + invocation.getArgument(1, String.class),
                    invocation.getArgument(2, String.class));
            return null;
        }).when(commentOutboxService).enqueue(anyString(), anyString(), anyString());
        return events;
    }
}
