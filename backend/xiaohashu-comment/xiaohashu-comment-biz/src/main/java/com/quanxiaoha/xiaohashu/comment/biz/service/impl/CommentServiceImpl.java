package com.quanxiaoha.xiaohashu.comment.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.constant.DateConstants;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.PageResponse;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.DateUtils;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.comment.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.comment.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentLikeDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentTombstoneDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentLikeDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentTombstoneMapper;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.comment.biz.enums.CommentLikeLuaResultEnum;
import com.quanxiaoha.xiaohashu.comment.biz.enums.CommentUnlikeLuaResultEnum;
import com.quanxiaoha.xiaohashu.comment.biz.enums.CommentLevelEnum;
import com.quanxiaoha.xiaohashu.comment.biz.enums.LikeUnlikeCommentTypeEnum;
import com.quanxiaoha.xiaohashu.comment.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.LikeUnlikeCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.CommentCacheInvalidationMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindCommentItemRspVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindCommentPageListReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindChildCommentItemRspVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.FindChildCommentPageListReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.LikeCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.UnLikeCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.dto.PublishCommentMqDTO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.PublishCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.model.vo.DeleteCommentReqVO;
import com.quanxiaoha.xiaohashu.comment.biz.retry.SendMqRetryHelper;
import com.quanxiaoha.xiaohashu.comment.biz.service.CommentOutboxService;
import com.quanxiaoha.xiaohashu.comment.biz.service.HotCommentRedisScriptExecutor;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.DistributedIdGeneratorRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.UserRpcService;
import com.quanxiaoha.xiaohashu.comment.biz.service.CommentService;
import com.quanxiaoha.xiaohashu.kv.dto.req.FindCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.rsp.FindCommentContentRspDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CommentServiceImpl implements CommentService {

    /**
     * 评论详情本地缓存
     */
    private static final Cache<Long, String> LOCAL_CACHE = Caffeine.newBuilder()
            .initialCapacity(10000)
            .maximumSize(10000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    @Resource
    private SendMqRetryHelper sendMqRetryHelper;

    @Resource
    private CommentOutboxService commentOutboxService;

    @Resource
    private DistributedIdGeneratorRpcService distributedIdGeneratorRpcService;

    @Resource
    private CommentDOMapper commentDOMapper;

    @Resource
    private CommentLikeDOMapper commentLikeDOMapper;

    @Resource
    private CommentTombstoneMapper commentTombstoneMapper;

    @Resource
    private NoteCountDOMapper noteCountDOMapper;

    @Resource
    private KeyValueRpcService keyValueRpcService;

    @Resource
    private UserRpcService userRpcService;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "taskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public Response<?> publishComment(PublishCommentReqVO publishCommentReqVO) {
        String content = publishCommentReqVO.getContent();
        String imageUrl = publishCommentReqVO.getImageUrl();

        Preconditions.checkArgument(StringUtils.isNotBlank(content) || StringUtils.isNotBlank(imageUrl),
                "评论正文和图片不能同时为空");

        Long creatorId = LoginUserContextHolder.getUserId();
        String commentId = distributedIdGeneratorRpcService.generateCommentId();
        PublishCommentMqDTO publishCommentMqDTO = PublishCommentMqDTO.builder()
                .commentId(Long.valueOf(commentId))
                .noteId(publishCommentReqVO.getNoteId())
                .content(content)
                .imageUrl(imageUrl)
                .replyCommentId(publishCommentReqVO.getReplyCommentId())
                .createTime(LocalDateTime.now())
                .creatorId(creatorId)
                .contentUuid(StringUtils.isNotBlank(content) ? UUID.randomUUID().toString() : null)
                .build();

        String eventKey = "comment-publish:" + publishCommentMqDTO.getCommentId();
        String payload = JsonUtils.toJsonString(publishCommentMqDTO);
        transactionTemplate.execute(status -> {
            commentOutboxService.enqueue(eventKey, MQConstants.TOPIC_PUBLISH_COMMENT, payload);
            return null;
        });

        return Response.success();
    }

    @Override
    public Response<?> deleteComment(DeleteCommentReqVO deleteCommentReqVO) {
        Long commentId = deleteCommentReqVO.getCommentId();

        CommentDO commentDO = commentDOMapper.selectByPrimaryKey(commentId);
        if (Objects.isNull(commentDO)) {
            throw new BizException(ResponseCodeEnum.COMMENT_NOT_FOUND);
        }

        Long currentUserId = LoginUserContextHolder.getUserId();
        if (!Objects.equals(currentUserId, commentDO.getUserId())) {
            throw new BizException(ResponseCodeEnum.COMMENT_CANT_OPERATE);
        }

        Long lockRootId = Objects.equals(commentDO.getLevel(), CommentLevelEnum.TWO.getCode())
                && commentDO.getParentId() != null ? commentDO.getParentId() : commentId;
        transactionTemplate.execute(status -> {
            // Match the publication lock order: level-one ancestor first, then target. This makes
            // collecting the recursive delete tree atomic with respect to replies targeting any child.
            CommentDO lockedRoot = commentDOMapper.selectByPrimaryKeyForUpdate(lockRootId);
            if (lockedRoot == null) {
                throw new BizException(ResponseCodeEnum.COMMENT_NOT_FOUND);
            }
            CommentDO lockedComment = Objects.equals(lockRootId, commentId)
                    ? lockedRoot : commentDOMapper.selectByPrimaryKeyForUpdate(commentId);
            if (lockedComment == null) {
                throw new BizException(ResponseCodeEnum.COMMENT_NOT_FOUND);
            }
            if (!Objects.equals(currentUserId, lockedComment.getUserId())) {
                throw new BizException(ResponseCodeEnum.COMMENT_CANT_OPERATE);
            }
            if (!Objects.equals(lockRootId, commentId)
                    && commentTombstoneMapper.selectByCommentIdForUpdate(lockRootId) != null) {
                throw new BizException(ResponseCodeEnum.COMMENT_NOT_FOUND);
            }
            if (commentTombstoneMapper.selectByCommentIdForUpdate(commentId) != null) {
                throw new BizException(ResponseCodeEnum.COMMENT_NOT_FOUND);
            }

            // Publish the root tombstone before collecting the recursive delete set. New child
            // publications hold the same ancestor row lock and will observe this tombstone.
            commentTombstoneMapper.insertIfAbsent(CommentTombstoneDO.builder()
                    .commentId(lockedComment.getId()).deleteRootId(commentId).noteId(lockedComment.getNoteId())
                    .level(lockedComment.getLevel()).parentId(lockedComment.getParentId())
                    .contentUuid(lockedComment.getContentUuid()).contentEmpty(lockedComment.getIsContentEmpty())
                    .createTime(lockedComment.getCreateTime()).build());

            List<CommentDO> deleteTree = commentDOMapper.selectDeleteTreeByRootCommentId(commentId);
            if (CollUtil.isEmpty(deleteTree)) {
                deleteTree = List.of(lockedComment);
            }
            for (CommentDO deletedComment : deleteTree) {
                if (Objects.equals(deletedComment.getId(), commentDO.getId())) {
                    continue; // The root tombstone was written before collecting the delete tree.
                }
                commentTombstoneMapper.insertIfAbsent(CommentTombstoneDO.builder()
                        .commentId(deletedComment.getId())
                        .deleteRootId(commentId)
                        .noteId(deletedComment.getNoteId())
                        .level(deletedComment.getLevel())
                        .parentId(deletedComment.getParentId())
                        .contentUuid(deletedComment.getContentUuid())
                        .contentEmpty(deletedComment.getIsContentEmpty())
                        .createTime(deletedComment.getCreateTime())
                        .build());
            }
            commentDOMapper.deleteByPrimaryKey(commentId);
            commentOutboxService.enqueue("comment-delete:" + commentId,
                    MQConstants.TOPIC_DELETE_COMMENT, JsonUtils.toJsonString(lockedComment));
            commentOutboxService.enqueue("comment-local-cache-delete:" + commentId,
                    MQConstants.TOPIC_DELETE_COMMENT_LOCAL_CACHE, String.valueOf(commentId));
            CommentCacheInvalidationMqDTO cacheDto = CommentCacheInvalidationMqDTO.builder()
                    .eventId("comment-cache-delete:" + commentId)
                    .commentId(commentId)
                    .noteId(lockedComment.getNoteId())
                    .parentId(lockedComment.getParentId())
                    .level(lockedComment.getLevel())
                    .build();
            commentOutboxService.enqueue("comment-cache-delete:" + commentId,
                    MQConstants.TOPIC_COMMENT_CACHE_INVALIDATE, JsonUtils.toJsonString(cacheDto));
            return null;
        });

        return Response.success();
    }

    @Override
    public void deleteCommentLocalCache(Long commentId) {
        LOCAL_CACHE.invalidate(commentId);
    }

    @Override
    public Response<?> likeComment(LikeCommentReqVO likeCommentReqVO) {
        Long commentId = likeCommentReqVO.getCommentId();
        checkCommentIsExist(commentId);

        Long userId = LoginUserContextHolder.getUserId();
        String bloomUserCommentLikeListKey = RedisKeyConstants.buildBloomCommentLikesKey(userId);
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("/lua/bloom_comment_like_check.lua")));
        script.setResultType(Long.class);

        Long result = redisTemplate.execute(script, Collections.singletonList(bloomUserCommentLikeListKey), commentId);
        CommentLikeLuaResultEnum commentLikeLuaResultEnum = CommentLikeLuaResultEnum.valueOf(result);
        if (Objects.isNull(commentLikeLuaResultEnum)) {
            throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
        }

        switch (commentLikeLuaResultEnum) {
            case NOT_EXIST -> {
                int count = commentLikeDOMapper.selectCountByUserIdAndCommentId(userId, commentId);
                long expireSeconds = 60 * 60 + RandomUtil.randomInt(60 * 60);

                if (count > 0) {
                    threadPoolTaskExecutor.submit(() ->
                            batchAddCommentLike2BloomAndExpire(userId, expireSeconds, bloomUserCommentLikeListKey));
                    throw new BizException(ResponseCodeEnum.COMMENT_ALREADY_LIKED);
                }

                batchAddCommentLike2BloomAndExpire(userId, expireSeconds, bloomUserCommentLikeListKey);

                DefaultRedisScript<Long> addCommentLikeScript = new DefaultRedisScript<>();
                addCommentLikeScript.setScriptSource(new ResourceScriptSource(
                        new ClassPathResource("/lua/bloom_add_comment_like_and_expire.lua")));
                addCommentLikeScript.setResultType(Long.class);
                redisTemplate.execute(addCommentLikeScript, Collections.singletonList(bloomUserCommentLikeListKey),
                        commentId, expireSeconds);
            }
            case COMMENT_LIKED -> {
                int count = commentLikeDOMapper.selectCountByUserIdAndCommentId(userId, commentId);
                if (count > 0) {
                    throw new BizException(ResponseCodeEnum.COMMENT_ALREADY_LIKED);
                }
            }
            case COMMENT_LIKE_SUCCESS -> {
                // 布隆过滤器已添加本次点赞的评论 ID
            }
        }

        LikeUnlikeCommentMqDTO likeUnlikeCommentMqDTO = LikeUnlikeCommentMqDTO.builder()
                .userId(userId)
                .commentId(commentId)
                .type(LikeUnlikeCommentTypeEnum.LIKE.getCode())
                .createTime(LocalDateTime.now())
                .build();
        Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(likeUnlikeCommentMqDTO)).build();
        String destination = MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE + ":" + MQConstants.TAG_LIKE;
        String hashKey = String.valueOf(userId);

        rocketMQTemplate.asyncSendOrderly(destination, message, hashKey, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.info("==> 【评论点赞】MQ 发送成功，SendResult: {}", sendResult);
            }

            @Override
            public void onException(Throwable throwable) {
                log.error("==> 【评论点赞】MQ 发送异常: ", throwable);
            }
        });

        return Response.success();
    }

    @Override
    public Response<?> unlikeComment(UnLikeCommentReqVO unLikeCommentReqVO) {
        Long commentId = unLikeCommentReqVO.getCommentId();
        checkCommentIsExist(commentId);

        Long userId = LoginUserContextHolder.getUserId();
        String bloomUserCommentLikeListKey = RedisKeyConstants.buildBloomCommentLikesKey(userId);
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("/lua/bloom_comment_unlike_check.lua")));
        script.setResultType(Long.class);

        Long result = redisTemplate.execute(script, Collections.singletonList(bloomUserCommentLikeListKey), commentId);
        CommentUnlikeLuaResultEnum commentUnlikeLuaResultEnum = CommentUnlikeLuaResultEnum.valueOf(result);
        if (Objects.isNull(commentUnlikeLuaResultEnum)) {
            throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
        }

        switch (commentUnlikeLuaResultEnum) {
            case NOT_EXIST -> {
                threadPoolTaskExecutor.submit(() -> {
                    long expireSeconds = 60 * 60 + RandomUtil.randomInt(60 * 60);
                    batchAddCommentLike2BloomAndExpire(userId, expireSeconds, bloomUserCommentLikeListKey);
                });

                int count = commentLikeDOMapper.selectCountByUserIdAndCommentId(userId, commentId);
                if (count == 0) {
                    throw new BizException(ResponseCodeEnum.COMMENT_NOT_LIKED);
                }
            }
            case COMMENT_NOT_LIKED -> throw new BizException(ResponseCodeEnum.COMMENT_NOT_LIKED);
            case COMMENT_LIKED -> {
                // Bloom Filter 命中后按教程直接发送取消点赞消息。
            }
        }

        LikeUnlikeCommentMqDTO likeUnlikeCommentMqDTO = LikeUnlikeCommentMqDTO.builder()
                .userId(userId)
                .commentId(commentId)
                .type(LikeUnlikeCommentTypeEnum.UNLIKE.getCode())
                .createTime(LocalDateTime.now())
                .build();
        Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(likeUnlikeCommentMqDTO)).build();
        String destination = MQConstants.TOPIC_COMMENT_LIKE_OR_UNLIKE + ":" + MQConstants.TAG_UNLIKE;
        String hashKey = String.valueOf(userId);

        rocketMQTemplate.asyncSendOrderly(destination, message, hashKey, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.info("==> 【评论取消点赞】MQ 发送成功，SendResult: {}", sendResult);
            }

            @Override
            public void onException(Throwable throwable) {
                log.error("==> 【评论取消点赞】MQ 发送异常: ", throwable);
            }
        });

        return Response.success();
    }

    private void batchAddCommentLike2BloomAndExpire(Long userId, long expireSeconds,
                                                     String bloomUserCommentLikeListKey) {
        try {
            List<CommentLikeDO> commentLikeDOS = commentLikeDOMapper.selectByUserId(userId);
            if (CollUtil.isNotEmpty(commentLikeDOS)) {
                DefaultRedisScript<Long> script = new DefaultRedisScript<>();
                script.setScriptSource(new ResourceScriptSource(
                        new ClassPathResource("/lua/bloom_batch_add_comment_like_and_expire.lua")));
                script.setResultType(Long.class);

                List<Object> luaArgs = Lists.newArrayList();
                commentLikeDOS.forEach(commentLikeDO -> luaArgs.add(commentLikeDO.getCommentId()));
                luaArgs.add(expireSeconds);
                redisTemplate.execute(script, Collections.singletonList(bloomUserCommentLikeListKey), luaArgs.toArray());
            }
        } catch (Exception e) {
            log.error("## 异步初始化【评论点赞】布隆过滤器异常: ", e);
        }
    }

    private void checkCommentIsExist(Long commentId) {
        String localCacheJson = LOCAL_CACHE.getIfPresent(commentId);
        if (StringUtils.isBlank(localCacheJson)) {
            String commentDetailRedisKey = RedisKeyConstants.buildCommentDetailKey(commentId);
            boolean hasKey = redisTemplate.hasKey(commentDetailRedisKey);
            if (!hasKey) {
                CommentDO commentDO = commentDOMapper.selectByPrimaryKey(commentId);
                if (Objects.isNull(commentDO)) {
                    throw new BizException(ResponseCodeEnum.COMMENT_NOT_FOUND);
                }
            }
        }
    }

    @Override
    public PageResponse<FindCommentItemRspVO> findCommentPageList(FindCommentPageListReqVO findCommentPageListReqVO) {
        Long noteId = findCommentPageListReqVO.getNoteId();
        Integer pageNo = findCommentPageListReqVO.getPageNo();
        long pageSize = 10;

        String noteCommentTotalKey = RedisKeyConstants.buildNoteCommentTotalKey(noteId);
        Number commentTotal = (Number) redisTemplate.opsForHash()
                .get(noteCommentTotalKey, RedisKeyConstants.FIELD_COMMENT_TOTAL);
        long count = Objects.isNull(commentTotal) ? 0L : commentTotal.longValue();

        if (Objects.isNull(commentTotal)) {
            Long dbCount = noteCountDOMapper.selectCommentTotalByNoteId(noteId);
            if (Objects.isNull(dbCount)) {
                throw new BizException(ResponseCodeEnum.COMMENT_NOT_FOUND);
            }

            count = dbCount;
            threadPoolTaskExecutor.execute(() -> syncNoteCommentTotal2Redis(noteCommentTotalKey, dbCount));
        }

        if (count == 0) {
            return PageResponse.success(null, pageNo, 0);
        }

        List<FindCommentItemRspVO> commentRspVOS = Lists.newArrayList();
        long offset = PageResponse.getOffset(pageNo, pageSize);

        String commentZSetKey = RedisKeyConstants.buildCommentListKey(noteId);
        List<String> hotCommentPage = offset < 500 ? readHotCommentPage(commentZSetKey, noteId, offset, pageSize)
                : null;
        if (offset < 500 && Objects.isNull(hotCommentPage)) {
            threadPoolTaskExecutor.execute(() -> syncHeatComments2Redis(commentZSetKey, noteId));
        }

        if (offset < 500 && Objects.nonNull(hotCommentPage)) {
            if (CollUtil.isNotEmpty(hotCommentPage)) {
                List<Long> orderedCommentIds = hotCommentPage.stream()
                        .map(commentId -> Long.valueOf(commentId.toString()))
                        .toList();
                Map<Long, String> localCacheDetails = LOCAL_CACHE.getAllPresent(orderedCommentIds);
                Map<Long, FindCommentItemRspVO> detailsById = Maps.newHashMap();
                List<Long> localCacheMissIds = Lists.newArrayList();
                orderedCommentIds.forEach(commentId -> {
                    String json = localCacheDetails.get(commentId);
                    if (StringUtils.isBlank(json)) {
                        if (Objects.nonNull(json)) {
                            LOCAL_CACHE.invalidate(commentId);
                        }
                        localCacheMissIds.add(commentId);
                    } else {
                        detailsById.put(commentId, JsonUtils.parseObject(json, FindCommentItemRspVO.class));
                    }
                });

                List<String> commentIdKeys = localCacheMissIds.stream()
                        .map(RedisKeyConstants::buildCommentDetailKey)
                        .toList();
                List<Object> commentsJsonList = CollUtil.isEmpty(commentIdKeys)
                        ? List.of() : redisTemplate.opsForValue().multiGet(commentIdKeys);
                List<Long> databaseMissIds = Lists.newArrayList();
                for (int i = 0; i < localCacheMissIds.size(); i++) {
                    Object value = commentsJsonList != null && i < commentsJsonList.size()
                            ? commentsJsonList.get(i) : null;
                    Long commentId = localCacheMissIds.get(i);
                    if (Objects.nonNull(value)) {
                        String commentJson = value.toString();
                        FindCommentItemRspVO commentDetail = JsonUtils.parseObject(commentJson,
                                FindCommentItemRspVO.class);
                        if (Objects.nonNull(commentDetail)) {
                            detailsById.put(commentId, commentDetail);
                            LOCAL_CACHE.put(commentId, commentJson);
                        } else {
                            databaseMissIds.add(commentId);
                        }
                    } else {
                        databaseMissIds.add(commentId);
                    }
                }

                if (CollUtil.isNotEmpty(databaseMissIds)) {
                    List<CommentDO> commentDOS = commentDOMapper.selectByCommentIds(databaseMissIds);
                    List<FindCommentItemRspVO> databaseDetails = Lists.newArrayList();
                    getCommentDataAndSync2Redis(commentDOS, noteId, databaseDetails);
                    databaseDetails.forEach(detail -> detailsById.put(detail.getCommentId(), detail));
                }

                orderedCommentIds.stream()
                        .map(detailsById::get)
                        .filter(Objects::nonNull)
                        .forEach(commentRspVOS::add);
                setCommentCountData(commentRspVOS, databaseMissIds);
            }
            return PageResponse.success(commentRspVOS, pageNo, count, pageSize);
        }

        List<CommentDO> oneLevelCommentDOS = commentDOMapper.selectPageList(noteId, offset, pageSize);
        getCommentDataAndSync2Redis(oneLevelCommentDOS, noteId, commentRspVOS);
        return PageResponse.success(commentRspVOS, pageNo, count, pageSize);
    }

    @Override
    public PageResponse<FindChildCommentItemRspVO> findChildCommentPageList(
            FindChildCommentPageListReqVO findChildCommentPageListReqVO) {
        Long parentCommentId = findChildCommentPageListReqVO.getParentCommentId();
        Integer pageNo = findChildCommentPageListReqVO.getPageNo();
        long pageSize = 6;

        String countCommentKey = RedisKeyConstants.buildCountCommentKey(parentCommentId);
        Number redisCount = (Number) redisTemplate.opsForHash()
                .get(countCommentKey, RedisKeyConstants.FIELD_CHILD_COMMENT_TOTAL);
        long count = Objects.isNull(redisCount) ? 0L : redisCount.longValue();

        if (Objects.isNull(redisCount)) {
            Long dbCount = commentDOMapper.selectChildCommentTotalById(parentCommentId);
            if (Objects.isNull(dbCount)) {
                throw new BizException(ResponseCodeEnum.PARENT_COMMENT_NOT_FOUND);
            }

            count = dbCount;
            threadPoolTaskExecutor.execute(() -> syncCommentCount2Redis(countCommentKey, dbCount));
        }

        if (count == 0) {
            return PageResponse.success(null, pageNo, 0);
        }

        List<FindChildCommentItemRspVO> childCommentRspVOS = Lists.newArrayList();
        long offset = PageResponse.getOffset(pageNo, pageSize);
        String childCommentZSetKey = RedisKeyConstants.buildChildCommentListKey(parentCommentId);
        boolean hasKey = redisTemplate.hasKey(childCommentZSetKey);

        if (!hasKey) {
            threadPoolTaskExecutor.execute(() -> syncChildComments2Redis(parentCommentId, childCommentZSetKey));
        }

        if (hasKey && offset < 6 * 10) {
            Set<Object> childCommentIds = redisTemplate.opsForZSet()
                    .range(childCommentZSetKey, offset, offset + pageSize - 1);

            if (CollUtil.isNotEmpty(childCommentIds)) {
                List<Object> childCommentIdList = Lists.newArrayList(childCommentIds);
                List<String> commentIdKeys = childCommentIdList.stream()
                        .map(RedisKeyConstants::buildCommentDetailKey)
                        .toList();
                List<Object> commentsJsonList = redisTemplate.opsForValue().multiGet(commentIdKeys);
                List<Long> expiredChildCommentIds = Lists.newArrayList();

                for (int i = 0; i < commentsJsonList.size(); i++) {
                    String commentJson = (String) commentsJsonList.get(i);
                    Long commentId = Long.valueOf(childCommentIdList.get(i).toString());
                    if (Objects.nonNull(commentJson)) {
                        childCommentRspVOS.add(JsonUtils.parseObject(commentJson, FindChildCommentItemRspVO.class));
                    } else {
                        expiredChildCommentIds.add(commentId);
                    }
                }

                if (CollUtil.isNotEmpty(childCommentRspVOS)) {
                    setChildCommentCountData(childCommentRspVOS, expiredChildCommentIds);
                }

                if (CollUtil.isNotEmpty(expiredChildCommentIds)) {
                    List<CommentDO> commentDOS = commentDOMapper.selectByCommentIds(expiredChildCommentIds);
                    getChildCommentDataAndSync2Redis(commentDOS, childCommentRspVOS);
                }

                childCommentRspVOS = childCommentRspVOS.stream()
                        .sorted(Comparator.comparing(FindChildCommentItemRspVO::getCommentId))
                        .collect(Collectors.toList());

                return PageResponse.success(childCommentRspVOS, pageNo, count, pageSize);
            }
        }

        List<CommentDO> childCommentDOS = commentDOMapper.selectChildPageList(parentCommentId, offset, pageSize);
        getChildCommentDataAndSync2Redis(childCommentDOS, childCommentRspVOS);
        return PageResponse.success(childCommentRspVOS, pageNo, count, pageSize);
    }

    /**
     * 设置评论 VO 的计数数据。
     */
    private void setCommentCountData(List<FindCommentItemRspVO> commentRspVOS,
                                     List<Long> expiredCommentIds) {
        List<Long> notExpiredCommentIds = Lists.newArrayList();
        commentRspVOS.forEach(commentRspVO -> {
            notExpiredCommentIds.add(commentRspVO.getCommentId());
            FindCommentItemRspVO firstReplyComment = commentRspVO.getFirstReplyComment();
            if (Objects.nonNull(firstReplyComment)) {
                notExpiredCommentIds.add(firstReplyComment.getCommentId());
            }
        });

        Map<Long, Map<Object, Object>> commentIdAndCountMap =
                getCommentCountDataAndSync2RedisHash(notExpiredCommentIds);

        for (FindCommentItemRspVO commentRspVO : commentRspVOS) {
            Long commentId = commentRspVO.getCommentId();
            if (CollUtil.isNotEmpty(expiredCommentIds) && expiredCommentIds.contains(commentId)) {
                continue;
            }

            Map<Object, Object> hash = commentIdAndCountMap.get(commentId);
            if (CollUtil.isNotEmpty(hash)) {
                Object childCommentTotal = hash.get(RedisKeyConstants.FIELD_CHILD_COMMENT_TOTAL);
                commentRspVO.setChildCommentTotal(Objects.isNull(childCommentTotal)
                        ? 0L : Long.parseLong(childCommentTotal.toString()));
                Object likeTotal = hash.get(RedisKeyConstants.FIELD_LIKE_TOTAL);
                commentRspVO.setLikeTotal(Objects.isNull(likeTotal)
                        ? 0L : Long.parseLong(likeTotal.toString()));

                FindCommentItemRspVO firstReplyComment = commentRspVO.getFirstReplyComment();
                if (Objects.nonNull(firstReplyComment)) {
                    Map<Object, Object> firstReplyHash = commentIdAndCountMap.get(firstReplyComment.getCommentId());
                    if (CollUtil.isNotEmpty(firstReplyHash)) {
                        Object firstReplyLikeTotal = firstReplyHash.get(RedisKeyConstants.FIELD_LIKE_TOTAL);
                        firstReplyComment.setLikeTotal(Long.parseLong(firstReplyLikeTotal.toString()));
                    }
                }
            }
        }
    }

    private void setChildCommentCountData(List<FindChildCommentItemRspVO> commentRspVOS,
                                          List<Long> expiredCommentIds) {
        List<Long> childCommentIds = commentRspVOS.stream()
                .map(FindChildCommentItemRspVO::getCommentId)
                .toList();
        Map<Long, Map<Object, Object>> commentIdAndCountMap =
                getCommentCountDataAndSync2RedisHash(childCommentIds);

        for (FindChildCommentItemRspVO commentRspVO : commentRspVOS) {
            Long commentId = commentRspVO.getCommentId();
            if (CollUtil.isNotEmpty(expiredCommentIds) && expiredCommentIds.contains(commentId)) {
                continue;
            }

            Map<Object, Object> hash = commentIdAndCountMap.get(commentId);
            if (CollUtil.isNotEmpty(hash)) {
                Object likeTotal = hash.get(RedisKeyConstants.FIELD_LIKE_TOTAL);
                if (Objects.nonNull(likeTotal)) {
                    commentRspVO.setLikeTotal(Long.valueOf(likeTotal.toString()));
                }
            }
        }
    }

    private Map<Long, Map<Object, Object>> getCommentCountDataAndSync2RedisHash(List<Long> commentIds) {
        List<Long> expiredCommentIds = Lists.newArrayList();
        List<String> commentCountKeys = commentIds.stream()
                .map(RedisKeyConstants::buildCountCommentKey)
                .toList();
        List<Object> results = redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                commentCountKeys.forEach(key -> operations.opsForHash().entries(key));
                return null;
            }
        });

        Map<Long, Map<Object, Object>> commentIdAndCountMap = Maps.newHashMap();
        for (int i = 0; i < commentIds.size(); i++) {
            Long commentId = commentIds.get(i);
            Map<Object, Object> hash = (Map<Object, Object>) results.get(i);
            if (CollUtil.isEmpty(hash)) {
                expiredCommentIds.add(commentId);
                continue;
            }
            commentIdAndCountMap.put(commentId, hash);
        }

        if (CollUtil.isNotEmpty(expiredCommentIds)) {
            List<CommentDO> commentDOS = commentDOMapper.selectCommentCountByIds(expiredCommentIds);
            commentDOS.forEach(commentDO -> {
                Integer level = commentDO.getLevel();
                Map<Object, Object> hash = Maps.newHashMap();
                hash.put(RedisKeyConstants.FIELD_LIKE_TOTAL, commentDO.getLikeTotal());
                if (Objects.equals(level, CommentLevelEnum.ONE.getCode())) {
                    hash.put(RedisKeyConstants.FIELD_CHILD_COMMENT_TOTAL, commentDO.getChildCommentTotal());
                }
                commentIdAndCountMap.put(commentDO.getId(), hash);
            });

            threadPoolTaskExecutor.execute(() -> redisTemplate.executePipelined(new SessionCallback<>() {
                @Override
                public Object execute(RedisOperations operations) {
                    commentDOS.forEach(commentDO -> {
                        String key = RedisKeyConstants.buildCountCommentKey(commentDO.getId());
                        Integer level = commentDO.getLevel();
                        Map<String, Long> fieldsMap = Objects.equals(level, CommentLevelEnum.ONE.getCode())
                                ? Map.of(RedisKeyConstants.FIELD_CHILD_COMMENT_TOTAL, commentDO.getChildCommentTotal(),
                                        RedisKeyConstants.FIELD_LIKE_TOTAL, commentDO.getLikeTotal())
                                : Map.of(RedisKeyConstants.FIELD_LIKE_TOTAL, commentDO.getLikeTotal());
                        operations.opsForHash().putAll(key, fieldsMap);
                        long expireTime = 60 * 60 + RandomUtil.randomInt(4 * 60 * 60);
                        operations.expire(key, expireTime, TimeUnit.SECONDS);
                    });
                    return null;
                }
            }));
        }

        return commentIdAndCountMap;
    }

    private void syncCommentCount2Redis(String countCommentKey, Long dbCount) {
        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                operations.opsForHash().put(countCommentKey, RedisKeyConstants.FIELD_CHILD_COMMENT_TOTAL, dbCount);
                long expireTime = 60 * 60 + RandomUtil.randomInt(4 * 60 * 60);
                operations.expire(countCommentKey, expireTime, TimeUnit.SECONDS);
                return null;
            }
        });
    }

    private void syncChildComments2Redis(Long parentCommentId, String childCommentZSetKey) {
        List<CommentDO> childCommentDOS = commentDOMapper.selectChildCommentsByParentIdAndLimit(parentCommentId, 6 * 10);
        if (CollUtil.isNotEmpty(childCommentDOS)) {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
                for (CommentDO childCommentDO : childCommentDOS) {
                    Long commentId = childCommentDO.getId();
                    zSetOps.add(childCommentZSetKey, commentId, commentId.doubleValue());
                }
                int randomExpiryTime = 60 * 60 + RandomUtil.randomInt(4 * 60 * 60);
                redisTemplate.expire(childCommentZSetKey, randomExpiryTime, TimeUnit.SECONDS);
                return null;
            });
        }
    }

    private void getChildCommentDataAndSync2Redis(List<CommentDO> childCommentDOS,
                                                   List<FindChildCommentItemRspVO> childCommentRspVOS) {
        List<FindCommentContentReqDTO> findCommentContentReqDTOS = Lists.newArrayList();
        Set<Long> userIds = Sets.newHashSet();
        Long noteId = null;

        List<FindChildCommentItemRspVO> cacheableChildDetails = Lists.newArrayList();
        for (CommentDO childCommentDO : childCommentDOS) {
            noteId = childCommentDO.getNoteId();
            if (!Boolean.TRUE.equals(childCommentDO.getIsContentEmpty())) {
                findCommentContentReqDTOS.add(FindCommentContentReqDTO.builder()
                        .contentId(childCommentDO.getContentUuid())
                        .yearMonth(DateConstants.DATE_FORMAT_Y_M.format(childCommentDO.getCreateTime()))
                        .build());
            }

            userIds.add(childCommentDO.getUserId());
            Long parentId = childCommentDO.getParentId();
            Long replyCommentId = childCommentDO.getReplyCommentId();
            if (!Objects.equals(parentId, replyCommentId)) {
                userIds.add(childCommentDO.getReplyUserId());
            }
        }

        List<FindCommentContentRspDTO> findCommentContentRspDTOS =
                keyValueRpcService.batchFindCommentContent(noteId, findCommentContentReqDTOS);
        Map<String, String> commentUuidAndContentMap = null;
        if (CollUtil.isNotEmpty(findCommentContentRspDTOS)) {
            commentUuidAndContentMap = findCommentContentRspDTOS.stream()
                    .collect(Collectors.toMap(FindCommentContentRspDTO::getContentId,
                            FindCommentContentRspDTO::getContent));
        }

        List<FindUserByIdRspDTO> findUserByIdRspDTOS = userRpcService.findByIds(userIds.stream().toList());
        Map<Long, FindUserByIdRspDTO> userIdAndDTOMap = toUserMap(findUserByIdRspDTOS);

        for (CommentDO childCommentDO : childCommentDOS) {
            Long userId = childCommentDO.getUserId();
            FindChildCommentItemRspVO childCommentRspVO = FindChildCommentItemRspVO.builder()
                    .userId(userId)
                    .commentId(childCommentDO.getId())
                    .imageUrl(childCommentDO.getImageUrl())
                    .createTime(DateUtils.formatRelativeTime(childCommentDO.getCreateTime()))
                    .likeTotal(childCommentDO.getLikeTotal())
                    .build();

            FindUserByIdRspDTO findUserByIdRspDTO = userIdAndDTOMap.get(userId);
            if (Objects.nonNull(findUserByIdRspDTO)) {
                childCommentRspVO.setAvatar(findUserByIdRspDTO.getAvatar());
                childCommentRspVO.setNickname(findUserByIdRspDTO.getNickName());
            }

            Long replyCommentId = childCommentDO.getReplyCommentId();
            Long parentId = childCommentDO.getParentId();
            if (Objects.nonNull(replyCommentId) && !Objects.equals(replyCommentId, parentId)) {
                Long replyUserId = childCommentDO.getReplyUserId();
                FindUserByIdRspDTO replyUser = userIdAndDTOMap.get(replyUserId);
                childCommentRspVO.setReplyUserId(replyUserId);
                if (Objects.nonNull(replyUser)) {
                    childCommentRspVO.setReplyUserName(replyUser.getNickName());
                }
            }

            if (CollUtil.isNotEmpty(commentUuidAndContentMap)) {
                String contentUuid = childCommentDO.getContentUuid();
                if (StringUtils.isNotBlank(contentUuid)) {
                    childCommentRspVO.setContent(commentUuidAndContentMap.get(contentUuid));
                }
            }

            childCommentRspVOS.add(childCommentRspVO);
            if (isCommentBodyAvailable(childCommentDO, commentUuidAndContentMap)) {
                cacheableChildDetails.add(childCommentRspVO);
            }
        }

        if (CollUtil.isNotEmpty(cacheableChildDetails)) {
            threadPoolTaskExecutor.execute(() -> {
                Map<String, String> data = Maps.newHashMap();
                cacheableChildDetails.forEach(commentRspVO -> {
                    String key = RedisKeyConstants.buildCommentDetailKey(commentRspVO.getCommentId());
                    data.put(key, JsonUtils.toJsonString(commentRspVO));
                });
                batchAddCommentDetailJson2Redis(data);
            });
        }
    }

    private void batchAddCommentDetailJson2Redis(Map<String, String> data) {
        redisTemplate.executePipelined((RedisCallback<?>) connection -> {
            for (Map.Entry<String, String> entry : data.entrySet()) {
                String jsonStr = JsonUtils.toJsonString(entry.getValue());
                int randomExpire = 60 * 60 + RandomUtil.randomInt(4 * 60 * 60);
                connection.setEx(
                        redisTemplate.getStringSerializer().serialize(entry.getKey()),
                        randomExpire,
                        redisTemplate.getStringSerializer().serialize(jsonStr));
            }
            return null;
        });
    }

    private void getCommentDataAndSync2Redis(List<CommentDO> oneLevelCommentDOS,
                                             Long noteId,
                                             List<FindCommentItemRspVO> commentRspVOS) {
        List<Long> twoLevelCommentIds = oneLevelCommentDOS.stream()
                .map(CommentDO::getFirstReplyCommentId)
                .filter(firstReplyCommentId -> firstReplyCommentId != 0)
                .toList();

        Map<Long, CommentDO> commentIdAndDOMap = null;
        List<CommentDO> twoLevelCommentDOS = null;
        if (CollUtil.isNotEmpty(twoLevelCommentIds)) {
            twoLevelCommentDOS = commentDOMapper.selectTwoLevelCommentByIds(twoLevelCommentIds);
            commentIdAndDOMap = twoLevelCommentDOS.stream()
                    .collect(Collectors.toMap(CommentDO::getId, commentDO -> commentDO));
        }

        List<FindCommentContentReqDTO> findCommentContentReqDTOS = Lists.newArrayList();
        List<Long> userIds = Lists.newArrayList();
        List<CommentDO> allCommentDOS = Lists.newArrayList();
        CollUtil.addAll(allCommentDOS, oneLevelCommentDOS);
        CollUtil.addAll(allCommentDOS, twoLevelCommentDOS);

        allCommentDOS.forEach(commentDO -> {
            if (!Boolean.TRUE.equals(commentDO.getIsContentEmpty())) {
                findCommentContentReqDTOS.add(FindCommentContentReqDTO.builder()
                        .contentId(commentDO.getContentUuid())
                        .yearMonth(DateConstants.DATE_FORMAT_Y_M.format(commentDO.getCreateTime()))
                        .build());
            }
            userIds.add(commentDO.getUserId());
        });

        List<FindCommentContentRspDTO> findCommentContentRspDTOS =
                keyValueRpcService.batchFindCommentContent(noteId, findCommentContentReqDTOS);
        Map<String, String> commentUuidAndContentMap = null;
        if (CollUtil.isNotEmpty(findCommentContentRspDTOS)) {
            commentUuidAndContentMap = findCommentContentRspDTOS.stream()
                    .collect(Collectors.toMap(FindCommentContentRspDTO::getContentId,
                            FindCommentContentRspDTO::getContent));
        }

        List<FindUserByIdRspDTO> findUserByIdRspDTOS = userRpcService.findByIds(userIds);
        Map<Long, FindUserByIdRspDTO> userIdAndDTOMap = toUserMap(findUserByIdRspDTOS);

        List<FindCommentItemRspVO> cacheableRootDetails = Lists.newArrayList();
        for (CommentDO commentDO : oneLevelCommentDOS) {
            Long userId = commentDO.getUserId();
            FindCommentItemRspVO oneLevelCommentRspVO = FindCommentItemRspVO.builder()
                    .userId(userId)
                    .commentId(commentDO.getId())
                    .imageUrl(commentDO.getImageUrl())
                    .createTime(DateUtils.formatRelativeTime(commentDO.getCreateTime()))
                    .likeTotal(commentDO.getLikeTotal())
                    .childCommentTotal(commentDO.getChildCommentTotal())
                    .heat(commentDO.getHeat())
                    .build();

            setUserInfo(userIdAndDTOMap, userId, oneLevelCommentRspVO);
            setCommentContent(commentUuidAndContentMap, commentDO, oneLevelCommentRspVO);

            Long firstReplyCommentId = commentDO.getFirstReplyCommentId();
            if (CollUtil.isNotEmpty(commentIdAndDOMap)) {
                CommentDO firstReplyCommentDO = commentIdAndDOMap.get(firstReplyCommentId);
                if (Objects.nonNull(firstReplyCommentDO)) {
                    Long firstReplyCommentUserId = firstReplyCommentDO.getUserId();
                    FindCommentItemRspVO firstReplyCommentRspVO = FindCommentItemRspVO.builder()
                            .userId(firstReplyCommentDO.getUserId())
                            .commentId(firstReplyCommentDO.getId())
                            .imageUrl(firstReplyCommentDO.getImageUrl())
                            .createTime(DateUtils.formatRelativeTime(firstReplyCommentDO.getCreateTime()))
                            .likeTotal(firstReplyCommentDO.getLikeTotal())
                            .heat(firstReplyCommentDO.getHeat())
                            .build();

                    setUserInfo(userIdAndDTOMap, firstReplyCommentUserId, firstReplyCommentRspVO);
                    oneLevelCommentRspVO.setFirstReplyComment(firstReplyCommentRspVO);
                    setCommentContent(commentUuidAndContentMap, firstReplyCommentDO, firstReplyCommentRspVO);
                }
            }
            commentRspVOS.add(oneLevelCommentRspVO);

            boolean firstReplyIsComplete = Objects.isNull(firstReplyCommentId) || firstReplyCommentId == 0L;
            if (!firstReplyIsComplete && CollUtil.isNotEmpty(commentIdAndDOMap)) {
                CommentDO firstReplyDO = commentIdAndDOMap.get(firstReplyCommentId);
                firstReplyIsComplete = Objects.nonNull(firstReplyDO)
                        && isCommentBodyAvailable(firstReplyDO, commentUuidAndContentMap);
            }
            if (firstReplyIsComplete && isCommentBodyAvailable(commentDO, commentUuidAndContentMap)) {
                cacheableRootDetails.add(oneLevelCommentRspVO);
            }
        }

        if (CollUtil.isNotEmpty(cacheableRootDetails)) {
            threadPoolTaskExecutor.execute(() -> {
                Map<String, String> data = Maps.newHashMap();
                cacheableRootDetails.forEach(commentRspVO -> {
                    String key = RedisKeyConstants.buildCommentDetailKey(commentRspVO.getCommentId());
                    data.put(key, JsonUtils.toJsonString(commentRspVO));
                });
                batchAddCommentDetailJson2Redis(data);
            });
            syncCommentDetail2LocalCache(cacheableRootDetails);
        }
    }

    /**
     * 同步评论详情到本地缓存中
     */
    private void syncCommentDetail2LocalCache(List<FindCommentItemRspVO> commentRspVOS) {
        threadPoolTaskExecutor.execute(() -> {
            Map<Long, String> localCacheData = Maps.newHashMap();
            commentRspVOS.forEach(commentRspVO ->
                    localCacheData.put(commentRspVO.getCommentId(), JsonUtils.toJsonString(commentRspVO)));
            LOCAL_CACHE.putAll(localCacheData);
        });
    }

    private void syncNoteCommentTotal2Redis(String noteCommentTotalKey, Long dbCount) {
        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                operations.opsForHash().put(noteCommentTotalKey, RedisKeyConstants.FIELD_COMMENT_TOTAL, dbCount);
                long expireTime = 60 * 60 + RandomUtil.randomInt(4 * 60 * 60);
                operations.expire(noteCommentTotalKey, expireTime, TimeUnit.SECONDS);
                return null;
            }
        });
    }

    private void syncHeatComments2Redis(String key, Long noteId) {
        String lockKey = RedisKeyConstants.buildCommentListRebuildLockKey(noteId);
        String lockToken = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofMinutes(3));
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }

        String temporaryKey = key + ":rebuild:" + UUID.randomUUID();

        try {
            String generationKey = RedisKeyConstants.buildCommentListGenerationKey(noteId);
            String readyKey = RedisKeyConstants.buildCommentListReadyKey(noteId);
            Object generationValue = redisTemplate.opsForValue().get(generationKey);
            String expectedGeneration = Objects.isNull(generationValue) ? "0" : generationValue.toString();
            int ttlSeconds = 60 * 60 + RandomUtil.randomInt(4 * 60 * 60);
            List<CommentDO> commentDOS = commentDOMapper.selectHeatComments(noteId);
            if (CollUtil.isNotEmpty(commentDOS)) {
                stringRedisTemplate.executePipelined(new SessionCallback<>() {
                    @Override
                    public Object execute(RedisOperations operations) {
                        ZSetOperations<String, String> zSetOps = stringRedisTemplate.opsForZSet();
                        for (CommentDO commentDO : commentDOS) {
                            zSetOps.add(temporaryKey,
                                    RedisKeyConstants.buildHotCommentMember(commentDO.getId()),
                                    commentDO.getHeat());
                        }
                        stringRedisTemplate.expire(temporaryKey, ttlSeconds, TimeUnit.SECONDS);
                        return null;
                    }
                });
            }

            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptSource(new ResourceScriptSource(
                    new ClassPathResource("/lua/replace_hot_comments.lua")));
            script.setResultType(Long.class);
            HotCommentRedisScriptExecutor.execute(redisTemplate, script,
                    List.of(key, generationKey, readyKey, temporaryKey, lockKey),
                    expectedGeneration, ttlSeconds, lockToken);
        } finally {
            try {
                redisTemplate.delete(temporaryKey);
            } finally {
                DefaultRedisScript<Long> releaseLockScript = new DefaultRedisScript<>();
                releaseLockScript.setScriptSource(new ResourceScriptSource(
                        new ClassPathResource("/lua/release_hot_comment_rebuild_lock.lua")));
                releaseLockScript.setResultType(Long.class);
                HotCommentRedisScriptExecutor.execute(redisTemplate, releaseLockScript,
                        List.of(lockKey), lockToken);
            }
        }
    }

    private void invalidateHotCommentCache(Long noteId) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("/lua/update_hot_comments.lua")));
        script.setResultType(Long.class);
        HotCommentRedisScriptExecutor.execute(redisTemplate, script, List.of(
                RedisKeyConstants.buildCommentListKey(noteId),
                RedisKeyConstants.buildCommentListGenerationKey(noteId),
                RedisKeyConstants.buildCommentListReadyKey(noteId)), "INVALIDATE");
    }

    @SuppressWarnings("unchecked")
    private List<String> readHotCommentPage(String zsetKey, Long noteId, long offset, long pageSize) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("/lua/read_hot_comment_page.lua")));
        script.setResultType(List.class);
        List<String> commentIds = stringRedisTemplate.execute(script, List.of(zsetKey,
                RedisKeyConstants.buildCommentListGenerationKey(noteId),
                RedisKeyConstants.buildCommentListReadyKey(noteId)),
                Long.toString(offset), Long.toString(pageSize));
        if (Objects.isNull(commentIds)) {
            // Spring Data Redis may wrap Lua's nil reply as a singleton list containing null.
            return null;
        }
        if (commentIds.isEmpty()) {
            // An empty ZREVRANGE result is a valid cached page, not a cache miss.
            return List.of();
        }
        if (commentIds.stream().allMatch(Objects::isNull)) {
            return null;
        }

        List<String> normalizedCommentIds = Lists.newArrayListWithCapacity(commentIds.size());
        for (String commentId : commentIds) {
            if (Objects.isNull(commentId)) {
                continue;
            }
            // Older rebuilds used the JSON value serializer, which stored each ZSET member with quotes.
            if (commentId.length() == 21 && commentId.startsWith("\"") && commentId.endsWith("\"")) {
                commentId = commentId.substring(1, commentId.length() - 1);
            }
            if (!commentId.matches("\\d{19}")) {
                return null;
            }
            normalizedCommentIds.add(commentId);
        }
        return CollUtil.isEmpty(normalizedCommentIds) ? null : normalizedCommentIds;
    }

    private static boolean isCommentBodyAvailable(CommentDO commentDO,
                                                  Map<String, String> commentUuidAndContentMap) {
        if (Boolean.TRUE.equals(commentDO.getIsContentEmpty())) {
            return true;
        }
        String contentUuid = commentDO.getContentUuid();
        return StringUtils.isNotBlank(contentUuid)
                && CollUtil.isNotEmpty(commentUuidAndContentMap)
                && StringUtils.isNotBlank(commentUuidAndContentMap.get(contentUuid));
    }

    private static void setCommentContent(Map<String, String> commentUuidAndContentMap,
                                          CommentDO commentDO,
                                          FindCommentItemRspVO commentRspVO) {
        if (CollUtil.isNotEmpty(commentUuidAndContentMap)) {
            String contentUuid = commentDO.getContentUuid();
            if (StringUtils.isNotBlank(contentUuid)) {
                commentRspVO.setContent(commentUuidAndContentMap.get(contentUuid));
            }
        }
    }

    private static void setUserInfo(Map<Long, FindUserByIdRspDTO> userIdAndDTOMap,
                                    Long userId,
                                    FindCommentItemRspVO commentRspVO) {
        if (Objects.isNull(userIdAndDTOMap)) {
            return;
        }
        FindUserByIdRspDTO userDTO = userIdAndDTOMap.get(userId);
        if (Objects.nonNull(userDTO)) {
            commentRspVO.setAvatar(userDTO.getAvatar());
            commentRspVO.setNickname(userDTO.getNickName());
        }
    }

    private static Map<Long, FindUserByIdRspDTO> toUserMap(List<FindUserByIdRspDTO> users) {
        Map<Long, FindUserByIdRspDTO> userMap = Maps.newHashMap();
        if (CollUtil.isNotEmpty(users)) {
            users.stream()
                    .filter(Objects::nonNull)
                    .filter(user -> Objects.nonNull(user.getId()))
                    .forEach(user -> userMap.putIfAbsent(user.getId(), user));
        }
        return userMap;
    }
}
