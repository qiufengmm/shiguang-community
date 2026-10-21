package com.quanxiaoha.xiaohashu.count.biz.consumer;

import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

/**
 * 二级消费者测试的公共脚手架。
 * <p>
 * 这些消费者把「幂等登记 + 增量累加」包在 {@link TransactionTemplate} 里，
 * 单元测试不需要真实事务管理器，用一个直接执行回调的模板即可，
 * 这样测试仍然验证的是「同一段代码顺序执行」，只是省掉了 begin/commit 的开销。
 */
final class CountConsumerTestSupport {

    private CountConsumerTestSupport() {
    }

    static TransactionTemplate passThroughTransactionTemplate() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    static MessageExt message(String msgId, String body) {
        MessageExt messageExt = new MessageExt();
        messageExt.setMsgId(msgId);
        messageExt.setBody(body.getBytes(StandardCharsets.UTF_8));
        return messageExt;
    }
}
