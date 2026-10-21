package com.quanxiaoha.xiaohashu.count.biz.domain.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 计数二级消费者的消息幂等日志。
 * <p>
 * 关键点：登记与增量写库必须跑在<b>同一个本地事务</b>里。这样无论进程在任何时刻挂掉，
 * 「登记」和「累加」要么一起提交、要么一起回滚，不会出现“登记了但没落库”的空窗。
 * <p>
 * 过期数据的清理放在 data-align 的 {@code deleteTableJobHandler} 里做，
 * 这里只负责写入与判重。
 */
public interface CountConsumeLogMapper {

    /**
     * 登记一条已消费的消息。主键冲突（即重复投递）时返回 0。
     */
    int insertIgnore(@Param("msgId") String msgId,
                     @Param("topic") String topic,
                     @Param("consumerGroup") String consumerGroup);
}
