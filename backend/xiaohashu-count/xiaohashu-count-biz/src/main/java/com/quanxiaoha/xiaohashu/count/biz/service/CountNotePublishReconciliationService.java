package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.UserCountDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NotePublishCountEventMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NotePublishCountLockMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NotePublishCountReconciliationMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CountNotePublishReconciliationService {

    @Resource
    private NotePublishCountLockMapper lockMapper;

    @Resource
    private NotePublishCountEventMapper eventMapper;

    @Resource
    private NotePublishCountReconciliationMapper reconciliationMapper;

    @Resource
    private UserCountDOMapper userCountDOMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Transactional(rollbackFor = Exception.class)
    public Result reconcile(Long userId) {
        if (userId == null) throw new IllegalArgumentException("userId is required");

        lockMapper.ensureLockRow(userId);
        if (lockMapper.lockByUserId(userId) == null) {
            throw new IllegalStateException("could not acquire note publish count lock");
        }

        UserCountDO current = userCountDOMapper.selectByUserId(userId);
        long storedBefore = current == null || current.getNoteTotal() == null ? 0L : current.getNoteTotal();
        long sourceCount = reconciliationMapper.countActiveNotesByUserId(userId);
        int suppressedOutboxEvents = eventMapper.insertOutboxEventsAsApplied(userId);
        userCountDOMapper.setNoteTotalByUserId(sourceCount, userId);
        invalidateUserCountCacheAfterCommit(userId);
        return new Result(userId, storedBefore, sourceCount, suppressedOutboxEvents);
    }

    private void invalidateUserCountCacheAfterCommit(Long userId) {
        Runnable invalidate = () -> redisTemplate.delete(RedisKeyConstants.buildCountUserKey(userId));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate.run();
            }
        });
    }

    public record Result(Long userId, long storedBefore, long sourceCount, int suppressedOutboxEvents) {
    }
}
