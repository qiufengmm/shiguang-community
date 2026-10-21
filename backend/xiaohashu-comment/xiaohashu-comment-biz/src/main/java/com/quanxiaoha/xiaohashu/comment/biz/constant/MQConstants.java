package com.quanxiaoha.xiaohashu.comment.biz.constant;

public interface MQConstants {

    String TOPIC_PUBLISH_COMMENT = "PublishCommentTopic";

    String TOPIC_COUNT_NOTE_COMMENT = "CountNoteCommentTopic";

    String TOPIC_COMMENT_HEAT_UPDATE = "CommentHeatUpdateTopic";

    String TOPIC_COMMENT_LIKE_OR_UNLIKE = "CommentLikeUnlikeTopic";

    String TOPIC_DELETE_COMMENT_LOCAL_CACHE = "DeleteCommentDetailLocalCacheTopic";

    String TOPIC_DELETE_COMMENT = "DeleteCommentTopic";

    String TOPIC_COMMENT_CACHE_INVALIDATE = "CommentCacheInvalidateTopic";

    String TAG_LIKE = "Like";

    String TAG_UNLIKE = "UnLike";
}
