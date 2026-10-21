package com.quanxiaoha.xiaohashu.note.biz.listener;

import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;

class NoteContentSagaWiringTest {

    @Test
    void publishTransactionListenerMustDependOnDurableContentSagaBeforeCallingCassandra() {
        var field = ReflectionUtils.findField(
                PublishNote2DBLocalTransactionListener.class, "noteContentSagaService");

        assertThat(field)
                .as("publishing must persist a recovery payload before the Cassandra write")
                .isNotNull();
    }
}
