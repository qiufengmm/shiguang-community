package com.quanxiaoha.xiaohashu.user.biz.domain.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRoleMapperXmlTest {

    @Test
    void allUserRoleAndPermissionMapperXmlFilesLoad() throws Exception {
        Configuration configuration = new Configuration();
        for (String resource : new String[]{
                "mapper/PermissionDOMapper.xml",
                "mapper/RoleDOMapper.xml",
                "mapper/RolePermissionDOMapper.xml",
                "mapper/UserRoleDOMapper.xml"}) {
            try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }

        assertTrue(configuration.hasStatement(RoleDOMapper.class.getName() + ".selectEnabledList"));
        assertTrue(configuration.hasStatement(RolePermissionDOMapper.class.getName() + ".selectByRoleIds"));
        assertTrue(configuration.hasStatement(PermissionDOMapper.class.getName() + ".selectAppEnabledList"));
        assertTrue(configuration.hasStatement(UserRoleDOMapper.class.getName() + ".insert"));
    }
}
