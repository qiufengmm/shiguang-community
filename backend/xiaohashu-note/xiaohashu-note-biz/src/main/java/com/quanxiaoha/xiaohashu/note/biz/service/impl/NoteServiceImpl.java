package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.DateUtils;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.framework.common.util.NumberUtils;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.note.biz.convert.NoteConvert;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteCollectionDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteLikeDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteCollectionDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.TopicDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.enums.LikeUnlikeNoteTypeEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteLikeLuaResultEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteCollectLuaResultEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.CollectUnCollectNoteTypeEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteStatusEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteTypeEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteVisibleEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.DeleteNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.CollectNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UnCollectNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.CollectUnCollectNoteMqDTO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteDetailReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteDetailRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.NoteDetailCacheEntry;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteIsLikedAndCollectedReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindNoteIsLikedAndCollectedRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindPublishedNoteListReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindPublishedNoteListRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.NoteItemRspVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.LikeNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.LikeUnlikeNoteMqDTO;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.PublishNoteDTO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.PublishNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.TopNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UpdateNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UpdateNoteVisibleOnlyMeReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UnlikeNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.rpc.CountRpcService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.DistributedIdGeneratorRpcService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.UserRpcService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteIdSetCache;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteOperatePersistenceService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteOperateOutboxService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteContentSagaService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteService;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 笔记业务
 **/
@Service
@Slf4j
public class NoteServiceImpl implements NoteService {

    @Resource
    private NoteDOMapper noteDOMapper;
    @Resource
    private NoteCollectionDOMapper noteCollectionDOMapper;
    @Resource
    private NoteLikeDOMapper noteLikeDOMapper;
    @Resource
    private TopicDOMapper topicDOMapper;
    @Resource
    private DistributedIdGeneratorRpcService distributedIdGeneratorRpcService;
    @Resource
    private KeyValueRpcService keyValueRpcService;
    @Resource
    private UserRpcService userRpcService;
    @Resource
    private CountRpcService countRpcService;
    @Resource(name = "taskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    @Resource
    private RedisTemplate<String, String> redisTemplate;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private NoteIdSetCache noteIdSetCache;
    @Resource
    private NoteOperatePersistenceService noteOperatePersistenceService;

    @Resource
    private NoteOperateOutboxService noteOperateOutboxService;

    @Resource
    private NoteContentSagaService noteContentSagaService;

    @Override
    public Response<FindPublishedNoteListRspVO> findPublishedNoteList(FindPublishedNoteListReqVO request) {
        Long userId = request.getUserId();
        Long cursor = request.getCursor();

        String publishedNoteListRedisKey = RedisKeyConstants.buildPublishedNoteListKey(userId);
        if (Objects.isNull(cursor)) {
            String publishedNoteListJson = redisTemplate.opsForValue().get(publishedNoteListRedisKey);
            if (StringUtils.isNotBlank(publishedNoteListJson)) {
                try {
                    log.info("## 已发布笔记列表命中了 Redis 缓存...");
                    List<NoteItemRspVO> cachedNotes = JsonUtils.parseList(publishedNoteListJson, NoteItemRspVO.class);
                    List<NoteItemRspVO> sortedNotes = cachedNotes.stream()
                            .sorted(Comparator.comparing(NoteItemRspVO::getNoteId).reversed())
                            .toList();
                    sortedNotes.forEach(note -> note.setIsLiked(false));

                    // 博主本人查询时刷新点赞量；所有登录用户都重新计算点赞状态
                    getAndSetLatestLikeTotalIfAuthor(userId, sortedNotes);
                    batchGetAndSetNoteIsLiked(sortedNotes);

                    Optional<Long> earliestNoteId = cachedNotes.stream()
                            .map(NoteItemRspVO::getNoteId)
                            .min(Long::compareTo);
                    FindPublishedNoteListRspVO cachedResponse = FindPublishedNoteListRspVO.builder()
                            .notes(sortedNotes)
                            .nextCursor(earliestNoteId.orElse(null))
                            .build();
                    return Response.success(cachedResponse);
                } catch (Exception e) {
                    log.error("## 已发布笔记列表缓存解析失败", e);
                }
            }
        }

        List<NoteDO> noteDOS = noteDOMapper.selectPublishedNoteListByUserIdAndCursor(userId, cursor);

        FindPublishedNoteListRspVO response = null;
        if (CollUtil.isNotEmpty(noteDOS)) {
            List<NoteItemRspVO> noteVOS = noteDOS.stream()
                    .map(noteDO -> {
                        String cover = StringUtils.isNotBlank(noteDO.getImgUris())
                                ? StringUtils.split(noteDO.getImgUris(), ",")[0]
                                : null;
                        return NoteItemRspVO.builder()
                                .noteId(noteDO.getId())
                                .type(noteDO.getType())
                                .creatorId(noteDO.getCreatorId())
                                .cover(cover)
                                .videoUri(noteDO.getVideoUri())
                                .title(noteDO.getTitle())
                                .isLiked(false)
                                .build();
                    })
                    .toList();

            CompletableFuture<FindUserByIdRspDTO> userFuture = CompletableFuture
                    .supplyAsync(() -> {
                        Optional<Long> creatorId = noteDOS.stream().map(NoteDO::getCreatorId).findAny();
                        return userRpcService.findById(creatorId.get());
                    }, threadPoolTaskExecutor);

            CompletableFuture<List<FindNoteCountsByIdRspDTO>> noteCountFuture = CompletableFuture
                    .supplyAsync(() -> {
                        List<Long> noteIds = noteDOS.stream().map(NoteDO::getId).toList();
                        return countRpcService.findByNoteIds(noteIds);
                    }, threadPoolTaskExecutor);

            CompletableFuture.allOf(userFuture, noteCountFuture).join();

            try {
                FindUserByIdRspDTO user = userFuture.get();
                List<FindNoteCountsByIdRspDTO> noteCounts = noteCountFuture.get();

                if (Objects.nonNull(user)) {
                    noteVOS.forEach(note -> {
                        note.setAvatar(user.getAvatar());
                        note.setNickname(user.getNickName());
                    });
                }

                setVOListLikeTotal(noteVOS, noteCounts);
            } catch (Exception e) {
                log.error("## 并发调用错误: ", e);
            }

            // 先缓存未按当前访客个性化的列表，再填充点赞状态，避免共享缓存串用户状态
            if (Objects.isNull(cursor)) {
                syncFirstPagePublishedNoteList2Redis(noteVOS, publishedNoteListRedisKey);
            }
            batchGetAndSetNoteIsLiked(noteVOS);

            Optional<Long> earliestNoteId = noteDOS.stream().map(NoteDO::getId).min(Long::compareTo);
            response = FindPublishedNoteListRspVO.builder()
                    .notes(noteVOS)
                    .nextCursor(earliestNoteId.orElse(null))
                    .build();
        }

        return Response.success(response);
    }

    /**
     * 异步同步第一页已发布笔记列表到 Redis
     */
    private void syncFirstPagePublishedNoteList2Redis(List<NoteItemRspVO> noteVOS, String redisKey) {
        if (CollUtil.isEmpty(noteVOS)) {
            return;
        }

        String noteListJson = JsonUtils.toJsonString(noteVOS);
        threadPoolTaskExecutor.submit(() -> {
            long expireSeconds = 60 * 30 + RandomUtil.randomInt(60 * 30);
            redisTemplate.opsForValue().set(redisKey, noteListJson, expireSeconds, TimeUnit.SECONDS);
        });
    }

    /**
     * 博主本人查看主页时，使用计数服务刷新最新点赞量
     */
    private void getAndSetLatestLikeTotalIfAuthor(Long userId, List<NoteItemRspVO> noteItemRspVOS) {
        Long loginUserId = LoginUserContextHolder.getUserId();
        if (Objects.nonNull(loginUserId) && Objects.equals(loginUserId, userId)
                && CollUtil.isNotEmpty(noteItemRspVOS)) {
            List<Long> noteIds = noteItemRspVOS.stream().map(NoteItemRspVO::getNoteId).toList();
            List<FindNoteCountsByIdRspDTO> noteCounts = countRpcService.findByNoteIds(noteIds);
            setVOListLikeTotal(noteItemRspVOS, noteCounts);
        }
    }

    /**
     * 设置笔记卡片列表中的点赞量
     */
    private static void setVOListLikeTotal(List<NoteItemRspVO> noteItemRspVOS,
                                           List<FindNoteCountsByIdRspDTO> noteCounts) {
        if (CollUtil.isEmpty(noteCounts)) {
            return;
        }

        Map<Long, FindNoteCountsByIdRspDTO> noteIdAndDTOMap = noteCounts.stream()
                .collect(Collectors.toMap(FindNoteCountsByIdRspDTO::getNoteId, dto -> dto));
        noteItemRspVOS.forEach(note -> {
            FindNoteCountsByIdRspDTO noteCount = noteIdAndDTOMap.get(note.getNoteId());
            note.setLikeTotal(Objects.nonNull(noteCount) && Objects.nonNull(noteCount.getLikeTotal())
                    ? NumberUtils.formatNumberString(noteCount.getLikeTotal()) : "0");
        });
    }

    /**
     * 批量查询当前登录用户对笔记列表的点赞状态
     */
    private void batchGetAndSetNoteIsLiked(List<NoteItemRspVO> noteItemRspVOS) {
        Long loginUserId = LoginUserContextHolder.getUserId();
        if (Objects.isNull(loginUserId) || CollUtil.isEmpty(noteItemRspVOS)) {
            return;
        }

        List<Long> noteIds = noteItemRspVOS.stream().map(NoteItemRspVO::getNoteId).toList();
        String setKey = RedisKeyConstants.buildUserNoteLikeSetKey(loginUserId);
        String readyKey = RedisKeyConstants.buildUserNoteLikeSetReadyKey(loginUserId);
        List<Long> results = noteIdSetCache.containsAll(setKey, readyKey, noteIds);
        if (results.size() == 1 && Objects.equals(results.get(0), -1L)) {
            List<NoteLikeDO> noteLikeDOS = noteLikeDOMapper.selectByUserIdAndNoteIds(loginUserId, noteIds);
            Set<Long> likedNoteIds = CollUtil.isEmpty(noteLikeDOS) ? Collections.emptySet()
                    : noteLikeDOS.stream().map(NoteLikeDO::getNoteId).collect(Collectors.toSet());
            noteItemRspVOS.forEach(note -> {
                note.setIsLiked(likedNoteIds.contains(note.getNoteId()));
            });
            threadPoolTaskExecutor.submit(() -> rebuildLikeSetFromDatabase(loginUserId));
            return;
        }

        Map<Long, Boolean> likedMap = Maps.newHashMapWithExpectedSize(noteIds.size());
        for (int i = 0; i < noteIds.size(); i++) {
            likedMap.put(noteIds.get(i), i < results.size() && Objects.equals(results.get(i), 1L));
        }
        noteItemRspVOS.forEach(note -> note.setIsLiked(likedMap.getOrDefault(note.getNoteId(), false)));
    }

    /**
     * 笔记详情本地缓存
     */
    private static final Cache<Long, NoteDetailCacheEntry> LOCAL_CACHE = Caffeine.newBuilder()
            .initialCapacity(10000) // 设置初始容量为 10000 个条目
            .maximumSize(10000) // 设置缓存的最大容量为 10000 个条目
            .expireAfterWrite(1, TimeUnit.HOURS) // 设置缓存条目在写入后 1 小时过期
            .build();

    /**
     * 笔记发布
     *
     * @param publishNoteReqVO
     * @return
     */
    @Override
    public Response<?> publishNote(PublishNoteReqVO publishNoteReqVO) {
        // 笔记类型
        Integer type = publishNoteReqVO.getType();

        // 获取对应类型的枚举
        NoteTypeEnum noteTypeEnum = NoteTypeEnum.valueOf(type);

        // 若非图文、视频，抛出业务业务异常
        if (Objects.isNull(noteTypeEnum)) {
            throw new BizException(ResponseCodeEnum.NOTE_TYPE_ERROR);
        }

        String imgUris = null;
        // 笔记内容是否为空，默认值为 true，即空
        Boolean isContentEmpty = true;
        String videoUri = null;
        switch (noteTypeEnum) {
            case IMAGE_TEXT: // 图文笔记
                List<String> imgUriList = publishNoteReqVO.getImgUris();
                // 校验图片是否为空
                Preconditions.checkArgument(CollUtil.isNotEmpty(imgUriList), "笔记图片不能为空");
                // 校验图片数量
                Preconditions.checkArgument(imgUriList.size() <= 8, "笔记图片不能多于 8 张");
                // 将图片链接拼接，以逗号分隔
                imgUris = StringUtils.join(imgUriList, ",");

                break;
            case VIDEO: // 视频笔记
                videoUri = publishNoteReqVO.getVideoUri();
                // 校验视频链接是否为空
                Preconditions.checkArgument(StringUtils.isNotBlank(videoUri), "笔记视频不能为空");
                break;
            default:
                break;
        }

        // RPC: 调用分布式 ID 生成服务，生成笔记 ID
        String snowflakeIdId = distributedIdGeneratorRpcService.getSnowflakeId();
        // 笔记内容 UUID
        String contentUuid = null;

        // 笔记内容
        String content = publishNoteReqVO.getContent();

        // 若用户填写了笔记内容
        if (StringUtils.isNotBlank(content)) {
            // 内容是否为空，置为 false，即不为空
            isContentEmpty = false;
            // 生成笔记内容 UUID
            contentUuid = UUID.randomUUID().toString();
        }

        // 话题
        Long topicId = publishNoteReqVO.getTopicId();
        String topicName = null;
        if (Objects.nonNull(topicId)) {
            // 获取话题名称
            topicName = topicDOMapper.selectNameByPrimaryKey(topicId);
        }

        // 发布者用户 ID
        Long creatorId = LoginUserContextHolder.getUserId();

        // 构建笔记 DO 对象
        NoteDO noteDO = NoteDO.builder()
                .id(Long.valueOf(snowflakeIdId))
                .isContentEmpty(isContentEmpty)
                .creatorId(creatorId)
                .imgUris(imgUris)
                .title(publishNoteReqVO.getTitle())
                .topicId(publishNoteReqVO.getTopicId())
                .topicName(topicName)
                .type(type)
                .visible(NoteVisibleEnum.PUBLIC.getCode())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .status(NoteStatusEnum.NORMAL.getCode())
                .isTop(Boolean.FALSE)
                .videoUri(videoUri)
                .contentUuid(contentUuid)
                .build();

        // 笔记正文为空时，沿用原有发布流程；有正文时通过事务消息处理
        if (StringUtils.isBlank(content)) {
            processPublishContentEmptyNote(creatorId, noteDO);
            return Response.success();
        }

        // DO 转 DTO，并填充笔记正文
        PublishNoteDTO publishNoteDTO = NoteConvert.INSTANCE.convertDO2DTO(noteDO);
        publishNoteDTO.setContent(content);

        // 构建事务消息
        Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(publishNoteDTO)).build();
        TransactionSendResult transactionSendResult = rocketMQTemplate.sendMessageInTransaction(
                MQConstants.TOPIC_PUBLISH_NOTE_TRANSACTION, message, null);
        LocalTransactionState transactionState = Objects.isNull(transactionSendResult)
                ? LocalTransactionState.UNKNOW
                : transactionSendResult.getLocalTransactionState();
        log.info("## 事务消息发送结果: {}", transactionState);

        if (transactionState != LocalTransactionState.COMMIT_MESSAGE) {
            throw new BizException(ResponseCodeEnum.NOTE_PUBLISH_FAIL);
        }
        return Response.success();
    }

    private void processPublishContentEmptyNote(Long creatorId, NoteDO noteDO) {
        // 删除个人主页 - 已发布笔记列表缓存
        // TODO: 大 V 应直接更新缓存，普通用户可直接删除
        String publishedNoteListRedisKey = RedisKeyConstants.buildPublishedNoteListKey(creatorId);
        redisTemplate.delete(publishedNoteListRedisKey);

        noteOperatePersistenceService.insertPublishedNote(noteDO);

        // 延迟双删：再次删除个人主页笔记列表缓存
        sendDelayDeleteRedisPublishedNoteListCacheMQ(creatorId);

    }

    private void sendDelayDeleteRedisPublishedNoteListCacheMQ(Long userId) {
        Message<String> message = MessageBuilder.withPayload(String.valueOf(userId)).build();
        rocketMQTemplate.asyncSend(MQConstants.TOPIC_DELAY_DELETE_PUBLISHED_NOTE_LIST_REDIS_CACHE, message,
                new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        log.info("## 延时删除 Redis 已发布笔记列表缓存消息发送成功...");
                    }

                    @Override
                    public void onException(Throwable e) {
                        log.error("## 延时删除 Redis 已发布笔记列表缓存消息发送失败...", e);
                    }
                },
                3000,
                1);
    }

    /**
     * 笔记更新
     *
     * @param updateNoteReqVO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> updateNote(UpdateNoteReqVO updateNoteReqVO) {
        // 笔记 ID
        Long noteId = updateNoteReqVO.getId();
        // 笔记类型
        Integer type = updateNoteReqVO.getType();

        // 获取对应类型的枚举
        NoteTypeEnum noteTypeEnum = NoteTypeEnum.valueOf(type);

        // 若非图文、视频，抛出业务业务异常
        if (Objects.isNull(noteTypeEnum)) {
            throw new BizException(ResponseCodeEnum.NOTE_TYPE_ERROR);
        }

        String imgUris = null;
        String videoUri = null;
        switch (noteTypeEnum) {
            case IMAGE_TEXT: // 图文笔记
                List<String> imgUriList = updateNoteReqVO.getImgUris();
                // 校验图片是否为空
                Preconditions.checkArgument(CollUtil.isNotEmpty(imgUriList), "笔记图片不能为空");
                // 校验图片数量
                Preconditions.checkArgument(imgUriList.size() <= 8, "笔记图片不能多于 8 张");

                imgUris = StringUtils.join(imgUriList, ",");
                break;
            case VIDEO: // 视频笔记
                videoUri = updateNoteReqVO.getVideoUri();
                // 校验视频链接是否为空
                Preconditions.checkArgument(StringUtils.isNotBlank(videoUri), "笔记视频不能为空");
                break;
            default:
                break;
        }

        // 当前登录用户 ID
        Long currUserId = LoginUserContextHolder.getUserId();
        NoteDO selectNoteDO = noteDOMapper.selectByPrimaryKey(noteId);

        // 笔记不存在
        if (Objects.isNull(selectNoteDO)) {
            throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
        }

        // 判断权限：非笔记发布者不允许更新笔记
        if (!Objects.equals(currUserId, selectNoteDO.getCreatorId())) {
            throw new BizException(ResponseCodeEnum.NOTE_CANT_OPERATE);
        }

        // 话题
        Long topicId = updateNoteReqVO.getTopicId();
        String topicName = null;
        if (Objects.nonNull(topicId)) {
            topicName = topicDOMapper.selectNameByPrimaryKey(topicId);

            // 判断一下提交的话题, 是否是真实存在的
            if (StringUtils.isBlank(topicName)) throw new BizException(ResponseCodeEnum.TOPIC_NOT_FOUND);
        }


        // 为每次正文编辑生成新 UUID，避免一个尚未提交的 Cassandra 写覆盖当前有效正文。
        String content = updateNoteReqVO.getContent();
        String oldContentUuid = selectNoteDO.getContentUuid();
        boolean contentEmpty = StringUtils.isBlank(content);
        String targetContentUuid = contentEmpty ? null : UUID.randomUUID().toString();
        String sagaOwner = UUID.randomUUID().toString();
        com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO contentSaga = null;
        if (!contentEmpty) {
            contentSaga = noteContentSagaService.prepareUpdate(noteId, targetContentUuid, sagaOwner);
        } else if (StringUtils.isNotBlank(oldContentUuid)) {
            contentSaga = noteContentSagaService.prepareClear(noteId, oldContentUuid, sagaOwner);
        }

        if (contentSaga != null && (!NoteContentSagaService.STATUS_PENDING.equals(contentSaga.getStatus())
                || !sagaOwner.equals(contentSaga.getLeaseOwner()))) {
            throw new BizException(ResponseCodeEnum.NOTE_UPDATE_FAIL);
        }

        if (!contentEmpty) {
            boolean saved;
            try {
                saved = keyValueRpcService.saveNoteContent(targetContentUuid, content);
            } catch (Exception e) {
                if (contentSaga != null) {
                    // A timeout can mean Cassandra committed the write but the response was lost. Use an
                    // independent cleanup record because recovery may already have taken over this lease.
                    noteContentSagaService.requeueLateWriteCleanup(contentSaga, targetContentUuid, e);
                }
                throw new BizException(ResponseCodeEnum.NOTE_UPDATE_FAIL);
            }
            if (!saved) {
                if (contentSaga != null) {
                    noteContentSagaService.requeueLateWriteCleanup(contentSaga, targetContentUuid,
                            new IllegalStateException("KV service did not confirm updated note content"));
                }
                throw new BizException(ResponseCodeEnum.NOTE_UPDATE_FAIL);
            }
        }

        // Fence the metadata commit against recovery taking over an expired operation while Cassandra is writing.
        if (contentSaga != null && !noteContentSagaService.lockLeaseForCommit(contentSaga)) {
            if (!contentEmpty) {
                noteContentSagaService.requeueLateWriteCleanup(contentSaga, targetContentUuid,
                        new IllegalStateException("note content saga lease expired before pointer commit"));
            }
            throw new BizException(ResponseCodeEnum.NOTE_UPDATE_FAIL);
        }

        // 更新笔记元数据表 t_note。正文 UUID 指针与编辑的其他元数据一并提交。
        NoteDO noteDO = NoteDO.builder()
                .id(noteId)
                .isContentEmpty(contentEmpty)
                .contentUuid(targetContentUuid)
                .imgUris(imgUris)
                .title(updateNoteReqVO.getTitle())
                .topicId(updateNoteReqVO.getTopicId())
                .topicName(topicName)
                .type(type)
                .updateTime(LocalDateTime.now())
                .videoUri(videoUri)
                .build();

        if (noteDOMapper.updateContentWithExpectedUuid(noteDO, oldContentUuid) != 1) {
            if (contentSaga != null && !contentEmpty) {
                noteContentSagaService.requeueLateWriteCleanup(contentSaga, targetContentUuid,
                        new IllegalStateException("note content pointer changed during concurrent update"));
            }
            throw new BizException(ResponseCodeEnum.NOTE_UPDATE_FAIL);
        }
        if (contentSaga != null) {
            // 新正文先成为 MySQL 指向的版本，再由恢复任务清理旧 UUID；清空时同样在 SQL 提交后删旧正文。
            noteContentSagaService.markOldContentDeletePending(contentSaga, oldContentUuid);
        }

        enqueueNoteCacheInvalidation(currUserId, noteId);

        // 缓存失效必须在事务提交后执行，避免其他请求在提交前把旧数据库内容重新写入缓存。
        runAfterCommit(() -> invalidateNoteCaches(noteId, currUserId));

        return Response.success();
    }

    /**
     * 笔记详情
     *
     * @param findNoteDetailReqVO
     * @return
     */
    @Override
    public Response<FindNoteDetailRspVO> findNoteDetail(FindNoteDetailReqVO findNoteDetailReqVO) {
        Long noteId = findNoteDetailReqVO.getId();
        Long userId = LoginUserContextHolder.getUserId();

        NoteDetailCacheEntry localEntry = LOCAL_CACHE.getIfPresent(noteId);
        if (Objects.nonNull(localEntry)) {
            if (localEntry.canServe()) {
                checkNoteVisibleFromVO(userId, localEntry.getDetail());
                return Response.success(localEntry.getDetail());
            }
            LOCAL_CACHE.invalidate(noteId);
        }

        String noteDetailRedisKey = RedisKeyConstants.buildNoteDetailKey(noteId);
        String noteDetailJson = redisTemplate.opsForValue().get(noteDetailRedisKey);
        if (StringUtils.isNotBlank(noteDetailJson)) {
            NoteDetailCacheEntry redisEntry = parseNoteDetailCacheEntry(noteDetailJson);
            if (Objects.nonNull(redisEntry) && redisEntry.canServe()) {
                LOCAL_CACHE.put(noteId, redisEntry);
                checkNoteVisibleFromVO(userId, redisEntry.getDetail());
                return Response.success(redisEntry.getDetail());
            }
        }

        NoteDO noteDO = noteDOMapper.selectByPrimaryKey(noteId);
        if (Objects.isNull(noteDO)) {
            threadPoolTaskExecutor.execute(() -> {
                long expireSeconds = 60 + RandomUtil.randomInt(60);
                redisTemplate.opsForValue().set(noteDetailRedisKey, "null", expireSeconds, TimeUnit.SECONDS);
            });
            throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
        }

        checkNoteVisible(noteDO.getVisible(), userId, noteDO.getCreatorId());
        Long creatorId = noteDO.getCreatorId();
        CompletableFuture<FindUserByIdRspDTO> userResultFuture = CompletableFuture
                .supplyAsync(() -> userRpcService.findById(creatorId), threadPoolTaskExecutor)
                .exceptionally(error -> {
                    log.warn("## 笔记作者资料查询失败，noteId: {}, creatorId: {}", noteId, creatorId, error);
                    return null;
                });
        boolean contentRequired = Boolean.FALSE.equals(noteDO.getIsContentEmpty());
        CompletableFuture<String> contentResultFuture = contentRequired
                ? CompletableFuture.supplyAsync(() -> {
                    try {
                        return keyValueRpcService.findNoteContent(noteDO.getContentUuid());
                    } catch (Exception e) {
                        log.warn("## 笔记正文读取失败，noteId: {}", noteId, e);
                        return null;
                    }
                }, threadPoolTaskExecutor)
                : CompletableFuture.completedFuture(null);

        CompletableFuture.allOf(userResultFuture, contentResultFuture).join();
        FindUserByIdRspDTO user = userResultFuture.join();
        String content = contentResultFuture.join();
        if (contentRequired && StringUtils.isBlank(content)) {
            throw new BizException(ResponseCodeEnum.NOTE_CONTENT_NOT_READY);
        }

        List<String> imgUris = null;
        if (Objects.equals(noteDO.getType(), NoteTypeEnum.IMAGE_TEXT.getCode())
                && StringUtils.isNotBlank(noteDO.getImgUris())) {
            imgUris = List.of(noteDO.getImgUris().split(","));
        }

        FindNoteDetailRspVO detail = FindNoteDetailRspVO.builder()
                .id(noteDO.getId())
                .type(noteDO.getType())
                .title(noteDO.getTitle())
                .content(content)
                .imgUris(imgUris)
                .topicId(noteDO.getTopicId())
                .topicName(noteDO.getTopicName())
                .creatorId(noteDO.getCreatorId())
                .creatorName(Objects.nonNull(user) ? user.getNickName() : null)
                .avatar(Objects.nonNull(user) ? user.getAvatar() : null)
                .videoUri(noteDO.getVideoUri())
                .updateTime(noteDO.getUpdateTime())
                .visible(noteDO.getVisible())
                .build();
        NoteDetailCacheEntry cacheEntry = NoteDetailCacheEntry.builder()
                .contentRequired(contentRequired)
                .detail(detail)
                .build();

        // 作者资料服务失败时仍可返回笔记，但不缓存不完整的作者信息。
        if (Objects.nonNull(user)) {
            LOCAL_CACHE.put(noteId, cacheEntry);
            threadPoolTaskExecutor.submit(() -> {
                String cacheJson = JsonUtils.toJsonString(cacheEntry);
                long expireSeconds = 60 * 60 * 24 + RandomUtil.randomInt(60 * 60 * 24);
                redisTemplate.opsForValue().set(noteDetailRedisKey, cacheJson, expireSeconds, TimeUnit.SECONDS);
            });
        }

        return Response.success(detail);
    }

    private NoteDetailCacheEntry parseNoteDetailCacheEntry(String json) {
        try {
            return JsonUtils.parseObject(json, NoteDetailCacheEntry.class);
        } catch (Exception e) {
            log.warn("## 笔记详情缓存格式无效，将回源查询", e);
            return null;
        }
    }
    /**
     * 删除本地笔记缓存
     *
     * @param noteId
     */
    @Override
    public void deleteNoteLocalCache(Long noteId) {
        LOCAL_CACHE.invalidate(noteId);
    }

    /**
     * 点赞笔记
     */
    @Override
    public Response<?> likeNote(LikeNoteReqVO likeNoteReqVO) {
        Long noteId = likeNoteReqVO.getId();

        // 1. 校验被点赞的笔记是否存在
        Long creatorId = checkNoteIsExistAndGetCreatorId(noteId);

        // 2. 判断目标笔记是否已经点赞过
        Long userId = LoginUserContextHolder.getUserId();
        String userNoteLikeZSetKey = RedisKeyConstants.buildUserNoteLikeZSetKey(userId);
        if (!changeRelationSet(userId, noteId, true, true)) {
            throw new BizException(ResponseCodeEnum.NOTE_ALREADY_LIKED);
        }

        // 3. 更新用户 ZSet 点赞列表
        LocalDateTime now = LocalDateTime.now();
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("/lua/note_like_check_and_update_zset.lua")));
        script.setResultType(Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(userNoteLikeZSetKey), noteId,
                DateUtils.localDateTime2Timestamp(now));

        // ZSet 不存在时，从数据库初始化最近点赞记录
        if (Objects.equals(result, NoteLikeLuaResultEnum.NOT_EXIST.getCode())) {
            List<NoteLikeDO> noteLikeDOS = noteLikeDOMapper.selectLikedByUserIdAndLimit(userId, 100);

            // 保底 1 天 + 随机秒数
            long expireSeconds = 60 * 60 * 24 + RandomUtil.randomInt(60 * 60 * 24);

            DefaultRedisScript<Long> script2 = new DefaultRedisScript<>();
            script2.setScriptSource(new ResourceScriptSource(
                    new ClassPathResource("/lua/batch_add_note_like_zset_and_expire.lua")));
            script2.setResultType(Long.class);

            if (CollUtil.isNotEmpty(noteLikeDOS)) {
                Object[] luaArgs = buildNoteLikeZSetLuaArgs(noteLikeDOS, expireSeconds);
                redisTemplate.execute(script2, Collections.singletonList(userNoteLikeZSetKey), luaArgs);

                // 将当前点赞的笔记加入已初始化的 ZSet
                redisTemplate.execute(script, Collections.singletonList(userNoteLikeZSetKey), noteId,
                        DateUtils.localDateTime2Timestamp(now));
            } else {
                List<Object> luaArgs = Lists.newArrayList();
                luaArgs.add(DateUtils.localDateTime2Timestamp(LocalDateTime.now()));
                luaArgs.add(noteId);
                luaArgs.add(expireSeconds);
                redisTemplate.execute(script2, Collections.singletonList(userNoteLikeZSetKey), luaArgs.toArray());
            }
        }

        // 4. 发送 MQ，将点赞数据落库
        LikeUnlikeNoteMqDTO likeUnlikeNoteMqDTO = LikeUnlikeNoteMqDTO.builder()
                .userId(userId)
                .noteId(noteId)
                .type(LikeUnlikeNoteTypeEnum.LIKE.getCode())
                .createTime(now)
                .noteCreatorId(creatorId)
                .build();

        Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(likeUnlikeNoteMqDTO))
                .build();
        String destination = MQConstants.TOPIC_LIKE_OR_UNLIKE + ":" + MQConstants.TAG_LIKE;
        String hashKey = String.valueOf(userId);

        sendRelationEvent(destination, message, hashKey, userId, true, userNoteLikeZSetKey);

        return Response.success();
    }

    /**
     * 取消点赞笔记
     */
    @Override
    public Response<?> unlikeNote(UnlikeNoteReqVO unlikeNoteReqVO) {
        Long noteId = unlikeNoteReqVO.getId();

        // 1. 校验笔记是否真实存在
        Long creatorId = checkNoteIsExistAndGetCreatorId(noteId);

        // 2. 校验笔记是否被点赞过
        Long userId = LoginUserContextHolder.getUserId();
        if (!changeRelationSet(userId, noteId, true, false)) {
            throw new BizException(ResponseCodeEnum.NOTE_NOT_LIKED);
        }

        // 3. 从用户点赞 ZSet 中删除笔记 ID
        String userNoteLikeZSetKey = RedisKeyConstants.buildUserNoteLikeZSetKey(userId);
        redisTemplate.opsForZSet().remove(userNoteLikeZSetKey, noteId);

        // 4. 发送 MQ，更新数据库点赞状态
        LikeUnlikeNoteMqDTO likeUnlikeNoteMqDTO = LikeUnlikeNoteMqDTO.builder()
                .userId(userId)
                .noteId(noteId)
                .type(LikeUnlikeNoteTypeEnum.UNLIKE.getCode())
                .createTime(LocalDateTime.now())
                .noteCreatorId(creatorId)
                .build();

        Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(likeUnlikeNoteMqDTO))
                .build();
        String destination = MQConstants.TOPIC_LIKE_OR_UNLIKE + ":" + MQConstants.TAG_UNLIKE;
        String hashKey = String.valueOf(userId);

        sendRelationEvent(destination, message, hashKey, userId, true, userNoteLikeZSetKey);

        return Response.success();
    }

    /**
     * 收藏笔记
     */
    @Override
    public Response<?> collectNote(CollectNoteReqVO collectNoteReqVO) {
        Long noteId = collectNoteReqVO.getId();

        // 1. 校验被收藏的笔记是否存在
        Long creatorId = checkNoteIsExistAndGetCreatorId(noteId);

        // 2. 判断目标笔记是否已经收藏过
        Long userId = LoginUserContextHolder.getUserId();
        String userNoteCollectZSetKey = RedisKeyConstants.buildUserNoteCollectZSetKey(userId);
        if (!changeRelationSet(userId, noteId, false, true)) {
            throw new BizException(ResponseCodeEnum.NOTE_ALREADY_COLLECTED);
        }

        // 3. 更新用户 ZSet 收藏列表
        LocalDateTime now = LocalDateTime.now();
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("/lua/note_collect_check_and_update_zset.lua")));
        script.setResultType(Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(userNoteCollectZSetKey), noteId,
                DateUtils.localDateTime2Timestamp(now));

        if (Objects.equals(result, NoteCollectLuaResultEnum.NOT_EXIST.getCode())) {
            List<NoteCollectionDO> noteCollectionDOS =
                    noteCollectionDOMapper.selectCollectedByUserIdAndLimit(userId, 300);
            long expireSeconds = 60 * 60 * 24 + RandomUtil.randomInt(60 * 60 * 24);

            DefaultRedisScript<Long> script2 = new DefaultRedisScript<>();
            script2.setScriptSource(new ResourceScriptSource(
                    new ClassPathResource("/lua/batch_add_note_collect_zset_and_expire.lua")));
            script2.setResultType(Long.class);

            if (CollUtil.isNotEmpty(noteCollectionDOS)) {
                Object[] luaArgs = buildNoteCollectZSetLuaArgs(noteCollectionDOS, expireSeconds);
                redisTemplate.execute(script2, Collections.singletonList(userNoteCollectZSetKey), luaArgs);
                redisTemplate.execute(script, Collections.singletonList(userNoteCollectZSetKey), noteId,
                        DateUtils.localDateTime2Timestamp(now));
            } else {
                List<Object> luaArgs = Lists.newArrayList();
                luaArgs.add(DateUtils.localDateTime2Timestamp(now));
                luaArgs.add(noteId);
                luaArgs.add(expireSeconds);
                redisTemplate.execute(script2, Collections.singletonList(userNoteCollectZSetKey), luaArgs.toArray());
            }
        }

        // 4. 发送 MQ，将收藏数据落库
        CollectUnCollectNoteMqDTO collectUnCollectNoteMqDTO = CollectUnCollectNoteMqDTO.builder()
                .userId(userId)
                .noteId(noteId)
                .type(CollectUnCollectNoteTypeEnum.COLLECT.getCode())
                .createTime(now)
                .noteCreatorId(creatorId)
                .build();

        Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(collectUnCollectNoteMqDTO))
                .build();
        String destination = MQConstants.TOPIC_COLLECT_OR_UN_COLLECT + ":" + MQConstants.TAG_COLLECT;
        String hashKey = String.valueOf(userId);

        sendRelationEvent(destination, message, hashKey, userId, false, userNoteCollectZSetKey);
        return Response.success();
    }

    /**
     * 取消收藏笔记
     */
    @Override
    public Response<?> unCollectNote(UnCollectNoteReqVO unCollectNoteReqVO) {
        Long noteId = unCollectNoteReqVO.getId();

        // 1. 校验笔记是否真实存在
        Long creatorId = checkNoteIsExistAndGetCreatorId(noteId);

        // 2. 校验笔记是否被收藏过
        Long userId = LoginUserContextHolder.getUserId();
        if (!changeRelationSet(userId, noteId, false, false)) {
            throw new BizException(ResponseCodeEnum.NOTE_NOT_COLLECTED);
        }

        // 3. 删除 ZSet 中已收藏的笔记 ID
        String userNoteCollectZSetKey = RedisKeyConstants.buildUserNoteCollectZSetKey(userId);
        redisTemplate.opsForZSet().remove(userNoteCollectZSetKey, noteId);

        // 4. 发送 MQ，将取消收藏数据落库
        CollectUnCollectNoteMqDTO unCollectNoteMqDTO = CollectUnCollectNoteMqDTO.builder()
                .userId(userId)
                .noteId(noteId)
                .type(CollectUnCollectNoteTypeEnum.UN_COLLECT.getCode())
                .createTime(LocalDateTime.now())
                .noteCreatorId(creatorId)
                .build();

        Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(unCollectNoteMqDTO))
                .build();
        String destination = MQConstants.TOPIC_COLLECT_OR_UN_COLLECT + ":" + MQConstants.TAG_UN_COLLECT;
        String hashKey = String.valueOf(userId);

        sendRelationEvent(destination, message, hashKey, userId, false, userNoteCollectZSetKey);

        return Response.success();
    }

    /**
     * 获取当前用户是否点赞、收藏笔记
     */
    @Override
    public Response<FindNoteIsLikedAndCollectedRspVO> isLikedAndCollectedData(
            FindNoteIsLikedAndCollectedReqVO findNoteIsLikedAndCollectedReqVO) {
        Long noteId = findNoteIsLikedAndCollectedReqVO.getNoteId();
        Long currUserId = LoginUserContextHolder.getUserId();

        boolean isLiked = false;
        boolean isCollected = false;
        if (Objects.nonNull(currUserId)) {
            isLiked = checkNoteIsLiked(noteId, currUserId);
            isCollected = checkNoteIsCollected(noteId, currUserId);
        }

        return Response.success(FindNoteIsLikedAndCollectedRspVO.builder()
                .noteId(noteId)
                .isLiked(isLiked)
                .isCollected(isCollected)
                .build());
    }

    /**
     * 校验当前用户是否点赞笔记
     */
    private boolean checkNoteIsLiked(Long noteId, Long currUserId) {
        Long result = noteIdSetCache.contains(RedisKeyConstants.buildUserNoteLikeSetKey(currUserId),
                RedisKeyConstants.buildUserNoteLikeSetReadyKey(currUserId), noteId);
        if (Objects.equals(result, -1L)) {
            boolean liked = noteLikeDOMapper.selectCountByUserIdAndNoteId(currUserId, noteId) > 0;
            threadPoolTaskExecutor.submit(() -> rebuildLikeSetFromDatabase(currUserId));
            return liked;
        }
        return Objects.equals(result, 1L);
    }

    /**
     * 校验当前用户是否收藏笔记
     */
    private boolean checkNoteIsCollected(Long noteId, Long currUserId) {
        Long result = noteIdSetCache.contains(RedisKeyConstants.buildUserNoteCollectSetKey(currUserId),
                RedisKeyConstants.buildUserNoteCollectSetReadyKey(currUserId), noteId);
        if (Objects.equals(result, -1L)) {
            boolean collected = noteCollectionDOMapper.selectCountByUserIdAndNoteId(currUserId, noteId) > 0;
            threadPoolTaskExecutor.submit(() -> rebuildCollectSetFromDatabase(currUserId));
            return collected;
        }
        return Objects.equals(result, 1L);
    }

    /**
     * 构建初始化用户点赞 ZSet 的 Lua 参数
     */
    private static Object[] buildNoteLikeZSetLuaArgs(List<NoteLikeDO> noteLikeDOS, long expireSeconds) {
        int argsLength = noteLikeDOS.size() * 2 + 1;
        Object[] luaArgs = new Object[argsLength];

        int i = 0;
        for (NoteLikeDO noteLikeDO : noteLikeDOS) {
            luaArgs[i] = DateUtils.localDateTime2Timestamp(noteLikeDO.getCreateTime());
            luaArgs[i + 1] = noteLikeDO.getNoteId();
            i += 2;
        }

        luaArgs[argsLength - 1] = expireSeconds;
        return luaArgs;
    }

    /**
     * 构建笔记收藏 ZSet Lua 脚本参数
     */
    private static Object[] buildNoteCollectZSetLuaArgs(List<NoteCollectionDO> noteCollectionDOS,
                                                        long expireSeconds) {
        int argsLength = noteCollectionDOS.size() * 2 + 1;
        Object[] luaArgs = new Object[argsLength];

        int i = 0;
        for (NoteCollectionDO noteCollectionDO : noteCollectionDOS) {
            luaArgs[i] = DateUtils.localDateTime2Timestamp(noteCollectionDO.getCreateTime());
            luaArgs[i + 1] = noteCollectionDO.getNoteId();
            i += 2;
        }

        luaArgs[argsLength - 1] = expireSeconds;
        return luaArgs;
    }

    /**
     * 异步初始化用户收藏笔记 ZSet
     */
    private void asynInitUserNoteCollectsZSet(Long userId, String userNoteCollectZSetKey) {
        threadPoolTaskExecutor.execute(() -> {
            boolean hasKey = redisTemplate.hasKey(userNoteCollectZSetKey);
            if (!hasKey) {
                List<NoteCollectionDO> noteCollectionDOS =
                        noteCollectionDOMapper.selectCollectedByUserIdAndLimit(userId, 300);
                if (CollUtil.isNotEmpty(noteCollectionDOS)) {
                    long expireSeconds = 60 * 60 * 24 + RandomUtil.randomInt(60 * 60 * 24);
                    Object[] luaArgs = buildNoteCollectZSetLuaArgs(noteCollectionDOS, expireSeconds);

                    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
                    script.setScriptSource(new ResourceScriptSource(
                            new ClassPathResource("/lua/batch_add_note_collect_zset_and_expire.lua")));
                    script.setResultType(Long.class);
                    redisTemplate.execute(script, Collections.singletonList(userNoteCollectZSetKey), luaArgs);
                }
            }
        });
    }

    /**
     * 异步初始化用户点赞笔记 ZSet
     */
    private void asynInitUserNoteLikesZSet(Long userId, String userNoteLikeZSetKey) {
        threadPoolTaskExecutor.execute(() -> {
            boolean hasKey = redisTemplate.hasKey(userNoteLikeZSetKey);
            if (!hasKey) {
                List<NoteLikeDO> noteLikeDOS = noteLikeDOMapper.selectLikedByUserIdAndLimit(userId, 100);
                if (CollUtil.isNotEmpty(noteLikeDOS)) {
                    long expireSeconds = 60 * 60 * 24 + RandomUtil.randomInt(60 * 60 * 24);
                    Object[] luaArgs = buildNoteLikeZSetLuaArgs(noteLikeDOS, expireSeconds);

                    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
                    script.setScriptSource(new ResourceScriptSource(
                            new ClassPathResource("/lua/batch_add_note_like_zset_and_expire.lua")));
                    script.setResultType(Long.class);
                    redisTemplate.execute(script, Collections.singletonList(userNoteLikeZSetKey), luaArgs);
                }
            }
        });
    }

    /**
     * 校验笔记是否存在，若存在，则获取笔记发布者 ID
     */
    private Long checkNoteIsExistAndGetCreatorId(Long noteId) {
        NoteDetailCacheEntry cacheEntry = LOCAL_CACHE.getIfPresent(noteId);
        if (Objects.isNull(cacheEntry) || !cacheEntry.canServe()) {
            String noteDetailRedisKey = RedisKeyConstants.buildNoteDetailKey(noteId);
            String noteDetailJson = redisTemplate.opsForValue().get(noteDetailRedisKey);
            cacheEntry = StringUtils.isBlank(noteDetailJson) ? null : parseNoteDetailCacheEntry(noteDetailJson);
        }

        if (Objects.isNull(cacheEntry) || !cacheEntry.canServe()) {
                Long creatorId = noteDOMapper.selectCreatorIdByNoteId(noteId);
                if (Objects.isNull(creatorId)) {
                    throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
                }

                threadPoolTaskExecutor.submit(() -> {
                    FindNoteDetailReqVO findNoteDetailReqVO = FindNoteDetailReqVO.builder().id(noteId).build();
                    findNoteDetail(findNoteDetailReqVO);
                });
                return creatorId;
        }
        return cacheEntry.getDetail().getCreatorId();
    }

    private boolean changeRelationSet(Long userId, Long noteId, boolean likeRelation, boolean desiredState) {
        String setKey = likeRelation ? RedisKeyConstants.buildUserNoteLikeSetKey(userId)
                : RedisKeyConstants.buildUserNoteCollectSetKey(userId);
        String readyKey = likeRelation ? RedisKeyConstants.buildUserNoteLikeSetReadyKey(userId)
                : RedisKeyConstants.buildUserNoteCollectSetReadyKey(userId);
        String versionKey = likeRelation ? RedisKeyConstants.buildUserNoteLikeSetVersionKey(userId)
                : RedisKeyConstants.buildUserNoteCollectSetVersionKey(userId);
        long ttlSeconds = 60 * 60 * 24 + RandomUtil.randomInt(60 * 60 * 24);

        for (int attempt = 0; attempt < 5; attempt++) {
            Long memberState = noteIdSetCache.contains(setKey, readyKey, noteId);
            if (!Objects.equals(memberState, -1L)) {
                boolean currentlyPresent = Objects.equals(memberState, 1L);
                if (currentlyPresent == desiredState) {
                    return false;
                }
                Long changed = desiredState
                        ? noteIdSetCache.addIfReady(setKey, readyKey, versionKey, noteId, ttlSeconds)
                        : noteIdSetCache.removeIfReady(setKey, readyKey, versionKey, noteId, ttlSeconds);
                if (Objects.equals(changed, 1L)) {
                    return true;
                }
                if (Objects.equals(changed, 0L)) {
                    return false;
                }
                continue;
            }

            String version = noteIdSetCache.currentVersion(versionKey);
            int rowCount = likeRelation
                    ? noteLikeDOMapper.selectCountByUserIdAndNoteId(userId, noteId)
                    : noteCollectionDOMapper.selectCountByUserIdAndNoteId(userId, noteId);
            List<Long> existingIds = likeRelation
                    ? noteLikeDOMapper.selectByUserId(userId).stream().map(NoteLikeDO::getNoteId).toList()
                    : noteCollectionDOMapper.selectByUserId(userId).stream().map(NoteCollectionDO::getNoteId).toList();
            Set<Long> snapshot = new java.util.LinkedHashSet<>(existingIds);
            boolean currentlyPresent = rowCount > 0 || snapshot.contains(noteId);
            if (currentlyPresent) {
                snapshot.add(noteId);
            } else {
                snapshot.remove(noteId);
            }
            if (desiredState) {
                snapshot.add(noteId);
            } else {
                snapshot.remove(noteId);
            }

            if (noteIdSetCache.rebuildIfVersionMatches(setKey, readyKey, versionKey,
                    version, snapshot, ttlSeconds)) {
                return currentlyPresent != desiredState;
            }
        }

        throw new BizException(ResponseCodeEnum.SYSTEM_ERROR);
    }

    private void sendRelationEvent(String destination, Message<String> message, String hashKey,
                                   Long userId, boolean likeRelation, String zSetKey) {
        try {
            SendResult result = rocketMQTemplate.syncSendOrderly(destination, message, hashKey);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ did not confirm the relation event");
            }
        } catch (Exception e) {
            String setKey = likeRelation ? RedisKeyConstants.buildUserNoteLikeSetKey(userId)
                    : RedisKeyConstants.buildUserNoteCollectSetKey(userId);
            String readyKey = likeRelation ? RedisKeyConstants.buildUserNoteLikeSetReadyKey(userId)
                    : RedisKeyConstants.buildUserNoteCollectSetReadyKey(userId);
            String versionKey = likeRelation ? RedisKeyConstants.buildUserNoteLikeSetVersionKey(userId)
                    : RedisKeyConstants.buildUserNoteCollectSetVersionKey(userId);
            noteIdSetCache.invalidate(setKey, readyKey, versionKey, 60 * 60 * 24 * 2L);
            redisTemplate.delete(zSetKey);
            log.error("==> 笔记关系事件发送失败 destination={}, userId={}, 将失效关系缓存", destination, userId, e);
            throw new BizException(ResponseCodeEnum.SYSTEM_ERROR);
        }
    }

    private void rebuildLikeSetFromDatabase(Long userId) {
        String setKey = RedisKeyConstants.buildUserNoteLikeSetKey(userId);
        String readyKey = RedisKeyConstants.buildUserNoteLikeSetReadyKey(userId);
        String versionKey = RedisKeyConstants.buildUserNoteLikeSetVersionKey(userId);
        String version = noteIdSetCache.currentVersion(versionKey);
        List<Long> ids = noteLikeDOMapper.selectByUserId(userId).stream().map(NoteLikeDO::getNoteId).toList();
        long ttlSeconds = 60 * 30 + RandomUtil.randomInt(60 * 30);
        noteIdSetCache.rebuildIfVersionMatches(setKey, readyKey, versionKey, version, ids, ttlSeconds);
    }

    private void rebuildCollectSetFromDatabase(Long userId) {
        String setKey = RedisKeyConstants.buildUserNoteCollectSetKey(userId);
        String readyKey = RedisKeyConstants.buildUserNoteCollectSetReadyKey(userId);
        String versionKey = RedisKeyConstants.buildUserNoteCollectSetVersionKey(userId);
        String version = noteIdSetCache.currentVersion(versionKey);
        List<Long> ids = noteCollectionDOMapper.selectByUserId(userId).stream()
                .map(NoteCollectionDO::getNoteId).toList();
        long ttlSeconds = 60 * 30 + RandomUtil.randomInt(60 * 30);
        noteIdSetCache.rebuildIfVersionMatches(setKey, readyKey, versionKey, version, ids, ttlSeconds);
    }
    /**
     * 删除笔记
     *
     * @param deleteNoteReqVO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> deleteNote(DeleteNoteReqVO deleteNoteReqVO) {
        // 笔记 ID
        Long noteId = deleteNoteReqVO.getId();

        NoteDO selectNoteDO = noteDOMapper.selectByPrimaryKey(noteId);

        // 判断笔记是否存在
        if (Objects.isNull(selectNoteDO)) {
            throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
        }

        // 判断权限：非笔记发布者不允许删除笔记
        Long currUserId = LoginUserContextHolder.getUserId();
        if (!Objects.equals(currUserId, selectNoteDO.getCreatorId())) {
            throw new BizException(ResponseCodeEnum.NOTE_CANT_OPERATE);
        }

        // Even an idempotent delete retry should clear caches. The persistence
        // service only enqueues a count event when the status changes once.
        noteOperatePersistenceService.softDeleteNote(noteId, currUserId);

        enqueueNoteCacheInvalidation(currUserId, noteId);
        runAfterCommit(() -> invalidateNoteCaches(noteId, currUserId));

        return Response.success();
    }

    /**
     * 笔记仅对自己可见
     *
     * @param updateNoteVisibleOnlyMeReqVO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> visibleOnlyMe(UpdateNoteVisibleOnlyMeReqVO updateNoteVisibleOnlyMeReqVO) {
        // 笔记 ID
        Long noteId = updateNoteVisibleOnlyMeReqVO.getId();

        NoteDO selectNoteDO = noteDOMapper.selectByPrimaryKey(noteId);

        // 判断笔记是否存在
        if (Objects.isNull(selectNoteDO)) {
            throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
        }

        // 判断权限：非笔记发布者不允许修改笔记权限
        Long currUserId = LoginUserContextHolder.getUserId();
        if (!Objects.equals(currUserId, selectNoteDO.getCreatorId())) {
            throw new BizException(ResponseCodeEnum.NOTE_CANT_OPERATE);
        }

        // 构建更新 DO 实体类
        NoteDO noteDO = NoteDO.builder()
                .id(noteId)
                .visible(NoteVisibleEnum.PRIVATE.getCode()) // 可见性设置为仅对自己可见
                .updateTime(LocalDateTime.now())
                .build();

        // 执行更新 SQL
        int count = noteDOMapper.updateVisibleOnlyMe(noteDO);

        // 若影响的行数为 0，则表示该笔记无法修改为仅自己可见
        if (count == 0) {
            throw new BizException(ResponseCodeEnum.NOTE_CANT_VISIBLE_ONLY_ME);
        }

        // 设为私密后还要清理公开主页列表缓存，避免其他人继续看到被缓存的卡片。
        enqueueNoteCacheInvalidation(currUserId, noteId);
        runAfterCommit(() -> invalidateNoteCaches(noteId, currUserId));

        return Response.success();
    }

    /**
     * 笔记置顶 / 取消置顶
     *
     * @param topNoteReqVO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> topNote(TopNoteReqVO topNoteReqVO) {
        // 笔记 ID
        Long noteId = topNoteReqVO.getId();
        // 是否置顶
        Boolean isTop = topNoteReqVO.getIsTop();

        // 当前登录用户 ID
        Long currUserId = LoginUserContextHolder.getUserId();

        // 构建置顶/取消置顶 DO 实体类
        NoteDO noteDO = NoteDO.builder()
                .id(noteId)
                .isTop(isTop)
                .updateTime(LocalDateTime.now())
                .creatorId(currUserId) // 只有笔记所有者，才能置顶/取消置顶笔记
                .build();

        int count = noteDOMapper.updateIsTop(noteDO);

        if (count == 0) {
            throw new BizException(ResponseCodeEnum.NOTE_CANT_OPERATE);
        }

        enqueueNoteCacheInvalidation(currUserId, noteId);
        runAfterCommit(() -> invalidateNoteCaches(noteId, currUserId));

        return Response.success();
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private void invalidateNoteCaches(Long noteId, Long userId) {
        LOCAL_CACHE.invalidate(noteId);
        String noteDetailRedisKey = RedisKeyConstants.buildNoteDetailKey(noteId);
        String publishedNoteListRedisKey = RedisKeyConstants.buildPublishedNoteListKey(userId);
        try {
            redisTemplate.delete(Arrays.asList(noteDetailRedisKey, publishedNoteListRedisKey));
        } catch (Exception e) {
            log.error("## 笔记 Redis 缓存失效失败，noteId: {}, userId: {}", noteId, userId, e);
        }

    }

    private void enqueueNoteCacheInvalidation(Long userId, Long noteId) {
        LocalDateTime availableAt = LocalDateTime.now().plusSeconds(1);
        noteOperateOutboxService.enqueueCacheInvalidation(userId, noteId,
                MQConstants.TOPIC_DELETE_NOTE_LOCAL_CACHE, String.valueOf(noteId), availableAt);
        noteOperateOutboxService.enqueueCacheInvalidation(userId, noteId,
                MQConstants.TOPIC_DELAY_DELETE_NOTE_REDIS_CACHE,
                JsonUtils.toJsonString(Arrays.asList(noteId, userId)), availableAt);
    }

    /**
     * 校验笔记的可见性
     *
     * @param visible    是否可见
     * @param currUserId 当前用户 ID
     * @param creatorId  笔记创建者
     */
    private void checkNoteVisible(Integer visible, Long currUserId, Long creatorId) {
        if (Objects.equals(visible, NoteVisibleEnum.PRIVATE.getCode())
                && !Objects.equals(currUserId, creatorId)) { // 仅自己可见, 并且访问用户为笔记创建者才能访问，非本人则抛出异常
            throw new BizException(ResponseCodeEnum.NOTE_PRIVATE);
        }
    }

    /**
     * 校验笔记的可见性（针对 VO 实体类）
     *
     * @param userId
     * @param findNoteDetailRspVO
     */
    private void checkNoteVisibleFromVO(Long userId, FindNoteDetailRspVO findNoteDetailRspVO) {
        if (Objects.nonNull(findNoteDetailRspVO)) {
            Integer visible = findNoteDetailRspVO.getVisible();
            checkNoteVisible(visible, userId, findNoteDetailRspVO.getCreatorId());
        }
    }

}
