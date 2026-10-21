package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CountSourceRefreshMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Refreshes stored counters from committed source rows; repeated events are harmless. */
@Service
public class CountSourceRefreshService {

    private final CountSourceRefreshMapper mapper;

    public CountSourceRefreshService(CountSourceRefreshMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void refreshFollowing(long userId) {
        requireId(userId, "userId");
        lockUserRow(userId);
        mapper.updateFollowingTotal(userId, requiredCount(mapper.selectFollowingTotal(userId), "following", userId));
    }

    @Transactional
    public void refreshFans(long userId) {
        requireId(userId, "userId");
        lockUserRow(userId);
        mapper.updateFansTotal(userId, requiredCount(mapper.selectFansTotal(userId), "fans", userId));
    }

    @Transactional
    public long refreshNoteLike(long noteId, Long creatorId) {
        requireId(noteId, "noteId");
        long resolvedCreatorId = resolveCreatorId(noteId, creatorId);
        lockNoteRow(noteId);
        lockUserRow(resolvedCreatorId);
        mapper.updateNoteLikeTotal(noteId, requiredCount(mapper.selectNoteLikeTotal(noteId), "noteLike", noteId));
        mapper.updateUserLikeTotal(resolvedCreatorId,
                requiredCount(mapper.selectUserLikeTotal(resolvedCreatorId), "userLike", resolvedCreatorId));
        return resolvedCreatorId;
    }

    @Transactional
    public long refreshNoteCollect(long noteId, Long creatorId) {
        requireId(noteId, "noteId");
        long resolvedCreatorId = resolveCreatorId(noteId, creatorId);
        lockNoteRow(noteId);
        lockUserRow(resolvedCreatorId);
        mapper.updateNoteCollectTotal(noteId,
                requiredCount(mapper.selectNoteCollectTotal(noteId), "noteCollect", noteId));
        mapper.updateUserCollectTotal(resolvedCreatorId,
                requiredCount(mapper.selectUserCollectTotal(resolvedCreatorId), "userCollect", resolvedCreatorId));
        return resolvedCreatorId;
    }

    private long resolveCreatorId(long noteId, Long creatorId) {
        if (creatorId != null && creatorId > 0) return creatorId;
        Long resolved = mapper.selectNoteCreatorId(noteId);
        if (resolved == null || resolved <= 0) {
            throw new IllegalStateException("Cannot resolve creator for note " + noteId);
        }
        return resolved;
    }

    private void lockUserRow(long userId) {
        mapper.ensureUserCountRow(userId);
        if (mapper.lockUserCountRow(userId) == null) {
            throw new IllegalStateException("Could not lock user counter " + userId);
        }
    }

    private void lockNoteRow(long noteId) {
        mapper.ensureNoteCountRow(noteId);
        if (mapper.lockNoteCountRow(noteId) == null) {
            throw new IllegalStateException("Could not lock note counter " + noteId);
        }
    }

    private long requiredCount(Long count, String metric, long entityId) {
        if (count == null || count < 0) {
            throw new IllegalStateException("Missing or invalid source count for " + metric + ":" + entityId);
        }
        return count;
    }

    private void requireId(long id, String field) {
        if (id <= 0) throw new IllegalArgumentException("Invalid " + field);
    }
}
