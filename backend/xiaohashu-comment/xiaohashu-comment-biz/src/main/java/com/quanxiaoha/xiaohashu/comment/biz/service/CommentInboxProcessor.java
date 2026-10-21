package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentInboxDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentTombstoneDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentCountEventMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentTombstoneMapper;
import com.quanxiaoha.xiaohashu.comment.biz.enums.CommentLevelEnum;
import com.quanxiaoha.xiaohashu.comment.biz.model.bo.CommentBO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.CommentCacheInvalidationMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.CountPublishCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.PublishCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scripting.support.ResourceScriptSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@Slf4j
public class CommentInboxProcessor {
    @Resource
    private CommentInboxService commentInboxService;

    @Resource
    private CommentOutboxService commentOutboxService;

    @Resource
    private CommentDOMapper commentDOMapper;

    @Resource
    private NoteCountDOMapper noteCountDOMapper;

    @Resource
    private CommentCountEventMapper commentCountEventMapper;

    @Resource
    private CommentTombstoneMapper commentTombstoneMapper;

    @Resource
    private KeyValueRpcService keyValueRpcService;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Scheduled(fixedDelayString = "${comment.delivery.inbox-processor-delay-ms:1000}")
    public void processDueInbox() {
        List<CommentInboxDO> batch = commentInboxService.claimBatch(UUID.randomUUID().toString());
        for (CommentInboxDO record : batch) {
            try {
                processInboxRecord(record);
            } catch (Exception e) {
                try {
                    commentInboxService.scheduleRetry(record, e);
                } catch (Exception retryError) {
                    log.error("Comment inbox retry metadata update failed, eventKey={}", record.getEventKey(), retryError);
                }
                log.error("Comment inbox processing failed, eventKey={}, type={}",
                        record.getEventKey(), record.getEventType(), e);
            }
        }
    }

    public void processInboxRecord(CommentInboxDO record) {
        if ("PUBLISH".equals(record.getEventType())) {
            processPublish(record);
            return;
        }
        if ("DELETE".equals(record.getEventType())) {
            processDelete(record);
            return;
        }
        if ("CACHE_INVALIDATE".equals(record.getEventType())) {
            processCacheInvalidation(record);
            return;
        }
        throw new IllegalArgumentException("unsupported comment inbox event type: " + record.getEventType());
    }

    private void processPublish(CommentInboxDO record) {
        PublishCommentMqDTO dto = JsonUtils.parseObject(record.getPayload(), PublishCommentMqDTO.class);
        if (dto == null || dto.getCommentId() == null || dto.getNoteId() == null || dto.getCreatorId() == null) {
            throw new IllegalArgumentException("invalid comment publish event metadata");
        }

        transactionTemplate.execute(status -> {
            CommentDO reply = null;
            if (dto.getReplyCommentId() != null) {
                CommentDO observedReply = commentDOMapper.selectByPrimaryKey(dto.getReplyCommentId());
                if (observedReply == null) {
                    commentInboxService.markApplied(record);
                    return null;
                }
                Long rootId = Objects.equals(observedReply.getLevel(), CommentLevelEnum.TWO.getCode())
                        ? observedReply.getParentId() : observedReply.getId();
                if (rootId == null) {
                    commentInboxService.markApplied(record);
                    return null;
                }

                // All replies and subtree deletes lock the level-one row first. A reply cannot be
                // appended after a delete has frozen its recursive tree, even when targeting a child.
                CommentDO root = commentDOMapper.selectByPrimaryKeyForUpdate(rootId);
                if (root == null || commentTombstoneMapper.selectByCommentIdForUpdate(rootId) != null) {
                    commentInboxService.markApplied(record);
                    return null;
                }
                reply = Objects.equals(rootId, dto.getReplyCommentId())
                        ? root : commentDOMapper.selectByPrimaryKeyForUpdate(dto.getReplyCommentId());
                if (reply == null
                        || commentTombstoneMapper.selectByCommentIdForUpdate(dto.getReplyCommentId()) != null) {
                    commentInboxService.markApplied(record);
                    return null;
                }
            }

            // A tombstone remains after physical comment cleanup, so a delayed duplicate event
            // cannot recreate a deleted comment or its body.
            if (commentTombstoneMapper.selectByCommentIdForUpdate(dto.getCommentId()) != null) {
                commentInboxService.markApplied(record);
                return null;
            }

            CommentDO existing = commentDOMapper.selectByPrimaryKeyForUpdate(dto.getCommentId());
            String contentUuid = resolveContentUuid(record.getEventKey(), dto, existing);
            CommentBO commentBO = toCommentBO(dto, contentUuid, reply);
            if (StringUtils.isNotBlank(dto.getContent())
                    && !keyValueRpcService.batchAddCommentContents(List.of(commentBO))) {
                throw new IllegalStateException("comment body write to KV service failed");
            }
            int inserted = commentDOMapper.insertIfAbsent(toCommentDO(commentBO));
            if (inserted > 0) {
                CountPublishCommentMqDTO countDto = CountPublishCommentMqDTO.builder()
                        .eventId("comment-count-publish:" + dto.getCommentId())
                        .noteId(commentBO.getNoteId())
                        .commentId(commentBO.getId())
                        .level(commentBO.getLevel())
                        .parentId(commentBO.getParentId())
                        .build();
                commentOutboxService.enqueue("comment-count-publish:" + dto.getCommentId(),
                        MQConstants.TOPIC_COUNT_NOTE_COMMENT, JsonUtils.toJsonString(List.of(countDto)));

                CommentCacheInvalidationMqDTO cacheDto = CommentCacheInvalidationMqDTO.builder()
                        .eventId("comment-cache-publish:" + dto.getCommentId())
                        .commentId(commentBO.getId())
                        .noteId(commentBO.getNoteId())
                        .parentId(commentBO.getParentId())
                        .level(commentBO.getLevel())
                        .build();
                commentOutboxService.enqueue("comment-cache-publish:" + dto.getCommentId(),
                        MQConstants.TOPIC_COMMENT_CACHE_INVALIDATE, JsonUtils.toJsonString(cacheDto));
            }
            commentInboxService.markApplied(record);
            return null;
        });
    }

    private void processDelete(CommentInboxDO record) {
        CommentDO event = JsonUtils.parseObject(record.getPayload(), CommentDO.class);
        if (event == null || event.getId() == null) {
            throw new IllegalArgumentException("invalid comment delete event");
        }
        transactionTemplate.execute(status -> {
            List<CommentTombstoneDO> tombstones = commentTombstoneMapper.selectByDeleteRootId(event.getId());
            if (tombstones == null || tombstones.isEmpty()) {
                CommentDO existingRoot = commentDOMapper.selectByPrimaryKeyForUpdate(event.getId());
                List<CommentDO> legacyTree = commentDOMapper.selectDeleteTreeByRootCommentId(event.getId());
                legacyTree = legacyTree == null
                        ? new java.util.ArrayList<>() : new java.util.ArrayList<>(legacyTree);
                CommentDO root = existingRoot == null ? event : existingRoot;
                if (legacyTree.stream().noneMatch(comment -> Objects.equals(comment.getId(), event.getId()))) {
                    legacyTree.add(0, root);
                }

                java.util.List<CommentTombstoneDO> persistedTombstones = new java.util.ArrayList<>();
                for (CommentDO legacyComment : legacyTree) {
                    CommentTombstoneDO tombstone = toLegacyTombstone(legacyComment, event, event.getId());
                    CommentTombstoneDO existingTombstone =
                            commentTombstoneMapper.selectByCommentIdForUpdate(tombstone.getCommentId());
                    if (existingTombstone != null) {
                        if (Objects.equals(existingTombstone.getDeleteRootId(), event.getId())) {
                            persistedTombstones.add(existingTombstone);
                        }
                        continue;
                    }

                    int inserted = commentTombstoneMapper.insertIfAbsent(tombstone);
                    if (inserted > 0) {
                        persistedTombstones.add(tombstone);
                        continue;
                    }

                    CommentTombstoneDO racedTombstone =
                            commentTombstoneMapper.selectByCommentIdForUpdate(tombstone.getCommentId());
                    if (racedTombstone == null) {
                        throw new IllegalStateException("failed to persist legacy comment tombstone: "
                                + tombstone.getCommentId());
                    }
                    if (Objects.equals(racedTombstone.getDeleteRootId(), event.getId())) {
                        persistedTombstones.add(racedTombstone);
                    }
                }

                if (persistedTombstones.isEmpty()) {
                    commentInboxService.markApplied(record);
                    return null;
                }
                tombstones = persistedTombstones;
            }

            List<Long> commentIds = tombstones.stream()
                    .map(com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentTombstoneDO::getCommentId)
                    .filter(Objects::nonNull).distinct().toList();
            if (!commentIds.isEmpty()) {
                commentDOMapper.deleteByIds(commentIds);
            }
            int noteCountDelta = 0;
            java.util.Map<Long, Integer> childCountDeltas = new java.util.HashMap<>();
            for (var tombstone : tombstones) {
                String countEventKey = "comment-count-publish:" + tombstone.getCommentId();
                Integer appliedDelta = commentCountEventMapper.selectAppliedDelta(countEventKey, "NOTE_COMMENT_TOTAL");
                if (appliedDelta == null) {
                    if (commentOutboxService.existsByEventKey(countEventKey)) {
                        throw new IllegalStateException("waiting for comment count event before applying deletion");
                    }
                    appliedDelta = 1; // Historical comments predate the event ledger and were already counted.
                }
                noteCountDelta += appliedDelta;
                if (Objects.equals(tombstone.getLevel(), CommentLevelEnum.TWO.getCode()) && tombstone.getParentId() != null) {
                    Integer childDelta = commentCountEventMapper.selectAppliedDelta(countEventKey, "CHILD_COMMENT_TOTAL");
                    if (childDelta == null && commentOutboxService.existsByEventKey(countEventKey)) {
                        throw new IllegalStateException("waiting for child count event before applying deletion");
                    }
                    childCountDeltas.merge(tombstone.getParentId(), childDelta == null ? 1 : childDelta, Integer::sum);
                }
            }
            if (noteCountDelta > 0 && !tombstones.isEmpty()) {
                noteCountDOMapper.updateCommentTotalByNoteId(tombstones.get(0).getNoteId(), -noteCountDelta);
            }
            childCountDeltas.forEach((parentId, count) -> {
                if (count > 0) commentDOMapper.updateChildCommentTotal(parentId, -count);
            });

            for (var tombstone : tombstones) {
                CommentCacheInvalidationMqDTO cacheDto = CommentCacheInvalidationMqDTO.builder()
                        .eventId("comment-cache-delete-applied:" + event.getId() + ":" + tombstone.getCommentId())
                        .commentId(tombstone.getCommentId()).noteId(tombstone.getNoteId())
                        .parentId(tombstone.getParentId()).level(tombstone.getLevel()).build();
                commentOutboxService.enqueue(cacheDto.getEventId(), MQConstants.TOPIC_COMMENT_CACHE_INVALIDATE,
                        JsonUtils.toJsonString(cacheDto));
                commentOutboxService.enqueue("comment-local-cache-delete:" + tombstone.getCommentId(),
                        MQConstants.TOPIC_DELETE_COMMENT_LOCAL_CACHE, String.valueOf(tombstone.getCommentId()));
            }

            java.util.Set<Long> deletedIds = new java.util.HashSet<>(commentIds);
            for (Long parentId : childCountDeltas.keySet()) {
                if (deletedIds.contains(parentId)) {
                    continue;
                }
                CommentDO firstReply = commentDOMapper.selectEarliestByParentId(parentId);
                long firstReplyId = firstReply == null ? 0L : firstReply.getId();
                commentDOMapper.updateFirstReplyCommentIdByPrimaryKey(firstReplyId, parentId);
                if (firstReply == null) {
                    String markerEventId = "comment-clear-first-reply-marker:" + event.getId() + ":" + parentId;
                    CommentCacheInvalidationMqDTO clearMarkerDto = CommentCacheInvalidationMqDTO.builder()
                            .eventId(markerEventId)
                            .commentId(parentId)
                            .parentId(parentId)
                            .clearFirstReplyMarker(true)
                            .build();
                    commentOutboxService.enqueue(markerEventId, MQConstants.TOPIC_COMMENT_CACHE_INVALIDATE,
                            JsonUtils.toJsonString(clearMarkerDto));
                }
                commentOutboxService.enqueue("comment-local-cache-delete-parent:" + event.getId() + ":" + parentId,
                        MQConstants.TOPIC_DELETE_COMMENT_LOCAL_CACHE, String.valueOf(parentId));
                commentOutboxService.enqueue("comment-heat-delete:" + event.getId(),
                        MQConstants.TOPIC_COMMENT_HEAT_UPDATE, JsonUtils.toJsonString(List.of(parentId)));
            }
            commentInboxService.markApplied(record);
            return null;
        });
    }

    private static CommentTombstoneDO toLegacyTombstone(CommentDO source, CommentDO event, Long deleteRootId) {
        boolean sameAsEvent = Objects.equals(source.getId(), event.getId());
        Long commentId = firstNonNull(source.getId(), event.getId());
        Long noteId = firstNonNull(source.getNoteId(), event.getNoteId());
        Integer level = firstNonNull(source.getLevel(), sameAsEvent ? event.getLevel() : null);
        Long parentId = firstNonNull(source.getParentId(), sameAsEvent ? event.getParentId() : null);
        if (parentId == null && Objects.equals(level, CommentLevelEnum.ONE.getCode())) {
            parentId = noteId;
        }
        LocalDateTime createTime = firstNonNull(source.getCreateTime(), sameAsEvent ? event.getCreateTime() : null);
        String contentUuid = StringUtils.isNotBlank(source.getContentUuid())
                ? source.getContentUuid() : sameAsEvent ? event.getContentUuid() : null;

        if (commentId == null || noteId == null || level == null || parentId == null || createTime == null) {
            throw new IllegalStateException("legacy comment delete event lacks tombstone metadata");
        }

        Boolean isContentEmpty = firstNonNull(source.getIsContentEmpty(),
                sameAsEvent ? event.getIsContentEmpty() : null);
        boolean contentEmpty = Boolean.TRUE.equals(isContentEmpty)
                || StringUtils.isBlank(contentUuid);
        return CommentTombstoneDO.builder()
                .commentId(commentId)
                .deleteRootId(deleteRootId)
                .noteId(noteId)
                .level(level)
                .parentId(parentId)
                .contentUuid(contentUuid)
                .contentEmpty(contentEmpty)
                .createTime(createTime)
                .build();
    }

    private static <T> T firstNonNull(T primary, T fallback) {
        return primary == null ? fallback : primary;
    }

    private void processCacheInvalidation(CommentInboxDO record) {
        CommentCacheInvalidationMqDTO dto = JsonUtils.parseObject(record.getPayload(), CommentCacheInvalidationMqDTO.class);
        if (dto == null || dto.getCommentId() == null) {
            throw new IllegalArgumentException("invalid comment cache invalidation event");
        }
        if (dto.getCommentId() != null) {
            redisTemplate.delete(RedisKeyConstants.buildCommentDetailKey(dto.getCommentId()));
        }
        if (dto.getParentId() != null) {
            redisTemplate.delete(RedisKeyConstants.buildCommentDetailKey(dto.getParentId()));
            redisTemplate.delete(RedisKeyConstants.buildChildCommentListKey(dto.getParentId()));
            redisTemplate.delete(RedisKeyConstants.buildCountCommentKey(dto.getParentId()));
        }
        if (Boolean.TRUE.equals(dto.getClearFirstReplyMarker())) {
            redisTemplate.delete(RedisKeyConstants.buildHaveFirstReplyCommentKey(dto.getCommentId()));
        }
        if (dto.getNoteId() != null) {
            redisTemplate.delete(RedisKeyConstants.buildNoteCommentTotalKey(dto.getNoteId()));
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptSource(new ResourceScriptSource(new ClassPathResource("/lua/update_hot_comments.lua")));
            script.setResultType(Long.class);
            HotCommentRedisScriptExecutor.execute(redisTemplate, script, List.of(RedisKeyConstants.buildCommentListKey(dto.getNoteId()),
                    RedisKeyConstants.buildCommentListGenerationKey(dto.getNoteId()),
                    RedisKeyConstants.buildCommentListReadyKey(dto.getNoteId())), "INVALIDATE");
        }
        transactionTemplate.execute(status -> {
            commentInboxService.markApplied(record);
            return null;
        });
    }

    private static String resolveContentUuid(String eventKey, PublishCommentMqDTO dto, CommentDO existing) {
        if (existing != null && StringUtils.isNotBlank(existing.getContentUuid())) {
            return existing.getContentUuid();
        }
        if (StringUtils.isBlank(dto.getContent())) {
            return null;
        }
        if (StringUtils.isNotBlank(dto.getContentUuid())) {
            return dto.getContentUuid();
        }
        return UUID.nameUUIDFromBytes(eventKey.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private CommentBO toCommentBO(PublishCommentMqDTO dto, String contentUuid, CommentDO reply) {
        String imageUrl = StringUtils.defaultString(dto.getImageUrl());
        boolean contentEmpty = StringUtils.isBlank(dto.getContent());
        Integer level = CommentLevelEnum.ONE.getCode();
        Long parentId = dto.getNoteId();
        Long replyCommentId = 0L;
        Long replyUserId = 0L;

        if (dto.getReplyCommentId() != null && reply != null) {
            level = CommentLevelEnum.TWO.getCode();
            parentId = Objects.equals(reply.getLevel(), CommentLevelEnum.TWO.getCode())
                    ? reply.getParentId() : reply.getId();
            replyCommentId = dto.getReplyCommentId();
            replyUserId = reply.getUserId();
        }

        return CommentBO.builder()
                .id(dto.getCommentId())
                .noteId(dto.getNoteId())
                .userId(dto.getCreatorId())
                .contentUuid(contentUuid)
                .content(dto.getContent())
                .isContentEmpty(contentEmpty)
                .imageUrl(imageUrl)
                .level(level)
                .parentId(parentId)
                .replyCommentId(replyCommentId)
                .replyUserId(replyUserId)
                .createTime(dto.getCreateTime())
                .updateTime(dto.getCreateTime())
                .isTop(false)
                .replyTotal(0L)
                .likeTotal(0L)
                .build();
    }

    private static CommentDO toCommentDO(CommentBO commentBO) {
        return CommentDO.builder()
                .id(commentBO.getId())
                .noteId(commentBO.getNoteId())
                .userId(commentBO.getUserId())
                .contentUuid(commentBO.getContentUuid())
                .isContentEmpty(commentBO.getIsContentEmpty())
                .imageUrl(commentBO.getImageUrl())
                .level(commentBO.getLevel())
                .replyTotal(commentBO.getReplyTotal())
                .likeTotal(commentBO.getLikeTotal())
                .parentId(commentBO.getParentId())
                .replyCommentId(commentBO.getReplyCommentId())
                .replyUserId(commentBO.getReplyUserId())
                .isTop(commentBO.getIsTop())
                .createTime(commentBO.getCreateTime())
                .updateTime(commentBO.getUpdateTime())
                .build();
    }
}
