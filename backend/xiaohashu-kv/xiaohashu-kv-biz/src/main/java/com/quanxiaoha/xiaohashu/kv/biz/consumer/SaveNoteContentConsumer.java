package com.quanxiaoha.xiaohashu.kv.biz.consumer;

import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.kv.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.kv.biz.dto.PublishNoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 事务消息：保存笔记正文
 */
@Component
@RocketMQMessageListener(
        consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_PUBLISH_NOTE_TRANSACTION,
        topic = MQConstants.TOPIC_PUBLISH_NOTE_TRANSACTION)
@Slf4j
public class SaveNoteContentConsumer implements RocketMQListener<Message> {

    @Override
    public void onMessage(Message message) {
        String bodyJsonStr = new String(message.getBody());
        if (StringUtils.isNotBlank(bodyJsonStr)) {
            PublishNoteDTO publishNoteDTO = JsonUtils.parseObject(bodyJsonStr, PublishNoteDTO.class);
            // 正文已由 note 服务在提交 MySQL 元数据前同步写入。
            // 延迟到达的事务消息只做确认，不能覆盖编辑后的内容或复活已清空的正文。
            log.info("## 确认笔记发布事务消息，noteId: {}", publishNoteDTO.getId());
        }
    }
}
