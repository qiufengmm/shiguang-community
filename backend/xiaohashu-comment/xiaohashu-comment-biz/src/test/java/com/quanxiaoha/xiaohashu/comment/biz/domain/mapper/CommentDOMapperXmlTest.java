package com.quanxiaoha.xiaohashu.comment.biz.domain.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentDOMapperXmlTest {

    @Test
    void selectByPrimaryKeyIncludesFirstReplyCommentIdForDeleteProcessing() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream inputStream = Resources.getResourceAsStream("mapper/CommentDOMapper.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "mapper/CommentDOMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        String sql = configuration.getMappedStatement(
                "com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper.selectByPrimaryKey")
                .getBoundSql(1L)
                .getSql();

        assertTrue(sql.contains("first_reply_comment_id"), sql);
    }

    @Test
    void selectByCommentIdsIncludesReplyTargetDetailsForCacheReconstruction() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream inputStream = Resources.getResourceAsStream("mapper/CommentDOMapper.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "mapper/CommentDOMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        String sql = configuration.getMappedStatement(
                        "com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper.selectByCommentIds")
                .getBoundSql(java.util.Map.of("commentIds", java.util.List.of(1L)))
                .getSql().toLowerCase();

        assertTrue(sql.contains("reply_comment_id"), sql);
        assertTrue(sql.contains("reply_user_id"), sql);
    }

    @Test
    void childCommentCacheAndSqlQueriesUseTheSameStableIdOrder() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream inputStream = Resources.getResourceAsStream("mapper/CommentDOMapper.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "mapper/CommentDOMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        String pageSql = configuration.getMappedStatement(
                        "com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper.selectChildPageList")
                .getBoundSql(java.util.Map.of("parentId", 1L, "offset", 0L, "pageSize", 6L))
                .getSql().toLowerCase().replaceAll("\\s+", " ");
        String cacheBuildSql = configuration.getMappedStatement(
                        "com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper.selectChildCommentsByParentIdAndLimit")
                .getBoundSql(java.util.Map.of("parentId", 1L, "limit", 60))
                .getSql().toLowerCase().replaceAll("\\s+", " ");

        assertTrue(pageSql.contains("order by id asc"), pageSql);
        assertTrue(cacheBuildSql.contains("order by id asc"), cacheBuildSql);
    }

    @Test
    void batchInsertIgnoresDuplicateCommentIds() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream inputStream = Resources.getResourceAsStream("mapper/CommentDOMapper.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "mapper/CommentDOMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        String sql = configuration.getMappedStatement(
                        "com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper.batchInsert")
                .getBoundSql(java.util.Map.of("commentDOS", java.util.List.of(
                        com.quanxiaoha.xiaohashu.comment.biz.domain.dataobject.CommentDO.builder()
                                .id(1L).build())))
                .getSql();

        assertTrue(sql.toLowerCase().contains("insert ignore into t_comment"), sql);
    }

    @Test
    void rootCommentSqlUsesStableHeatThenCommentIdOrdering() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream inputStream = Resources.getResourceAsStream("mapper/CommentDOMapper.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "mapper/CommentDOMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        String pageSql = configuration.getMappedStatement(
                        "com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper.selectPageList")
                .getBoundSql(java.util.Map.of("noteId", 100L, "offset", 0L, "pageSize", 10L))
                .getSql().toLowerCase().replaceAll("\\s+", " ");
        String hotSql = configuration.getMappedStatement(
                        "com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper.selectHeatComments")
                .getBoundSql(100L).getSql().toLowerCase().replaceAll("\\s+", " ");

        assertTrue(pageSql.contains("order by heat desc, id desc"), pageSql);
        assertTrue(hotSql.contains("order by heat desc, id desc"), hotSql);
    }

    @Test
    void deleteTreeQueryAnchorsOrphanRepliesWhenRootIsMissing() throws Exception {
        String sql = deleteTreeSql(123L).toLowerCase().replaceAll("\\s+", " ");

        assertTrue(sql.contains("where id = ? or (`level` = 2 and parent_id = ?)"), sql);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "XIAOHASHU_TEST_MYSQL_URL", matches = ".+")
    void deleteTreeQueryFindsOrphanRepliesInMySql() throws Exception {
        long rootId = 4_000_000_000_000_000L + java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(100_000_000L);
        long firstReplyId = rootId + 1;
        long secondReplyId = rootId + 2;
        String tableName = "comment_delete_tree_sandbox_"
                + Long.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextLong(), 16);

        // Opt-in integration check: only a random sandbox table is created and dropped.
        try (Connection connection = DriverManager.getConnection(
                System.getenv("XIAOHASHU_TEST_MYSQL_URL"),
                System.getenv("XIAOHASHU_TEST_MYSQL_USER"),
                System.getenv("XIAOHASHU_TEST_MYSQL_PASSWORD"));
             Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TABLE " + tableName + " ("
                    + "id BIGINT NOT NULL PRIMARY KEY, note_id BIGINT, user_id BIGINT, "
                    + "content_uuid VARCHAR(64), is_content_empty BIT, image_url VARCHAR(255), "
                    + "`level` TINYINT, reply_total BIGINT, like_total BIGINT, parent_id BIGINT, "
                    + "reply_comment_id BIGINT, reply_user_id BIGINT, is_top TINYINT, "
                    + "create_time DATETIME, update_time DATETIME, first_reply_comment_id BIGINT) ENGINE=InnoDB");
            try {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + tableName
                                + " (id, note_id, user_id, `level`, parent_id, reply_comment_id) "
                                + "VALUES (?, 1, 1, 2, ?, ?)")) {
                    insert.setLong(1, firstReplyId);
                    insert.setLong(2, rootId);
                    insert.setLong(3, rootId);
                    insert.executeUpdate();

                    insert.setLong(1, secondReplyId);
                    insert.setLong(2, rootId);
                    insert.setLong(3, firstReplyId);
                    insert.executeUpdate();
                }

                String sql = deleteTreeSql(rootId).replace("t_comment", tableName);
                try (PreparedStatement query = connection.prepareStatement(sql)) {
                    query.setLong(1, rootId);
                    query.setLong(2, rootId);
                    try (ResultSet results = query.executeQuery()) {
                        Set<Long> actualIds = new HashSet<>();
                        while (results.next()) {
                            actualIds.add(results.getLong("id"));
                        }
                        assertEquals(Set.of(firstReplyId, secondReplyId), actualIds);
                    }
                }
            } finally {
                ddl.execute("DROP TABLE " + tableName);
            }
        }
    }

    private static String deleteTreeSql(long commentId) throws Exception {
        return deleteTreeBoundSql(commentId).getSql();
    }

    private static org.apache.ibatis.mapping.BoundSql deleteTreeBoundSql(long commentId) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream inputStream = Resources.getResourceAsStream("mapper/CommentDOMapper.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "mapper/CommentDOMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(
                        "com.quanxiaoha.xiaohashu.comment.biz.domain.mapper.CommentDOMapper.selectDeleteTreeByRootCommentId")
                .getBoundSql(commentId);
    }
}
