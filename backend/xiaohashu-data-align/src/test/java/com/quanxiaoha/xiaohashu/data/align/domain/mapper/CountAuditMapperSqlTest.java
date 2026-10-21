package com.quanxiaoha.xiaohashu.data.align.domain.mapper;

import com.quanxiaoha.xiaohashu.data.align.job.CountAuditMetric;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.io.Resources;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CountAuditMapperSqlTest {

    @Test
    void everyCountMetricUsesItsReviewedSourceRelationAndStatus() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream mapperInput = Resources.getResourceAsStream("mapper/CountAuditMapper.xml")) {
            new XMLMapperBuilder(mapperInput, configuration, "mapper/CountAuditMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        Map<CountAuditMetric, String> expectedSql = Map.of(
                CountAuditMetric.FOLLOWING, "from t_following where user_id = ?",
                CountAuditMetric.FANS, "from t_following where following_user_id = ?",
                CountAuditMetric.NOTE_COLLECT, "from t_note_collection where note_id = ? and status = 1",
                CountAuditMetric.USER_COLLECT, "join t_note n on n.id = c.note_id where n.creator_id = ? and c.status = 1",
                CountAuditMetric.USER_LIKE, "join t_note n on n.id = l.note_id where n.creator_id = ? and l.status = 1",
                CountAuditMetric.NOTE_LIKE, "from t_note_like where note_id = ? and status = 1",
                CountAuditMetric.NOTE_PUBLISH, "from t_note where creator_id = ? and status <> 2");

        MappedStatement statement = configuration.getMappedStatement(
                "com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMapper.selectSourceCount");
        for (Map.Entry<CountAuditMetric, String> expected : expectedSql.entrySet()) {
            BoundSql boundSql = statement.getBoundSql(Map.of("metric", expected.getKey().name(), "entityId", 123L));
            assertThat(boundSql.getSql().replaceAll("\\s+", " ")).contains(expected.getValue());
        }
    }

    @Test
    void everyCountMetricLocksAndUpdatesOnlyItsStoredCounterColumn() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream mapperInput = Resources.getResourceAsStream("mapper/CountAuditMapper.xml")) {
            new XMLMapperBuilder(mapperInput, configuration, "mapper/CountAuditMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        Map<CountAuditMetric, String> expectedSql = Map.of(
                CountAuditMetric.FOLLOWING, "update t_user_count set following_total = ? where user_id = ?",
                CountAuditMetric.FANS, "update t_user_count set fans_total = ? where user_id = ?",
                CountAuditMetric.NOTE_COLLECT, "update t_note_count set collect_total = ? where note_id = ?",
                CountAuditMetric.USER_COLLECT, "update t_user_count set collect_total = ? where user_id = ?",
                CountAuditMetric.USER_LIKE, "update t_user_count set like_total = ? where user_id = ?",
                CountAuditMetric.NOTE_LIKE, "update t_note_count set like_total = ? where note_id = ?",
                CountAuditMetric.NOTE_PUBLISH, "update t_user_count set note_total = ? where user_id = ?");
        MappedStatement statement = configuration.getMappedStatement(
                "com.quanxiaoha.xiaohashu.data.align.domain.mapper.CountAuditMapper.updateStoredCount");

        for (Map.Entry<CountAuditMetric, String> expected : expectedSql.entrySet()) {
            BoundSql boundSql = statement.getBoundSql(Map.of(
                    "metric", expected.getKey().name(), "entityId", 123L, "total", 7L));
            assertThat(boundSql.getSql().replaceAll("\\s+", " ")).contains(expected.getValue());
        }
    }
}
