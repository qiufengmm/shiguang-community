package com.quanxiaoha.xiaohashu.auth.sms;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aliyun.dypnsapi20170525.Client;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class AliyunSmsHelperLoggingTest {

    @Test
    void failedSendDoesNotWritePhoneNumberOrVerificationCodeToLogs() throws Exception {
        Client client = mock(Client.class);
        doThrow(new IllegalStateException("provider unavailable"))
                .when(client).sendSmsVerifyCodeWithOptions(any(), any());
        AliyunSmsHelper helper = new AliyunSmsHelper();
        ReflectionTestUtils.setField(helper, "client", client);

        Logger logger = (Logger) LoggerFactory.getLogger(AliyunSmsHelper.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(helper.sendMessage("test-sign", "test-template", "13812345678", "{\"code\":\"764291\"}"))
                    .isFalse();

            String logText = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logText).doesNotContain("13812345678", "764291");
            assertThat(logText).contains("138****5678");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
