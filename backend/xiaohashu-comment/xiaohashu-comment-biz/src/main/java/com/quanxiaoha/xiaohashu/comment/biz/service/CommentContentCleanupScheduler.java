package com.quanxiaoha.xiaohashu.comment.biz.service;

import com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentTombstoneDO;
import com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentTombstoneMapper;
import com.quanxiaoha.xiaohashu.comment.biz.rpc.KeyValueRpcService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
@Slf4j
public class CommentContentCleanupScheduler {
    private static final int BATCH_SIZE = 100;

    @Resource
    private CommentTombstoneMapper commentTombstoneMapper;

    @Resource
    private KeyValueRpcService keyValueRpcService;

    @Scheduled(fixedDelayString = "${comment.delivery.content-cleanup-delay-ms:30000}")
    public void cleanupDeletedCommentBodies() {
        List<CommentTombstoneDO> pending = commentTombstoneMapper.selectPendingContentCleanup(BATCH_SIZE);
        for (CommentTombstoneDO tombstone : pending) {
            try {
                if (!Boolean.TRUE.equals(tombstone.getContentEmpty())
                        && tombstone.getContentUuid() != null && !tombstone.getContentUuid().isBlank()) {
                    keyValueRpcService.deleteCommentContent(tombstone.getNoteId(), tombstone.getCreateTime(),
                            tombstone.getContentUuid());
                }
                commentTombstoneMapper.markContentCleaned(tombstone.getCommentId());
            } catch (Exception e) {
                log.error("Deleted comment body cleanup failed, commentId={}", tombstone.getCommentId(), e);
            }
        }
    }
}
