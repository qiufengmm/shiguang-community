package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountSourceRefreshMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CountSourceRefreshServiceTest {

    @Test
    void noteLikeReplaySetsSourceTotalsAfterLockingBothCounterRows() {
        CountSourceRefreshMapper mapper = mock(CountSourceRefreshMapper.class);
        when(mapper.lockNoteCountRow(73L)).thenReturn(73L);
        when(mapper.lockUserCountRow(19L)).thenReturn(19L);
        when(mapper.selectNoteLikeTotal(73L)).thenReturn(12L);
        when(mapper.selectUserLikeTotal(19L)).thenReturn(34L);

        new CountSourceRefreshService(mapper).refreshNoteLike(73L, 19L);

        InOrder ordered = inOrder(mapper);
        ordered.verify(mapper).ensureNoteCountRow(73L);
        ordered.verify(mapper).lockNoteCountRow(73L);
        ordered.verify(mapper).ensureUserCountRow(19L);
        ordered.verify(mapper).lockUserCountRow(19L);
        ordered.verify(mapper).selectNoteLikeTotal(73L);
        ordered.verify(mapper).updateNoteLikeTotal(73L, 12L);
        ordered.verify(mapper).selectUserLikeTotal(19L);
        ordered.verify(mapper).updateUserLikeTotal(19L, 34L);
    }

    @Test
    void rejectsAnInvalidEntityWithoutQueryingTheDatabase() {
        CountSourceRefreshMapper mapper = mock(CountSourceRefreshMapper.class);

        assertThatThrownBy(() -> new CountSourceRefreshService(mapper).refreshFollowing(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void returnsTheResolvedCreatorForCacheInvalidationWhenLegacyEventOmitsIt() {
        CountSourceRefreshMapper mapper = mock(CountSourceRefreshMapper.class);
        when(mapper.selectNoteCreatorId(73L)).thenReturn(19L);
        when(mapper.lockNoteCountRow(73L)).thenReturn(73L);
        when(mapper.lockUserCountRow(19L)).thenReturn(19L);
        when(mapper.selectNoteLikeTotal(73L)).thenReturn(12L);
        when(mapper.selectUserLikeTotal(19L)).thenReturn(34L);

        long creatorId = new CountSourceRefreshService(mapper).refreshNoteLike(73L, null);

        org.assertj.core.api.Assertions.assertThat(creatorId).isEqualTo(19L);
    }
}
