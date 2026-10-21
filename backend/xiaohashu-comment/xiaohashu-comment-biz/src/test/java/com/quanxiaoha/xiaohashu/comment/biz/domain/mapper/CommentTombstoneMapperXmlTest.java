package com.quanxiaoha.xiaohashu.comment.biz.domain.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentTombstoneMapperXmlTest {

    @Test
    void pendingContentCleanupWaitsUntilDeleteInboxIsApplied() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream inputStream = Resources.getResourceAsStream("mapper/CommentTombstoneMapper.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "mapper/CommentTombstoneMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        String sql = configuration.getMappedStatement(
                        "com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentTombstoneMapper.selectPendingContentCleanup")
                .getBoundSql(Map.of("limit", 100))
                .getSql().toLowerCase().replaceAll("\\s+", " ");

        assertTrue(sql.contains("t_comment_inbox"), sql);
        assertTrue(sql.contains("comment-delete:"), sql);
        assertTrue(sql.contains("event_type = 'delete'"), sql);
        assertTrue(sql.contains("status = 'applied'"), sql);
    }
}
