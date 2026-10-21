package com.quanxiaoha.xiaohashu.count.biz;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
@MapperScan("com.quanxiaoha.xiaohashu.count.biz.domain.mapper")
public class XiaohashuCountBizApplication {

    public static void main(String[] args) {
        SpringApplication.run(XiaohashuCountBizApplication.class, args);
        // initFlowRules();
    }

    /**
     * 初始化限流规则
     */
    private static void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        FlowRule rule = new FlowRule();
        rule.setResource("findUserCountData");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(5);

        rules.add(rule);
        FlowRuleManager.loadRules(rules);
    }

}
