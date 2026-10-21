package com.quanxiaoha.xiaohashu.count.biz.domain.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommentCountEventMapperXmlTest {

    @Test
    void countStateSqlUsesTheEventAndCounterAsItsStableKey() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream inputStream = Resources.getResourceAsStream("mapper/CommentCountEventMapper.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "mapper/CommentCountEventMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        String selectSql = configuration.getMappedStatement(
                        "com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentCountEventMapper.selectAppliedDeltaForUpdate")
                .getBoundSql(Map.of("eventId", "comment-like-state:8:900", "counterType", "COMMENT_LIKE_STATE"))
                .getSql().toLowerCase().replaceAll("\\s+", " ");
        String updateSql = configuration.getMappedStatement(
                        "com.quanxiaoha.xiaohashu.count.biz.domain.mapper.CommentCountEventMapper.updateAppliedDelta")
                .getBoundSql(Map.of("eventId", "comment-like-state:8:900", "counterType", "COMMENT_LIKE_STATE",
                        "appliedDelta", 1))
                .getSql().toLowerCase().replaceAll("\\s+", " ");

        assertThat(selectSql).contains("where event_id = ? and counter_type = ? for update");
        assertThat(updateSql).contains("set applied_delta = ? where event_id = ? and counter_type = ?");
    }
}
