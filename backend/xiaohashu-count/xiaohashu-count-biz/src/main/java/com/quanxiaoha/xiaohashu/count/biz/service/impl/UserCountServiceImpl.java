package com.quanxiaoha.xiaohashu.count.biz.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import cn.hutool.core.util.RandomUtil;
import com.google.common.collect.Maps;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.UserCountDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.UserCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.service.UserCountService;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 用户计数业务
 **/
@Service
@Slf4j
public class UserCountServiceImpl implements UserCountService {

    @Resource
    private UserCountDOMapper userCountDOMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource(name = "taskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /**
     * 查询用户相关计数
     */
    @Override
    @SentinelResource(value = "findUserCountData", blockHandler = "blockHandler4findUserCountData")
    public Response<FindUserCountsByIdRspDTO> findUserCountData(FindUserCountsByIdReqDTO findUserCountsByIdReqDTO) {
        Long userId = findUserCountsByIdReqDTO.getUserId();

        FindUserCountsByIdRspDTO responseDTO = FindUserCountsByIdRspDTO.builder()
                .userId(userId)
                .build();

        String userCountHashKey = RedisKeyConstants.buildCountUserKey(userId);
        List<Object> counts = redisTemplate.opsForHash().multiGet(userCountHashKey, List.of(
                RedisKeyConstants.FIELD_COLLECT_TOTAL,
                RedisKeyConstants.FIELD_FANS_TOTAL,
                RedisKeyConstants.FIELD_NOTE_TOTAL,
                RedisKeyConstants.FIELD_FOLLOWING_TOTAL,
                RedisKeyConstants.FIELD_LIKE_TOTAL));

        Object collectTotal = counts.get(0);
        Object fansTotal = counts.get(1);
        Object noteTotal = counts.get(2);
        Object followingTotal = counts.get(3);
        Object likeTotal = counts.get(4);

        responseDTO.setCollectTotal(Objects.isNull(collectTotal) ? 0L : Long.parseLong(String.valueOf(collectTotal)));
        responseDTO.setFansTotal(Objects.isNull(fansTotal) ? 0L : Long.parseLong(String.valueOf(fansTotal)));
        responseDTO.setNoteTotal(Objects.isNull(noteTotal) ? 0L : Long.parseLong(String.valueOf(noteTotal)));
        responseDTO.setFollowingTotal(Objects.isNull(followingTotal) ? 0L : Long.parseLong(String.valueOf(followingTotal)));
        responseDTO.setLikeTotal(Objects.isNull(likeTotal) ? 0L : Long.parseLong(String.valueOf(likeTotal)));

        boolean isAnyNull = counts.stream().anyMatch(Objects::isNull);
        if (isAnyNull) {
            UserCountDO userCountDO = userCountDOMapper.selectByUserId(userId);

            if (Objects.nonNull(userCountDO) && Objects.isNull(collectTotal)) {
                responseDTO.setCollectTotal(userCountDO.getCollectTotal());
            }
            if (Objects.nonNull(userCountDO) && Objects.isNull(fansTotal)) {
                responseDTO.setFansTotal(userCountDO.getFansTotal());
            }
            if (Objects.nonNull(userCountDO) && Objects.isNull(noteTotal)) {
                responseDTO.setNoteTotal(userCountDO.getNoteTotal());
            }
            if (Objects.nonNull(userCountDO) && Objects.isNull(followingTotal)) {
                responseDTO.setFollowingTotal(userCountDO.getFollowingTotal());
            }
            if (Objects.nonNull(userCountDO) && Objects.isNull(likeTotal)) {
                responseDTO.setLikeTotal(userCountDO.getLikeTotal());
            }

            syncHashCount2Redis(userCountHashKey, userCountDO,
                    collectTotal, fansTotal, noteTotal, followingTotal, likeTotal);
        }

        return Response.success(responseDTO);
    }

    /**
     * 资源被限流、降级或系统保护时的兜底处理
     */
    public Response<FindUserCountsByIdRspDTO> blockHandler4findUserCountData(
            FindUserCountsByIdReqDTO findUserCountsByIdReqDTO, BlockException blockException) {
        log.warn("## findUserCountData() 方法被限流: {}", JsonUtils.toJsonString(findUserCountsByIdReqDTO));

        return Response.success(FindUserCountsByIdRspDTO.builder()
                .userId(findUserCountsByIdReqDTO.getUserId())
                .collectTotal(0L)
                .fansTotal(0L)
                .followingTotal(0L)
                .likeTotal(0L)
                .noteTotal(0L)
                .build());
    }

    /**
     * 将该用户的 Hash 计数同步到 Redis 中
     */
    private void syncHashCount2Redis(String userCountHashKey, UserCountDO userCountDO,
                                     Object collectTotal, Object fansTotal, Object noteTotal,
                                     Object followingTotal, Object likeTotal) {
        if (Objects.nonNull(userCountDO)) {
            threadPoolTaskExecutor.submit(() -> {
                Map<String, Long> userCountMap = Maps.newHashMap();
                if (Objects.isNull(collectTotal)) {
                    userCountMap.put(RedisKeyConstants.FIELD_COLLECT_TOTAL,
                            Objects.isNull(userCountDO.getCollectTotal()) ? 0L : userCountDO.getCollectTotal());
                }
                if (Objects.isNull(fansTotal)) {
                    userCountMap.put(RedisKeyConstants.FIELD_FANS_TOTAL,
                            Objects.isNull(userCountDO.getFansTotal()) ? 0L : userCountDO.getFansTotal());
                }
                if (Objects.isNull(noteTotal)) {
                    userCountMap.put(RedisKeyConstants.FIELD_NOTE_TOTAL,
                            Objects.isNull(userCountDO.getNoteTotal()) ? 0L : userCountDO.getNoteTotal());
                }
                if (Objects.isNull(followingTotal)) {
                    userCountMap.put(RedisKeyConstants.FIELD_FOLLOWING_TOTAL,
                            Objects.isNull(userCountDO.getFollowingTotal()) ? 0L : userCountDO.getFollowingTotal());
                }
                if (Objects.isNull(likeTotal)) {
                    userCountMap.put(RedisKeyConstants.FIELD_LIKE_TOTAL,
                            Objects.isNull(userCountDO.getLikeTotal()) ? 0L : userCountDO.getLikeTotal());
                }

                redisTemplate.executePipelined(new SessionCallback<>() {
                    @Override
                    public Object execute(RedisOperations operations) {
                        operations.opsForHash().putAll(userCountHashKey, userCountMap);
                        long expireTime = 60 * 60 + RandomUtil.randomInt(60 * 60);
                        operations.expire(userCountHashKey, expireTime, TimeUnit.SECONDS);
                        return null;
                    }
                });
            });
        }
    }
}
