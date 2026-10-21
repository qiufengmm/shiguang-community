package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMapper;
import com.quanxiaoha.xiaohashu.data.align.rpc.CountNotePublishReconciliationRpcService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class CountReconciliationRepairServiceTest {

    @Test
    void locksTheCounterAndRecomputesItsAuthoritativeSourceBeforeRepairing() {
        CountAuditMapper mapper = mock(CountAuditMapper.class);
        when(mapper.ensureCounterRow("FANS", 17L)).thenReturn(1);
        when(mapper.lockCounterRow("FANS", 17L)).thenReturn(17L);
        when(mapper.selectSourceCount("FANS", 17L)).thenReturn(2L);
        when(mapper.selectStoredCount("FANS", 17L)).thenReturn(0L);
        when(mapper.updateStoredCount("FANS", 17L, 2L)).thenReturn(1);

        CountReconciliationRepairService service = new CountReconciliationRepairService(mapper);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        CountReconciliationRepairService.RepairResult result = service.repair(CountAuditMetric.FANS, 17L);

        assertThat(result.repaired()).isTrue();
        InOrder ordered = inOrder(mapper);
        ordered.verify(mapper).ensureCounterRow("FANS", 17L);
        ordered.verify(mapper).lockCounterRow("FANS", 17L);
        ordered.verify(mapper).selectSourceCount("FANS", 17L);
        ordered.verify(mapper).selectStoredCount("FANS", 17L);
        ordered.verify(mapper).updateStoredCount("FANS", 17L, 2L);
        verify(redisTemplate).delete("count:user:17");
    }

    @Test
    void notePublishMismatchUsesTheCountServicesLockedReconciliation() {
        CountAuditMapper mapper = mock(CountAuditMapper.class);
        CountNotePublishReconciliationRpcService rpc = mock(CountNotePublishReconciliationRpcService.class);
        CountReconciliationRepairService service = new CountReconciliationRepairService(mapper);
        ReflectionTestUtils.setField(service, "notePublishReconciliationRpcService", rpc);
        when(mapper.selectStoredCount("NOTE_PUBLISH", 42L)).thenReturn(5L, 6L);
        when(rpc.reconcile(42L)).thenReturn(true);

        CountReconciliationRepairService.RepairResult result = service.repair(CountAuditMetric.NOTE_PUBLISH, 42L);

        assertThat(result.previousCount()).isEqualTo(5L);
        assertThat(result.sourceCount()).isEqualTo(6L);
        assertThat(result.repaired()).isTrue();
        verify(mapper, never()).lockCounterRow("NOTE_PUBLISH", 42L);
        verify(rpc).reconcile(42L);
    }

    @Test
    void invalidatesGenericCounterCacheOnlyAfterTheRepairTransactionCommits() {
        CountAuditMapper mapper = mock(CountAuditMapper.class);
        when(mapper.ensureCounterRow("FANS", 17L)).thenReturn(1);
        when(mapper.lockCounterRow("FANS", 17L)).thenReturn(17L);
        when(mapper.selectSourceCount("FANS", 17L)).thenReturn(2L);
        when(mapper.selectStoredCount("FANS", 17L)).thenReturn(0L);
        when(mapper.updateStoredCount("FANS", 17L, 2L)).thenReturn(1);
        @SuppressWarnings("unchecked")
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        CountReconciliationRepairService service = new CountReconciliationRepairService(mapper);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.repair(CountAuditMetric.FANS, 17L);
            verify(redisTemplate, never()).delete("count:user:17");

            TransactionSynchronizationUtils.triggerAfterCommit();

            verify(redisTemplate).delete("count:user:17");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
