package com.quanxiaoha.xiaohashu.user.biz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@MapperScan("com.quanxiaoha.xiaohashu.user.biz.domain.mapper")
@EnableFeignClients(basePackages = "com.quanxiaoha.xiaohashu")
@ComponentScan({"com.quanxiaoha.xiaohashu.user.biz", "com.quanxiaoha.xiaohashu.count"})
public class XiaohashuUserBizApplication {

    public static void main(String[] args) {
        SpringApplication.run(XiaohashuUserBizApplication.class, args);
    }
}
