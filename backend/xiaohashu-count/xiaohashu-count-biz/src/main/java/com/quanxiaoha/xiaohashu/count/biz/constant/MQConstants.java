package com.quanxiaoha.xiaohashu.count.biz.constant;

/**
 * MQ 常量
 */
public interface MQConstants {

    /**
     * Topic: 关注数计数
     */
    String TOPIC_COUNT_FOLLOWING = "CountFollowingTopic";

    /**
     * Topic: 粉丝数计数
     */
    String TOPIC_COUNT_FANS = "CountFansTopic";

    /**
     * Topic: 粉丝数计数入库
     */
    String TOPIC_COUNT_FANS_2_DB = "CountFans2DBTopic";

    /**
     * Topic: 关注数计数入库
     */
    String TOPIC_COUNT_FOLLOWING_2_DB = "CountFollowing2DBTopic";

    /**
     * Topic: 计数 - 笔记点赞数
     */
    String TOPIC_COUNT_NOTE_LIKE = "CountNoteLikeTopic";

    /**
     * Topic: 笔记点赞、取消点赞
     */
    String TOPIC_LIKE_OR_UNLIKE = "LikeUnlikeTopic";

    /**
     * Topic: 计数 - 笔记点赞数落库
     */
    String TOPIC_COUNT_NOTE_LIKE_2_DB = "CountNoteLike2DBTTopic";

    /**
     * Topic: 计数 - 笔记收藏数
     */
    String TOPIC_COUNT_NOTE_COLLECT = "CountNoteCollectTopic";

    /**
     * Topic: 计数 - 笔记收藏数落库
     */
    String TOPIC_COUNT_NOTE_COLLECT_2_DB = "CountNoteCollect2DBTTopic";

    /**
     * Topic: 笔记操作（发布、删除）
     */
    String TOPIC_NOTE_OPERATE = "NoteOperateTopic";

    String TAG_NOTE_PUBLISH = "publishNote";

    String TAG_NOTE_DELETE = "deleteNote";

    /**
     * Topic: 笔记评论总数计数
     */
    String TOPIC_COUNT_NOTE_COMMENT = "CountNoteCommentTopic";

    /**
     * Topic: 评论热度值更新
     */
    String TOPIC_COMMENT_HEAT_UPDATE = "CommentHeatUpdateTopic";

    /**
     * Topic: 评论点赞、取消点赞
     */
    String TOPIC_COMMENT_LIKE_OR_UNLIKE = "CommentLikeUnlikeTopic";

    /**
     * Topic: 评论点赞数入库
     */
    String TOPIC_COUNT_COMMENT_LIKE_2_DB = "CountCommentLike2DBTTopic";
}
