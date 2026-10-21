package com.quanxiaoha.xiaohashu.search.biz.domain.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.io.Resources;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SelectMapperNoteIndexSqlTest {

    @Test
    void mapsCanalNoteFieldsToTheElasticsearchNoteIndexNames() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream mapperInput = Resources.getResourceAsStream("mapper/SelectMapper.xml")) {
            new XMLMapperBuilder(mapperInput, configuration, "mapper/SelectMapper.xml",
                    configuration.getSqlFragments()).parse();
        }

        MappedStatement statement = configuration.getMappedStatement(
                "com.quanxiaoha.xiaohashu.search.biz.domain.mapper.SelectMapper.selectEsNoteIndexData");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("noteId", 1L);
        parameters.put("userId", null);
        BoundSql boundSql = statement.getBoundSql(parameters);
        String sql = boundSql.getSql().replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("SUBSTRING_INDEX(n.img_uris, ',', 1) AS cover")
                .contains("u.nickname AS creator_nickname")
                .contains("u.avatar AS creator_avatar");
    }
}
