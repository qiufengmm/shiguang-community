package com.quanxiaoha.xiaohashu.note.biz.constant;

/**
 * Redis Key 常量
 **/
public class RedisKeyConstants {

    /**
     * 已发布笔记列表 KEY 前缀
     */
    private static final String PUBLISHED_NOTE_LIST_KEY = "note:published:list:";

    /**
     * 笔记详情 KEY 前缀
     */
    public static final String NOTE_DETAIL_KEY = "note:detail:v2:";

    /**
     * 布隆过滤器：用户笔记点赞
     */
    public static final String BLOOM_USER_NOTE_LIKE_LIST_KEY = "bloom:note:likes:";

    /**
     * 布隆过滤器：用户笔记收藏
     */
    public static final String BLOOM_USER_NOTE_COLLECT_LIST_KEY = "bloom:note:collects:";

    /**
     * 用户笔记收藏列表 ZSet 前缀
     */
    public static final String USER_NOTE_COLLECT_ZSET_KEY = "user:note:collects:";

    /**
     * 用户笔记点赞列表 ZSet 前缀
     */
    public static final String USER_NOTE_LIKE_ZSET_KEY = "user:note:likes:";

    /**
     * Roaring Bitmap：用户笔记点赞前缀
     */
    public static final String R_BITMAP_USER_NOTE_LIKE_LIST_KEY = "rbitmap:note:likes:";

    /**
     * Roaring Bitmap：用户笔记收藏前缀
     */
    public static final String R_BITMAP_USER_NOTE_COLLECT_LIST_KEY = "rbitmap:note:collects:";

    /** 新版完整 Long 笔记 ID 点赞集合前缀。 */
    public static final String USER_NOTE_LIKE_SET_KEY = "note:likes:v2:";

    /** 新版完整 Long 笔记 ID 收藏集合前缀。 */
    public static final String USER_NOTE_COLLECT_SET_KEY = "note:collects:v2:";


    /**
     * 构建完整的笔记详情 KEY
     *
     * @param noteId
     * @return
     */
    public static String buildNoteDetailKey(Long noteId) {
        return NOTE_DETAIL_KEY + noteId;
    }

    /**
     * 构建完整的已发布笔记列表 KEY
     */
    public static String buildPublishedNoteListKey(Long userId) {
        return PUBLISHED_NOTE_LIST_KEY + userId;
    }

    /**
     * 构建完整的用户笔记点赞布隆过滤器 Key
     */
    public static String buildBloomUserNoteLikeListKey(Long userId) {
        return BLOOM_USER_NOTE_LIKE_LIST_KEY + userId;
    }

    /**
     * 构建完整的布隆过滤器：用户笔记收藏 Key
     */
    public static String buildBloomUserNoteCollectListKey(Long userId) {
        return BLOOM_USER_NOTE_COLLECT_LIST_KEY + userId;
    }

    /**
     * 构建完整的用户笔记收藏列表 ZSet Key
     */
    public static String buildUserNoteCollectZSetKey(Long userId) {
        return USER_NOTE_COLLECT_ZSET_KEY + userId;
    }

    /**
     * 构建完整的用户笔记点赞列表 ZSet Key
     */
    public static String buildUserNoteLikeZSetKey(Long userId) {
        return USER_NOTE_LIKE_ZSET_KEY + userId;
    }

    /**
     * 构建完整的用户笔记点赞 Roaring Bitmap Key
     */
    public static String buildRBitmapUserNoteLikeListKey(Long userId) {
        return R_BITMAP_USER_NOTE_LIKE_LIST_KEY + userId;
    }

    /**
     * 构建完整的用户笔记收藏 Roaring Bitmap Key
     */
    public static String buildRBitmapUserNoteCollectListKey(Long userId) {
        return R_BITMAP_USER_NOTE_COLLECT_LIST_KEY + userId;
    }

    public static String buildUserNoteLikeSetKey(Long userId) {
        return USER_NOTE_LIKE_SET_KEY + userId;
    }

    public static String buildUserNoteLikeSetReadyKey(Long userId) {
        return USER_NOTE_LIKE_SET_KEY + "ready:" + userId;
    }

    public static String buildUserNoteLikeSetVersionKey(Long userId) {
        return USER_NOTE_LIKE_SET_KEY + "version:" + userId;
    }

    public static String buildUserNoteCollectSetKey(Long userId) {
        return USER_NOTE_COLLECT_SET_KEY + userId;
    }

    public static String buildUserNoteCollectSetReadyKey(Long userId) {
        return USER_NOTE_COLLECT_SET_KEY + "ready:" + userId;
    }

    public static String buildUserNoteCollectSetVersionKey(Long userId) {
        return USER_NOTE_COLLECT_SET_KEY + "version:" + userId;
    }

}
