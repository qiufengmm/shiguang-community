package com.quanxiaoha.xiaohashu.user.biz.domain.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDOMapperXmlTest {

    @Test
    void mapperXmlRegistersGeneratedStatements() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/UserDOMapper.xml";

        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments()).parse();
        }

        assertTrue(configuration.hasStatement(UserDOMapper.class.getName() + ".selectByPrimaryKey"));
        assertTrue(configuration.hasStatement(UserDOMapper.class.getName() + ".selectByPhone"));
        assertTrue(configuration.hasStatement(UserDOMapper.class.getName() + ".updateByPrimaryKeySelective"));
    }
}
