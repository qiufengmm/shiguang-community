package com.quanxiaoha.xiaohashu.count.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.NoteCountDO;
import com.quanxiaoha.xiaohashu.count.biz.domain.mapper.NoteCountDOMapper;
import com.quanxiaoha.xiaohashu.count.biz.service.NoteCountService;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.dto.FindNoteCountsByIdsReqDTO;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class NoteCountServiceImpl implements NoteCountService {

    @Resource
    private NoteCountDOMapper noteCountDOMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public Response<List<FindNoteCountsByIdRspDTO>> findNotesCountData(FindNoteCountsByIdsReqDTO request) {
        List<Long> noteIds = request.getNoteIds();

        // 1. 先通过 Pipeline 批量查询 Redis 中的笔记计数
        List<String> hashKeys = noteIds.stream()
                .map(RedisKeyConstants::buildCountNoteKey)
                .toList();
        List<Object> countHashes = getCountHashesByPipelineFromRedis(hashKeys);

        List<FindNoteCountsByIdRspDTO> responseDTOs = Lists.newArrayList();
        List<Long> noteIdsNeedQuery = Lists.newArrayList();

        for (int i = 0; i < noteIds.size(); i++) {
            Long noteId = noteIds.get(i);
            @SuppressWarnings("unchecked")
            List<Object> countHash = (List<Object>) countHashes.get(i);

            Object likeTotal = countHash.get(0);
            Object collectTotal = countHash.get(1);
            Object commentTotal = countHash.get(2);

            if (Objects.isNull(likeTotal) || Objects.isNull(collectTotal) || Objects.isNull(commentTotal)) {
                noteIdsNeedQuery.add(noteId);
            }

            responseDTOs.add(FindNoteCountsByIdRspDTO.builder()
                    .noteId(noteId)
                    .likeTotal(toLong(likeTotal))
                    .collectTotal(toLong(collectTotal))
                    .commentTotal(toLong(commentTotal))
                    .build());
        }

        if (CollUtil.isEmpty(noteIdsNeedQuery)) {
            return Response.success(responseDTOs);
        }

        // 2. 缓存字段缺失时，批量查询数据库
        List<NoteCountDO> noteCountDOs = noteCountDOMapper.selectByNoteIds(noteIdsNeedQuery);
        if (CollUtil.isNotEmpty(noteCountDOs)) {
            Map<Long, NoteCountDO> noteIdAndDOMap = noteCountDOs.stream()
                    .collect(Collectors.toMap(NoteCountDO::getNoteId, noteCountDO -> noteCountDO));

            syncNoteHash2Redis(responseDTOs, noteIdAndDOMap);

            for (FindNoteCountsByIdRspDTO responseDTO : responseDTOs) {
                Long noteId = responseDTO.getNoteId();
                NoteCountDO noteCountDO = noteIdAndDOMap.get(noteId);
                if (Objects.isNull(responseDTO.getLikeTotal())) {
                    responseDTO.setLikeTotal(Objects.nonNull(noteCountDO) ? noteCountDO.getLikeTotal() : 0L);
                }
                if (Objects.isNull(responseDTO.getCollectTotal())) {
                    responseDTO.setCollectTotal(Objects.nonNull(noteCountDO) ? noteCountDO.getCollectTotal() : 0L);
                }
                if (Objects.isNull(responseDTO.getCommentTotal())) {
                    responseDTO.setCommentTotal(Objects.nonNull(noteCountDO) ? noteCountDO.getCommentTotal() : 0L);
                }
            }
        }

        return Response.success(responseDTOs);
    }

    private List<Object> getCountHashesByPipelineFromRedis(List<String> hashKeys) {
        return redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                for (String hashKey : hashKeys) {
                    operations.opsForHash().multiGet(hashKey, List.of(
                            RedisKeyConstants.FIELD_LIKE_TOTAL,
                            RedisKeyConstants.FIELD_COLLECT_TOTAL,
                            RedisKeyConstants.FIELD_COMMENT_TOTAL));
                }
                return null;
            }
        });
    }

    private void syncNoteHash2Redis(List<FindNoteCountsByIdRspDTO> responseDTOs,
                                    Map<Long, NoteCountDO> noteIdAndDOMap) {
        redisTemplate.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(RedisOperations operations) {
                for (FindNoteCountsByIdRspDTO responseDTO : responseDTOs) {
                    Long likeTotal = responseDTO.getLikeTotal();
                    Long collectTotal = responseDTO.getCollectTotal();
                    Long commentTotal = responseDTO.getCommentTotal();

                    if (Objects.nonNull(likeTotal) && Objects.nonNull(collectTotal)
                            && Objects.nonNull(commentTotal)) {
                        continue;
                    }

                    Long noteId = responseDTO.getNoteId();
                    String hashKey = RedisKeyConstants.buildCountNoteKey(noteId);
                    NoteCountDO noteCountDO = noteIdAndDOMap.get(noteId);
                    Map<String, Long> countMap = Maps.newHashMap();

                    if (Objects.isNull(likeTotal)) {
                        countMap.put(RedisKeyConstants.FIELD_LIKE_TOTAL,
                                Objects.nonNull(noteCountDO) ? noteCountDO.getLikeTotal() : 0L);
                    }
                    if (Objects.isNull(collectTotal)) {
                        countMap.put(RedisKeyConstants.FIELD_COLLECT_TOTAL,
                                Objects.nonNull(noteCountDO) ? noteCountDO.getCollectTotal() : 0L);
                    }
                    if (Objects.isNull(commentTotal)) {
                        countMap.put(RedisKeyConstants.FIELD_COMMENT_TOTAL,
                                Objects.nonNull(noteCountDO) ? noteCountDO.getCommentTotal() : 0L);
                    }

                    operations.opsForHash().putAll(hashKey, countMap);
                    long expireTime = 60 * 30 + RandomUtil.randomInt(60 * 30);
                    operations.expire(hashKey, expireTime, TimeUnit.SECONDS);
                }
                return null;
            }
        });
    }

    private Long toLong(Object value) {
        return Objects.isNull(value) ? null : Long.parseLong(String.valueOf(value));
    }
}
