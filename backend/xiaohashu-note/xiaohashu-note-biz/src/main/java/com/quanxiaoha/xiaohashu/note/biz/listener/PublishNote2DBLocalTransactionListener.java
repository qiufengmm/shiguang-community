package com.quanxiaoha.xiaohashu.note.biz.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.note.biz.convert.NoteConvert;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteContentSagaDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.PublishNoteDTO;
import com.quanxiaoha.xiaohashu.note.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteContentSagaService;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteOperatePersistenceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 事务消息：笔记发布入库
 */
@RocketMQTransactionListener
@Slf4j
public class PublishNote2DBLocalTransactionListener implements RocketMQLocalTransactionListener {

    private static final long TRANSACTION_STATE_TTL_HOURS = 12;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private NoteDOMapper noteDOMapper;

    @Resource
    private NoteOperatePersistenceService noteOperatePersistenceService;

    @Resource
    private NoteContentSagaService noteContentSagaService;

    @Resource
    private KeyValueRpcService keyValueRpcService;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    private final Cache<Long, LocalTransactionState> localTransactionStates = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(TRANSACTION_STATE_TTL_HOURS, TimeUnit.HOURS)
            .build();

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        PublishNoteDTO publishNoteDTO = parsePublishNote(msg);
        if (Objects.isNull(publishNoteDTO) || Objects.isNull(publishNoteDTO.getId())) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        Long noteId = publishNoteDTO.getId();
        localTransactionStates.put(noteId, LocalTransactionState.IN_PROGRESS);
        log.info("## 笔记发布本地事务开始，noteId: {}", noteId);

        String contentUuid = publishNoteDTO.getContentUuid();
        String sagaOwner = UUID.randomUUID().toString();
        NoteContentSagaDO contentSaga = null;
        try {
            if (!Boolean.FALSE.equals(publishNoteDTO.getIsContentEmpty())
                    || isBlank(contentUuid) || isBlank(publishNoteDTO.getContent())) {
                localTransactionStates.put(noteId, LocalTransactionState.ROLLED_BACK);
                return RocketMQLocalTransactionState.ROLLBACK;
            }

            // MySQL commits this recovery record before the cross-store Cassandra write starts.
            contentSaga = noteContentSagaService.preparePublish(publishNoteDTO, sagaOwner);
            if (NoteContentSagaService.STATUS_COMPLETED.equals(contentSaga.getStatus())) {
                localTransactionStates.put(noteId, LocalTransactionState.COMMITTED);
                return RocketMQLocalTransactionState.COMMIT;
            }
            if (NoteContentSagaService.STATUS_ROLLED_BACK.equals(contentSaga.getStatus())) {
                localTransactionStates.put(noteId, LocalTransactionState.ROLLED_BACK);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            if (!NoteContentSagaService.STATUS_PENDING.equals(contentSaga.getStatus())
                    || !sagaOwner.equals(contentSaga.getLeaseOwner())) {
                localTransactionStates.put(noteId, LocalTransactionState.UNKNOWN);
                return RocketMQLocalTransactionState.UNKNOWN;
            }

            // 先同步写入 Cassandra。MySQL 元数据还未插入，此处失败时可安全清理新 UUID 的孤立正文。
            boolean contentSaved = keyValueRpcService.saveNoteContent(contentUuid, publishNoteDTO.getContent());
            if (!contentSaved) {
                noteContentSagaService.scheduleRetry(contentSaga,
                        new IllegalStateException("KV service did not confirm note content save"));
                localTransactionStates.put(noteId, LocalTransactionState.UNKNOWN);
                return RocketMQLocalTransactionState.UNKNOWN;
            }
        } catch (Exception e) {
            log.error("## 笔记正文同步写入失败，noteId: {}", noteId, e);
            if (contentSaga != null && sagaOwner.equals(contentSaga.getLeaseOwner())) {
                try {
                    noteContentSagaService.scheduleRetry(contentSaga, e);
                } catch (Exception sagaError) {
                    e.addSuppressed(sagaError);
                }
            }
            localTransactionStates.put(noteId, LocalTransactionState.UNKNOWN);
            return RocketMQLocalTransactionState.UNKNOWN;
        }

        try {
            noteOperatePersistenceService.insertPublishedNote(
                    NoteConvert.INSTANCE.convertDTO2DO(publishNoteDTO), contentSaga);
        } catch (Exception insertException) {
            return resolveInsertFailure(publishNoteDTO, contentSaga, insertException);
        }

        localTransactionStates.put(noteId, LocalTransactionState.COMMITTED);
        publishCommittedFollowUps(publishNoteDTO);
        return RocketMQLocalTransactionState.COMMIT;
    }

    private RocketMQLocalTransactionState resolveInsertFailure(PublishNoteDTO dto,
                                                                NoteContentSagaDO contentSaga,
                                                                Exception insertException) {
        Long noteId = dto.getId();
        String persistedContentUuid;
        try {
            persistedContentUuid = noteDOMapper.selectContentUuidByNoteId(noteId);
        } catch (Exception lookupException) {
            insertException.addSuppressed(lookupException);
            log.error("## 笔记 SQL 提交结果未知，保留正文等待事务回查，noteId: {}", noteId, insertException);
            try {
                noteContentSagaService.scheduleRetry(contentSaga, insertException);
            } catch (Exception sagaError) {
                insertException.addSuppressed(sagaError);
            }
            localTransactionStates.put(noteId, LocalTransactionState.UNKNOWN);
            return RocketMQLocalTransactionState.UNKNOWN;
        }

        if (Objects.equals(dto.getContentUuid(), persistedContentUuid)) {
            log.warn("## SQL 调用异常但查到相同正文 UUID，按已提交处理，noteId: {}", noteId, insertException);
            try {
                NoteContentSagaDO currentSaga = noteContentSagaService.find(contentSaga.getOperationId());
                if (currentSaga != null && NoteContentSagaService.STATUS_PENDING.equals(currentSaga.getStatus())
                        && Objects.equals(currentSaga.getLeaseOwner(), contentSaga.getLeaseOwner())
                        && Objects.equals(currentSaga.getLeaseVersion(), contentSaga.getLeaseVersion())) {
                    noteOperatePersistenceService.markNoteContentSagaCompleted(contentSaga);
                }
            } catch (Exception sagaError) {
                insertException.addSuppressed(sagaError);
                localTransactionStates.put(noteId, LocalTransactionState.UNKNOWN);
                return RocketMQLocalTransactionState.UNKNOWN;
            }
            localTransactionStates.put(noteId, LocalTransactionState.COMMITTED);
            publishCommittedFollowUps(dto);
            return RocketMQLocalTransactionState.COMMIT;
        }

        if (persistedContentUuid != null || isDefiniteSqlRejection(insertException)) {
            try {
                noteContentSagaService.markDeletePending(contentSaga, insertException);
            } catch (Exception sagaError) {
                insertException.addSuppressed(sagaError);
                log.error("## 笔记正文清理任务无法持久化，保持 UNKNOWN，noteId: {}", noteId, insertException);
                localTransactionStates.put(noteId, LocalTransactionState.UNKNOWN);
                return RocketMQLocalTransactionState.UNKNOWN;
            }
            localTransactionStates.put(noteId, LocalTransactionState.ROLLED_BACK);
            log.error("## 笔记元数据确定未提交，已持久化正文清理任务，noteId: {}", noteId, insertException);
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        // 网络中断、超时等异常不能证明 MySQL 最终没有提交；禁止删除可能已被引用的正文。
        try {
            noteContentSagaService.scheduleRetry(contentSaga, insertException);
        } catch (Exception sagaError) {
            insertException.addSuppressed(sagaError);
        }
        localTransactionStates.put(noteId, LocalTransactionState.UNKNOWN);
        log.error("## 笔记 SQL 提交状态无法确认，保留正文等待事务回查，noteId: {}", noteId, insertException);
        return RocketMQLocalTransactionState.UNKNOWN;
    }

    private void publishCommittedFollowUps(PublishNoteDTO dto) {
        try {
            redisTemplate.delete(RedisKeyConstants.buildPublishedNoteListKey(dto.getCreatorId()));
        } catch (Exception e) {
            log.error("## 删除已发布笔记列表缓存失败，noteId: {}", dto.getId(), e);
        }

        try {
            sendDelayDeleteRedisPublishedNoteListCacheMQ(dto.getCreatorId());
        } catch (Exception e) {
            log.error("## 发送已发布笔记列表延迟删除消息失败，noteId: {}", dto.getId(), e);
        }

    }

    private void sendDelayDeleteRedisPublishedNoteListCacheMQ(Long userId) {
        Message<String> message = MessageBuilder.withPayload(String.valueOf(userId)).build();
        rocketMQTemplate.asyncSend(MQConstants.TOPIC_DELAY_DELETE_PUBLISHED_NOTE_LIST_REDIS_CACHE,
                message, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        log.info("## 延时删除 Redis 已发布笔记列表缓存消息发送成功...");
                    }

                    @Override
                    public void onException(Throwable e) {
                        log.error("## 延时删除 Redis 已发布笔记列表缓存消息发送失败...", e);
                    }
                }, 3000, 1);
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        PublishNoteDTO dto = parsePublishNote(msg);
        if (Objects.isNull(dto) || Objects.isNull(dto.getId())) {
            return RocketMQLocalTransactionState.UNKNOWN;
        }

        LocalTransactionState localState = localTransactionStates.getIfPresent(dto.getId());
        if (localState == LocalTransactionState.COMMITTED) {
            return RocketMQLocalTransactionState.COMMIT;
        }
        if (localState == LocalTransactionState.ROLLED_BACK) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        try {
            String persistedContentUuid = noteDOMapper.selectContentUuidByNoteId(dto.getId());
            if (Objects.equals(dto.getContentUuid(), persistedContentUuid) && persistedContentUuid != null) {
                localTransactionStates.put(dto.getId(), LocalTransactionState.COMMITTED);
                return RocketMQLocalTransactionState.COMMIT;
            }
            if (persistedContentUuid != null) {
                localTransactionStates.put(dto.getId(), LocalTransactionState.ROLLED_BACK);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            NoteContentSagaDO saga = noteContentSagaService.find(NoteContentSagaService.publishOperationId(dto.getId()));
            if (saga != null && NoteContentSagaService.STATUS_ROLLED_BACK.equals(saga.getStatus())) {
                localTransactionStates.put(dto.getId(), LocalTransactionState.ROLLED_BACK);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            // 缺少行不能区分“还在执行”和“提交结果未知”，保持 UNKNOWN，不能提前删除正文。
            return RocketMQLocalTransactionState.UNKNOWN;
        } catch (Exception e) {
            log.error("## 笔记事务回查失败，保持 UNKNOWN，noteId: {}", dto.getId(), e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    private PublishNoteDTO parsePublishNote(Message msg) {
        try {
            String payload = new String((byte[]) msg.getPayload(), StandardCharsets.UTF_8);
            return JsonUtils.parseObject(payload, PublishNoteDTO.class);
        } catch (Exception e) {
            log.error("## 笔记事务消息解析失败", e);
            return null;
        }
    }

    private boolean isDefiniteSqlRejection(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException
                    || current instanceof BadSqlGrammarException
                    || current instanceof InvalidDataAccessApiUsageException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private enum LocalTransactionState {
        IN_PROGRESS,
        COMMITTED,
        ROLLED_BACK,
        UNKNOWN
    }
}
