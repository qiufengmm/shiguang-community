package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.UserCountDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NotePublishCountEventMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NotePublishCountLockMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NotePublishCountReconciliationMapper;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CountNotePublishReconciliationServiceTest {

    private final NotePublishCountLockMapper lockMapper = mock(NotePublishCountLockMapper.class);
    private final NotePublishCountEventMapper eventMapper = mock(NotePublishCountEventMapper.class);
    private final NotePublishCountReconciliationMapper reconciliationMapper =
            mock(NotePublishCountReconciliationMapper.class);
    private final UserCountDOMapper userCountDOMapper = mock(UserCountDOMapper.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    private final CountNotePublishReconciliationService service = new CountNotePublishReconciliationService();

    @BeforeEach
    void injectDependencies() {
        ReflectionTestUtils.setField(service, "lockMapper", lockMapper);
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "reconciliationMapper", reconciliationMapper);
        ReflectionTestUtils.setField(service, "userCountDOMapper", userCountDOMapper);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
    }

    @Test
    void locksCreatorBeforeSnapshotAndReplacesCounterFromActiveNotes() {
        Long userId = 100L;
        when(lockMapper.lockByUserId(userId)).thenReturn(userId);
        when(userCountDOMapper.selectByUserId(userId))
                .thenReturn(UserCountDO.builder().userId(userId).noteTotal(49L).build());
        when(reconciliationMapper.countActiveNotesByUserId(userId)).thenReturn(52L);
        when(eventMapper.insertOutboxEventsAsApplied(userId)).thenReturn(2);

        CountNotePublishReconciliationService.Result result = service.reconcile(userId);

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.storedBefore()).isEqualTo(49L);
        assertThat(result.sourceCount()).isEqualTo(52L);
        assertThat(result.suppressedOutboxEvents()).isEqualTo(2);
        var order = inOrder(lockMapper, userCountDOMapper, reconciliationMapper, eventMapper, redisTemplate);
        order.verify(lockMapper).ensureLockRow(userId);
        order.verify(lockMapper).lockByUserId(userId);
        order.verify(userCountDOMapper).selectByUserId(userId);
        order.verify(reconciliationMapper).countActiveNotesByUserId(userId);
        order.verify(eventMapper).insertOutboxEventsAsApplied(userId);
        order.verify(userCountDOMapper).setNoteTotalByUserId(52L, userId);
        order.verify(redisTemplate).delete(RedisKeyConstants.buildCountUserKey(userId));
    }

    @Test
    void refusesToRepairWhenThePerCreatorLockWasNotAcquired() {
        Long userId = 100L;
        when(lockMapper.lockByUserId(userId)).thenReturn(null);

        assertThatThrownBy(() -> service.reconcile(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not acquire");

        verify(userCountDOMapper, never()).selectByUserId(userId);
        verify(reconciliationMapper, never()).countActiveNotesByUserId(userId);
        verify(eventMapper, never()).insertOutboxEventsAsApplied(userId);
        verify(userCountDOMapper, never()).setNoteTotalByUserId(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(userId));
    }

    @Test
    void invalidatesUserCountCacheOnlyAfterTheReconciliationCommits() {
        Long userId = 101L;
        when(lockMapper.lockByUserId(userId)).thenReturn(userId);
        when(userCountDOMapper.selectByUserId(userId))
                .thenReturn(UserCountDO.builder().userId(userId).noteTotal(2L).build());
        when(reconciliationMapper.countActiveNotesByUserId(userId)).thenReturn(3L);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.reconcile(userId);
            verify(redisTemplate, never()).delete(RedisKeyConstants.buildCountUserKey(userId));

            TransactionSynchronizationUtils.triggerAfterCommit();

            verify(redisTemplate).delete(RedisKeyConstants.buildCountUserKey(userId));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
