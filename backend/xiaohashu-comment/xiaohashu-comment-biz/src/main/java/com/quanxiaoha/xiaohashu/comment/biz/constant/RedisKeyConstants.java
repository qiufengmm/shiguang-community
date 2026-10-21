package com.quanxiaoha.xiaohashu.comment.biz.constant;

import java.util.Locale;

/** Redis Key 常量。 */
public class RedisKeyConstants {

    private static final String COUNT_COMMENT_TOTAL_KEY_PREFIX = "count:note:";
    public static final String FIELD_COMMENT_TOTAL = "commentTotal";

    private static final String COUNT_COMMENT_KEY_PREFIX = "count:comment:";
    public static final String FIELD_CHILD_COMMENT_TOTAL = "childCommentTotal";
    public static final String FIELD_LIKE_TOTAL = "likeTotal";

    private static final String COMMENT_LIST_KEY_PREFIX = "comment:list:";
    private static final String COMMENT_LIST_GENERATION_KEY_PREFIX = "comment:list:generation:";
    private static final String COMMENT_LIST_READY_KEY_PREFIX = "comment:list:ready:";
    private static final String COMMENT_LIST_REBUILD_LOCK_KEY_PREFIX = "comment:list:rebuild-lock:";
    private static final String COMMENT_DETAIL_KEY_PREFIX = "comment:detail:v2:";

    private static final String CHILD_COMMENT_LIST_KEY_PREFIX = "comment:childList:";

    private static final String HAVE_FIRST_REPLY_COMMENT_KEY_PREFIX = "comment:havaFirstReplyCommentId:";

    private static final String BLOOM_COMMENT_LIKES_KEY_PREFIX = "bloom:comment:likes:";

    public static String buildNoteCommentTotalKey(Long noteId) {
        return COUNT_COMMENT_TOTAL_KEY_PREFIX + noteId;
    }

    public static String buildCountCommentKey(Long commentId) {
        return COUNT_COMMENT_KEY_PREFIX + commentId;
    }

    public static String buildCommentListKey(Long noteId) {
        return COMMENT_LIST_KEY_PREFIX + noteId;
    }

    public static String buildCommentListGenerationKey(Long noteId) {
        return COMMENT_LIST_GENERATION_KEY_PREFIX + noteId;
    }

    public static String buildCommentListReadyKey(Long noteId) {
        return COMMENT_LIST_READY_KEY_PREFIX + noteId;
    }

    public static String buildCommentListRebuildLockKey(Long noteId) {
        return COMMENT_LIST_REBUILD_LOCK_KEY_PREFIX + noteId;
    }

    public static String buildHotCommentMember(Long commentId) {
        if (commentId == null || commentId < 0) {
            throw new IllegalArgumentException("commentId must be a non-negative Long");
        }
        return String.format(Locale.ROOT, "%019d", commentId);
    }

    public static String buildChildCommentListKey(Long commentId) {
        return CHILD_COMMENT_LIST_KEY_PREFIX + commentId;
    }

    public static String buildCommentDetailKey(Object commentId) {
        return COMMENT_DETAIL_KEY_PREFIX + commentId;
    }

    public static String buildHaveFirstReplyCommentKey(Long commentId) {
        return HAVE_FIRST_REPLY_COMMENT_KEY_PREFIX + commentId;
    }

    public static String buildBloomCommentLikesKey(Long userId) {
        return BLOOM_COMMENT_LIKES_KEY_PREFIX + userId;
    }
}
