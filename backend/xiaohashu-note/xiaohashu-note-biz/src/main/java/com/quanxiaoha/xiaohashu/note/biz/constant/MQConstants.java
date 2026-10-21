package com.quanxiaoha.xiaohashu.note.biz.constant;

/**
 * MQ 常量
 **/
public interface MQConstants {

    /**
     * Topic 主题：删除笔记本地缓存
     */
    String TOPIC_DELETE_NOTE_LOCAL_CACHE = "DeleteNoteLocalCacheTopic";

    /**
     * Topic 主题：延迟双删 Redis 笔记缓存
     */
    String TOPIC_DELAY_DELETE_NOTE_REDIS_CACHE = "DelayDeleteNoteRedisCacheTopic";

    /**
     * Topic 主题：延迟双删 Redis 已发布笔记列表缓存
     */
    String TOPIC_DELAY_DELETE_PUBLISHED_NOTE_LIST_REDIS_CACHE = "DelayDeletePublishedNoteListRedisCacheTopic";

    /**
     * Topic: 发布笔记事务消息
     */
    String TOPIC_PUBLISH_NOTE_TRANSACTION = "PublishNoteTransactionTopic";

    /**
     * Topic: 点赞、取消点赞共用一个
     */
    String TOPIC_LIKE_OR_UNLIKE = "LikeUnlikeTopic";

    /**
     * Topic: 笔记点赞、取消点赞计数
     */
    String TOPIC_COUNT_NOTE_LIKE = "CountNoteLikeTopic";

    /**
     * 点赞标签
     */
    String TAG_LIKE = "Like";

    /**
     * 取消点赞标签
     */
    String TAG_UNLIKE = "Unlike";

    /**
     * Topic: 收藏、取消收藏共用一个
     */
    String TOPIC_COLLECT_OR_UN_COLLECT = "CollectUnCollectTopic";

    /**
     * Tag 标签：收藏
     */
    String TAG_COLLECT = "Collect";

    /**
     * Tag 标签：取消收藏
     */
    String TAG_UN_COLLECT = "UnCollect";

    /**
     * Topic: 计数 - 笔记收藏数
     */
    String TOPIC_COUNT_NOTE_COLLECT = "CountNoteCollectTopic";

    /**
     * Topic: 笔记操作（发布、删除）
     */
    String TOPIC_NOTE_OPERATE = "NoteOperateTopic";

    String TAG_NOTE_PUBLISH = "publishNote";

    String TAG_NOTE_DELETE = "deleteNote";

}
