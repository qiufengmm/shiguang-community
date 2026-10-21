package com.quanxiaoha.xiaohashu.user.relation.biz.consumer;

import com.google.common.util.concurrent.RateLimiter;
import com.quanxiaoha.framework.common.util.DateUtils;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.user.relation.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.user.relation.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.FansDO;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.FollowingDO;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.FansDOMapper;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.FollowingDOMapper;
import com.quanxiaoha.xiaohashu.user.relation.biz.enums.FollowUnfollowTypeEnum;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.CountFollowUnfollowMqDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.FollowUserMqDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.UnfollowUserMqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Objects;

/**
 * 关注、取关 MQ 消费者
 **/
@Component
@RocketMQMessageListener(consumerGroup = "xiaohashu_group_" + MQConstants.TOPIC_FOLLOW_OR_UNFOLLOW, // Group 组
        topic = MQConstants.TOPIC_FOLLOW_OR_UNFOLLOW, // 消费的主题 Topic
        consumeMode = ConsumeMode.ORDERLY // 顺序消费模式
)
@Slf4j
public class FollowUnfollowConsumer implements RocketMQListener<Message> {

    @Resource
    private FollowingDOMapper followingDOMapper;
    @Resource
    private FansDOMapper fansDOMapper;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private RateLimiter rateLimiter;
    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Override
    public void onMessage(Message message) {
        // 流量削峰：通过获取令牌，如果没有令牌可用，将阻塞，直到获得
        rateLimiter.acquire();

        // 消息体
        String bodyJsonStr = new String(message.getBody());
        // 标签
        String tags = message.getTags();

        log.info("==> FollowUnfollowConsumer 消费了消息 {}, tags: {}", bodyJsonStr, tags);

        // 根据 MQ 标签，判断操作类型
        if (Objects.equals(tags, MQConstants.TAG_FOLLOW)) { // 关注
            handleFollowTagMessage(bodyJsonStr);
        } else if (Objects.equals(tags, MQConstants.TAG_UNFOLLOW)) { // 取关
            handleUnfollowTagMessage(bodyJsonStr);
        }
    }

    /**
     * 关注
     * @param bodyJsonStr
     */
    private void handleFollowTagMessage(String bodyJsonStr) {
        // 将消息体 Json 字符串转为 DTO 对象
        FollowUserMqDTO followUserMqDTO = JsonUtils.parseObject(bodyJsonStr, FollowUserMqDTO.class);

        if (Objects.isNull(followUserMqDTO)) throw new IllegalArgumentException("Invalid follow event");

        // 幂等性：通过联合唯一索引保证

        Long userId = followUserMqDTO.getUserId();
        Long followUserId = followUserMqDTO.getFollowUserId();
        LocalDateTime createTime = followUserMqDTO.getCreateTime();

        // 编程式提交事务
        boolean isSuccess = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            followingDOMapper.insert(FollowingDO.builder()
                    .userId(userId)
                    .followingUserId(followUserId)
                    .createTime(createTime)
                    .build());
            fansDOMapper.insert(FansDO.builder()
                    .userId(followUserId)
                    .fansUserId(userId)
                    .createTime(createTime)
                    .build());
            return true;
        }));

        log.info("## 数据库添加记录结果：{}", isSuccess);

        // 若数据库操作成功，更新 Redis 中被关注用户的 ZSet 粉丝列表
        if (!isSuccess) throw new IllegalStateException("Follow relation transaction did not commit");
        {
            // Lua 脚本
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptSource(new ResourceScriptSource(new ClassPathResource("/lua/follow_check_and_update_fans_zset.lua")));
            script.setResultType(Long.class);

            // 时间戳
            long timestamp = DateUtils.localDateTime2Timestamp(createTime);

            // 构建被关注用户的粉丝列表 Redis Key
            String fansRedisKey = RedisKeyConstants.buildUserFansKey(followUserId);
            // 执行脚本
            redisTemplate.execute(script, Collections.singletonList(fansRedisKey), userId, timestamp);

            // 发送 MQ 通知计数服务
            CountFollowUnfollowMqDTO countFollowUnfollowMqDTO = CountFollowUnfollowMqDTO.builder()
                    .userId(userId)
                    .targetUserId(followUserId)
                    .type(FollowUnfollowTypeEnum.FOLLOW.getCode())
                    .build();
            sendMQ(countFollowUnfollowMqDTO);
        }
    }

    /**
     * 取关
     * @param bodyJsonStr
     */
    private void handleUnfollowTagMessage(String bodyJsonStr) {
        // 将消息体 Json 字符串转为 DTO 对象
        UnfollowUserMqDTO unfollowUserMqDTO = JsonUtils.parseObject(bodyJsonStr, UnfollowUserMqDTO.class);

        if (Objects.isNull(unfollowUserMqDTO)) throw new IllegalArgumentException("Invalid unfollow event");

        Long userId = unfollowUserMqDTO.getUserId();
        Long unfollowUserId = unfollowUserMqDTO.getUnfollowUserId();
        LocalDateTime createTime = unfollowUserMqDTO.getCreateTime();

        // 编程式提交事务
        boolean isSuccess = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            int count = followingDOMapper.deleteByUserIdAndFollowingUserId(userId, unfollowUserId);
            if (count > 0) {
                fansDOMapper.deleteByUserIdAndFansUserId(unfollowUserId, userId);
            }
            return true;
        }));

        // 若数据库删除成功，更新 Redis，将自己从被取关用户的 ZSet 粉丝列表删除
        if (!isSuccess) throw new IllegalStateException("Unfollow relation transaction did not commit");
        {
            // 被取关用户的粉丝列表 Redis Key
            String fansRedisKey = RedisKeyConstants.buildUserFansKey(unfollowUserId);
            // 删除指定粉丝
            redisTemplate.opsForZSet().remove(fansRedisKey, userId);

            // 发送 MQ 通知计数服务
            CountFollowUnfollowMqDTO countFollowUnfollowMqDTO = CountFollowUnfollowMqDTO.builder()
                    .userId(userId)
                    .targetUserId(unfollowUserId)
                    .type(FollowUnfollowTypeEnum.UNFOLLOW.getCode())
                    .build();
            sendMQ(countFollowUnfollowMqDTO);
        }
    }

    /**
     * 发送 MQ 通知计数服务
     */
    void sendMQ(CountFollowUnfollowMqDTO countFollowUnfollowMqDTO) {
        org.springframework.messaging.Message<String> message = MessageBuilder.withPayload(JsonUtils.toJsonString(countFollowUnfollowMqDTO))
                .build();
        rocketMQTemplate.syncSend(MQConstants.TOPIC_COUNT_FOLLOWING, message);
        rocketMQTemplate.syncSend(MQConstants.TOPIC_COUNT_FANS, message);
    }
}
