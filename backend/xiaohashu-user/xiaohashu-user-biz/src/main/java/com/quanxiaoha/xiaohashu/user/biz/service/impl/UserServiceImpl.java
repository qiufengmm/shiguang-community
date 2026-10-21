package com.quanxiaoha.xiaohashu.user.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.enums.DeletedEnum;
import com.quanxiaoha.framework.common.enums.StatusEnum;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.framework.common.util.DateUtils;
import com.quanxiaoha.framework.common.util.NumberUtils;
import com.quanxiaoha.framework.common.util.ParamUtils;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.user.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.user.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.user.biz.constant.RoleConstants;
import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.UserDO;
import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.RoleDO;
import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.UserRoleDO;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.RoleDOMapper;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.UserDOMapper;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.UserRoleDOMapper;
import com.quanxiaoha.xiaohashu.user.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.user.biz.enums.SexEnum;
import com.quanxiaoha.xiaohashu.user.biz.model.vo.UpdateUserInfoReqVO;
import com.quanxiaoha.xiaohashu.user.biz.model.vo.FindUserProfileReqVO;
import com.quanxiaoha.xiaohashu.user.biz.model.vo.FindUserProfileRspVO;
import com.quanxiaoha.xiaohashu.user.biz.rpc.CountRpcService;
import com.quanxiaoha.xiaohashu.user.biz.rpc.DistributedIdGeneratorRpcService;
import com.quanxiaoha.xiaohashu.user.biz.rpc.OssRpcService;
import com.quanxiaoha.xiaohashu.user.biz.service.UserService;
import com.quanxiaoha.xiaohashu.user.dto.req.FindUserByIdReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.FindUsersByIdsReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.RegisterUserReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.FindUserByPhoneReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.UpdateUserPasswordReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByPhoneRspDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    @Resource
    private UserDOMapper userDOMapper;

    @Resource
    private OssRpcService ossRpcService;

    @Resource
    private DistributedIdGeneratorRpcService distributedIdGeneratorRpcService;

    @Resource
    private CountRpcService countRpcService;

    @Resource
    private UserRoleDOMapper userRoleDOMapper;

    @Resource
    private RoleDOMapper roleDOMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "taskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 用户信息本地缓存
     */
    private static final Cache<Long, FindUserByIdRspDTO> LOCAL_CACHE = Caffeine.newBuilder()
            .initialCapacity(10000) // 设置初始容量为 10000 个条目
            .maximumSize(10000) // 设置缓存的最大容量为 10000 个条目
            .expireAfterWrite(1, TimeUnit.HOURS) // 设置缓存条目在写入后 1 小时过期
            .build();

    /**
     * 用户主页信息本地缓存
     */
    private static final Cache<Long, FindUserProfileRspVO> PROFILE_LOCAL_CACHE = Caffeine.newBuilder()
            .initialCapacity(10000)
            .maximumSize(10000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    @Override
    public Response<FindUserProfileRspVO> findUserProfile(FindUserProfileReqVO request) {
        Long userId = request.getUserId();
        if (Objects.isNull(userId)) {
            userId = LoginUserContextHolder.getUserId();
        }

        // 暂时关闭缓存，确保压测时每次都调用计数服务。
        /*
        if (!Objects.equals(userId, LoginUserContextHolder.getUserId())) {
            FindUserProfileRspVO profileLocalCache = PROFILE_LOCAL_CACHE.getIfPresent(userId);
            if (Objects.nonNull(profileLocalCache)) {
                log.info("## 用户主页信息命中本地缓存: {}", JsonUtils.toJsonString(profileLocalCache));
                return Response.success(profileLocalCache);
            }
        }

        String userProfileRedisKey = RedisKeyConstants.buildUserProfileKey(userId);
        String userProfileJson = (String) redisTemplate.opsForValue().get(userProfileRedisKey);
        if (StringUtils.isNotBlank(userProfileJson)) {
            FindUserProfileRspVO profile = JsonUtils.parseObject(userProfileJson, FindUserProfileRspVO.class);
            syncUserProfile2LocalCache(userId, profile);
            authorGetActualCountData(userId, profile);
            return Response.success(profile);
        }
        */

        UserDO userDO = userDOMapper.selectByPrimaryKey(userId);
        if (Objects.isNull(userDO)) {
            throw new BizException(ResponseCodeEnum.USER_NOT_FOUND);
        }

        FindUserProfileRspVO profile = FindUserProfileRspVO.builder()
                .userId(userDO.getId())
                .avatar(userDO.getAvatar())
                .nickname(userDO.getNickname())
                .xiaohashuId(userDO.getXiaohashuId())
                .sex(userDO.getSex())
                .introduction(userDO.getIntroduction())
                .build();

        var birthday = userDO.getBirthday();
        profile.setAge(Objects.isNull(birthday) ? 0 : DateUtils.calculateAge(birthday));

        rpcCountServiceAndSetData(userId, profile);

        return Response.success(profile);
    }

    private void rpcCountServiceAndSetData(Long userId, FindUserProfileRspVO profile) {
        FindUserCountsByIdRspDTO counts = countRpcService.findUserCountById(userId);
        if (Objects.nonNull(counts)) {
            Long likeTotal = Objects.requireNonNullElse(counts.getLikeTotal(), 0L);
            Long collectTotal = Objects.requireNonNullElse(counts.getCollectTotal(), 0L);
            profile.setFansTotal(NumberUtils.formatNumberString(
                    Objects.requireNonNullElse(counts.getFansTotal(), 0L)));
            profile.setFollowingTotal(NumberUtils.formatNumberString(
                    Objects.requireNonNullElse(counts.getFollowingTotal(), 0L)));
            profile.setLikeAndCollectTotal(NumberUtils.formatNumberString(likeTotal + collectTotal));
            profile.setNoteTotal(NumberUtils.formatNumberString(
                    Objects.requireNonNullElse(counts.getNoteTotal(), 0L)));
            profile.setLikeTotal(NumberUtils.formatNumberString(likeTotal));
            profile.setCollectTotal(NumberUtils.formatNumberString(collectTotal));
        }
    }

    private void authorGetActualCountData(Long userId, FindUserProfileRspVO profile) {
        if (Objects.equals(userId, LoginUserContextHolder.getUserId())) {
            rpcCountServiceAndSetData(userId, profile);
        }
    }

    private void syncUserProfile2Redis(String redisKey, FindUserProfileRspVO profile) {
        threadPoolTaskExecutor.submit(() -> {
            long expireSeconds = 60 * 60 + RandomUtil.randomInt(60 * 60);
            redisTemplate.opsForValue().set(redisKey, JsonUtils.toJsonString(profile), expireSeconds, TimeUnit.SECONDS);
        });
    }

    private void syncUserProfile2LocalCache(Long userId, FindUserProfileRspVO profile) {
        threadPoolTaskExecutor.submit(() -> PROFILE_LOCAL_CACHE.put(userId, profile));
    }

    @Override
    public Response<?> updateUserInfo(UpdateUserInfoReqVO request) {
        Long userId = request.getUserId();
        Long loginUserId = LoginUserContextHolder.getUserId();
        if (!Objects.equals(loginUserId, userId)) {
            throw new BizException(ResponseCodeEnum.CANT_UPDATE_OTHER_USER_PROFILE);
        }

        UserDO userDO = new UserDO();
        userDO.setId(userId);
        boolean needUpdate = false;

        String nickname = request.getNickname();
        if (StringUtils.isNotBlank(nickname)) {
            Preconditions.checkArgument(ParamUtils.checkNickname(nickname),
                    ResponseCodeEnum.NICK_NAME_VALID_FAIL.getErrorMessage());
            userDO.setNickname(nickname);
            needUpdate = true;
        }

        String xiaohashuId = request.getXiaohashuId();
        if (StringUtils.isNotBlank(xiaohashuId)) {
            Preconditions.checkArgument(ParamUtils.checkXiaohashuId(xiaohashuId),
                    ResponseCodeEnum.XIAOHASHU_ID_VALID_FAIL.getErrorMessage());
            userDO.setXiaohashuId(xiaohashuId);
            needUpdate = true;
        }

        Integer sex = request.getSex();
        if (Objects.nonNull(sex)) {
            Preconditions.checkArgument(SexEnum.isValid(sex),
                    ResponseCodeEnum.SEX_VALID_FAIL.getErrorMessage());
            userDO.setSex(sex);
            needUpdate = true;
        }

        if (Objects.nonNull(request.getBirthday())) {
            userDO.setBirthday(request.getBirthday());
            needUpdate = true;
        }

        String introduction = request.getIntroduction();
        if (StringUtils.isNotBlank(introduction)) {
            Preconditions.checkArgument(ParamUtils.checkLength(introduction, 100),
                    ResponseCodeEnum.INTRODUCTION_VALID_FAIL.getErrorMessage());
            userDO.setIntroduction(introduction);
            needUpdate = true;
        }

        MultipartFile avatarFile = request.getAvatar();
        if (Objects.nonNull(avatarFile)) {
            String avatar = ossRpcService.uploadFile(avatarFile);
            log.info("OSS 返回头像链接：{}", avatar);
            if (StringUtils.isBlank(avatar)) {
                throw new BizException(ResponseCodeEnum.UPLOAD_AVATAR_FAIL);
            }
            userDO.setAvatar(avatar);
            needUpdate = true;
        }

        MultipartFile backgroundImgFile = request.getBackgroundImg();
        if (Objects.nonNull(backgroundImgFile)) {
            String backgroundImg = ossRpcService.uploadFile(backgroundImgFile);
            log.info("OSS 返回背景图链接：{}", backgroundImg);
            if (StringUtils.isBlank(backgroundImg)) {
                throw new BizException(ResponseCodeEnum.UPLOAD_BACKGROUND_IMG_FAIL);
            }
            userDO.setBackgroundImg(backgroundImg);
            needUpdate = true;
        }

        if (needUpdate) {
            deleteUserRedisCache(userId);

            userDO.setUpdateTime(LocalDateTime.now());
            userDOMapper.updateByPrimaryKeySelective(userDO);
            sendDelayDeleteUserRedisCacheMQ(userId);
        }
        return Response.success();
    }

    private void deleteUserRedisCache(Long userId) {
        String userInfoRedisKey = RedisKeyConstants.buildUserInfoKey(userId);
        String userProfileRedisKey = RedisKeyConstants.buildUserProfileKey(userId);
        redisTemplate.delete(Arrays.asList(userInfoRedisKey, userProfileRedisKey));
    }

    private void sendDelayDeleteUserRedisCacheMQ(Long userId) {
        Message<String> message = MessageBuilder.withPayload(String.valueOf(userId)).build();
        rocketMQTemplate.asyncSend(MQConstants.TOPIC_DELAY_DELETE_USER_REDIS_CACHE, message,
                new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        log.info("## 延时删除 Redis 用户缓存消息发送成功...");
                    }

                    @Override
                    public void onException(Throwable e) {
                        log.error("## 延时删除 Redis 用户缓存消息发送失败...", e);
                    }
                },
                3000,
                1);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<Long> register(RegisterUserReqDTO request) {
        String phone = request.getPhone();

        UserDO existingUser = userDOMapper.selectByPhone(phone);
        log.info("用户手机号是否已注册: {}", Objects.nonNull(existingUser));
        if (Objects.nonNull(existingUser)) {
            return Response.success(existingUser.getId());
        }

        // 否则注册新用户
        // 获取全局自增的拾光号 ID
        // Long xiaohashuId = redisTemplate.opsForValue().increment(RedisKeyConstants.XIAOHASHU_ID_GENERATOR_KEY);

        // RPC: 调用分布式 ID 生成服务生成拾光号 ID
        String xiaohashuId = distributedIdGeneratorRpcService.getXiaohashuId();

        // RPC: 调用分布式 ID 生成服务生成用户 ID
        String userIdStr = distributedIdGeneratorRpcService.getUserId();
        Long userId = Long.valueOf(userIdStr);

        LocalDateTime now = LocalDateTime.now();
        UserDO userDO = UserDO.builder()
                .id(userId)
                .phone(phone)
                .xiaohashuId(xiaohashuId) // 自动生成拾光号 ID
                .nickname("小红薯" + xiaohashuId) // 自动生成昵称, 如：小红薯10000
                .status(StatusEnum.ENABLE.getValue())
                .createTime(now)
                .updateTime(now)
                .isDeleted(DeletedEnum.NO.getValue())
                .build();
        userDOMapper.insert(userDO);

        // 获取刚刚添加入库的用户 ID
        // Long userId = userDO.getId();

        UserRoleDO userRoleDO = UserRoleDO.builder()
                .userId(userId)
                .roleId(RoleConstants.COMMON_USER_ROLE_ID)
                .createTime(now)
                .updateTime(now)
                .isDeleted(DeletedEnum.NO.getValue())
                .build();
        userRoleDOMapper.insert(userRoleDO);

        RoleDO roleDO = roleDOMapper.selectByPrimaryKey(RoleConstants.COMMON_USER_ROLE_ID);
        List<String> roles = new ArrayList<>(1);
        roles.add(roleDO.getRoleKey());
        String userRolesKey = RedisKeyConstants.buildUserRoleKey(userId);
        stringRedisTemplate.opsForValue().set(userRolesKey, JsonUtils.toJsonString(roles));

        return Response.success(userId);
    }

    @Override
    public Response<FindUserByPhoneRspDTO> findByPhone(FindUserByPhoneReqDTO request) {
        UserDO userDO = userDOMapper.selectByPhone(request.getPhone());
        if (Objects.isNull(userDO)) {
            throw new BizException(ResponseCodeEnum.USER_NOT_FOUND);
        }

        FindUserByPhoneRspDTO response = FindUserByPhoneRspDTO.builder()
                .id(userDO.getId())
                .password(userDO.getPassword())
                .build();
        return Response.success(response);
    }

    @Override
    public Response<FindUserByIdRspDTO> findById(FindUserByIdReqDTO findUserByIdReqDTO) {
        Long userId = findUserByIdReqDTO.getId();

        // 先从本地缓存中查询
        FindUserByIdRspDTO findUserByIdRspDTOLocalCache = LOCAL_CACHE.getIfPresent(userId);
        if (Objects.nonNull(findUserByIdRspDTOLocalCache)) {
            log.info("==> 命中了本地缓存；{}", findUserByIdRspDTOLocalCache);
            return Response.success(findUserByIdRspDTOLocalCache);
        }

        // 用户缓存 Redis Key
        String userInfoRedisKey = RedisKeyConstants.buildUserInfoKey(userId);

        // 再从 Redis 缓存中查询
        String userInfoRedisValue = (String) redisTemplate.opsForValue().get(userInfoRedisKey);

        // 若 Redis 缓存中存在该用户信息
        if (StringUtils.isNotBlank(userInfoRedisValue)) {
            // 将存储的 Json 字符串转换成对象，并返回
            FindUserByIdRspDTO findUserByIdRspDTO = JsonUtils.parseObject(userInfoRedisValue, FindUserByIdRspDTO.class);
            // 异步线程中将用户信息存入本地缓存
            threadPoolTaskExecutor.submit(() -> {
                if (Objects.nonNull(findUserByIdRspDTO)) {
                    // 写入本地缓存
                    LOCAL_CACHE.put(userId, findUserByIdRspDTO);
                }
            });
            return Response.success(findUserByIdRspDTO);
        }

        // 否则, 从数据库中查询
        // 根据用户 ID 查询用户信息
        UserDO userDO = userDOMapper.selectByPrimaryKey(userId);

        // 判空
        if (Objects.isNull(userDO)) {
            threadPoolTaskExecutor.execute(() -> {
                // 防止缓存穿透，将空数据存入 Redis 缓存 (过期时间不宜设置过长)
                // 保底1分钟 + 随机秒数
                long expireSeconds = 60 + RandomUtil.randomInt(60);
                redisTemplate.opsForValue().set(userInfoRedisKey, "null", expireSeconds, TimeUnit.SECONDS);
            });
            throw new BizException(ResponseCodeEnum.USER_NOT_FOUND);
        }

        // 构建返参
        FindUserByIdRspDTO findUserByIdRspDTO = FindUserByIdRspDTO.builder()
                .id(userDO.getId())
                .nickName(userDO.getNickname())
                .avatar(userDO.getAvatar())
                .introduction(userDO.getIntroduction())
                .build();

        // 异步将用户信息存入 Redis 缓存，提升响应速度
        threadPoolTaskExecutor.submit(() -> {
            // 过期时间（保底1天 + 随机秒数，将缓存过期时间打散，防止同一时间大量缓存失效，导致数据库压力太大）
            long expireSeconds = 60 * 60 * 24 + RandomUtil.randomInt(60 * 60 * 24);
            redisTemplate.opsForValue()
                    .set(userInfoRedisKey, JsonUtils.toJsonString(findUserByIdRspDTO), expireSeconds, TimeUnit.SECONDS);
        });

        return Response.success(findUserByIdRspDTO);
    }

    /**
     * 批量根据用户 ID 查询用户信息
     *
     * @param findUsersByIdsReqDTO
     * @return
     */
    @Override
    public Response<List<FindUserByIdRspDTO>> findByIds(FindUsersByIdsReqDTO findUsersByIdsReqDTO) {
        // 需要查询的用户 ID 集合
        List<Long> userIds = findUsersByIdsReqDTO.getIds();

        // 构建 Redis Key 集合
        List<String> redisKeys = userIds.stream()
                .map(RedisKeyConstants::buildUserInfoKey)
                .toList();

        // 先从 Redis 缓存中查, multiGet 批量查询提升性能
        List<Object> redisValues = redisTemplate.opsForValue().multiGet(redisKeys);
        // 如果缓存中不为空
        if (CollUtil.isNotEmpty(redisValues)) {
            // 过滤掉为空的数据
            redisValues = redisValues.stream().filter(Objects::nonNull).toList();
        }

        // 返参
        List<FindUserByIdRspDTO> findUserByIdRspDTOS = Lists.newArrayList();

        // 将过滤后的缓存集合，转换为 DTO 返参实体类
        if (CollUtil.isNotEmpty(redisValues)) {
            findUserByIdRspDTOS = redisValues.stream()
                    .map(value -> JsonUtils.parseObject(String.valueOf(value), FindUserByIdRspDTO.class))
                    .collect(Collectors.toList());
        }

        // 如果被查询的用户信息，都在 Redis 缓存中, 则直接返回
        if (CollUtil.size(userIds) == CollUtil.size(findUserByIdRspDTOS)) {
            return Response.success(findUserByIdRspDTOS);
        }

        // 还有另外两种情况：一种是缓存里没有用户信息数据，还有一种是缓存里数据不全，需要从数据库中补充
        // 筛选出缓存里没有的用户数据，去查数据库
        List<Long> userIdsNeedQuery = null;

        if (CollUtil.isNotEmpty(findUserByIdRspDTOS)) {
            // 将 findUserInfoByIdRspDTOS 集合转 Map
            Map<Long, FindUserByIdRspDTO> map = findUserByIdRspDTOS.stream()
                    .collect(Collectors.toMap(FindUserByIdRspDTO::getId, p -> p));

            // 筛选出需要查 DB 的用户 ID
            userIdsNeedQuery = userIds.stream()
                    .filter(id -> Objects.isNull(map.get(id)))
                    .toList();
        } else { // 缓存中一条用户信息都没查到，则提交的用户 ID 集合都需要查数据库
            userIdsNeedQuery = userIds;
        }

        // 从数据库中批量查询
        List<UserDO> userDOS = userDOMapper.selectByIds(userIdsNeedQuery);

        List<FindUserByIdRspDTO> findUserByIdRspDTOS2 = null;

        // 若数据库查询的记录不为空
        if (CollUtil.isNotEmpty(userDOS)) {
            // DO 转 DTO
            findUserByIdRspDTOS2 = userDOS.stream()
                    .map(userDO -> FindUserByIdRspDTO.builder()
                            .id(userDO.getId())
                            .nickName(userDO.getNickname())
                            .avatar(userDO.getAvatar())
                            .introduction(userDO.getIntroduction())
                            .build())
                    .collect(Collectors.toList());

            // 异步线程将用户信息同步到 Redis 中
            List<FindUserByIdRspDTO> finalFindUserByIdRspDTOS = findUserByIdRspDTOS2;
            threadPoolTaskExecutor.submit(() -> {
                // DTO 集合转 Map
                Map<Long, FindUserByIdRspDTO> map = finalFindUserByIdRspDTOS.stream()
                        .collect(Collectors.toMap(FindUserByIdRspDTO::getId, p -> p));

                // 执行 pipeline 操作
                redisTemplate.executePipelined(new SessionCallback<>() {
                    @Override
                    public Object execute(RedisOperations operations) {
                        for (UserDO userDO : userDOS) {
                            Long userId = userDO.getId();

                            // 用户信息缓存 Redis Key
                            String userInfoRedisKey = RedisKeyConstants.buildUserInfoKey(userId);

                            // DTO 转 JSON 字符串
                            FindUserByIdRspDTO findUserInfoByIdRspDTO = map.get(userId);
                            String value = JsonUtils.toJsonString(findUserInfoByIdRspDTO);

                            // 过期时间（保底1天 + 随机秒数，将缓存过期时间打散，防止同一时间大量缓存失效，导致数据库压力太大）
                            long expireSeconds = 60*60*24 + RandomUtil.randomInt(60*60*24);
                            operations.opsForValue().set(userInfoRedisKey, value, expireSeconds, TimeUnit.SECONDS);
                        }
                        return null;
                    }
                });
            });
        }

        // 合并数据
        if (CollUtil.isNotEmpty(findUserByIdRspDTOS2)) {
            findUserByIdRspDTOS.addAll(findUserByIdRspDTOS2);
        }

        return Response.success(findUserByIdRspDTOS);
    }

    @Override
    public Response<?> updatePassword(UpdateUserPasswordReqDTO request) {
        UserDO userDO = UserDO.builder()
                .id(LoginUserContextHolder.getUserId())
                .password(request.getEncodePassword())
                .updateTime(LocalDateTime.now())
                .build();
        int updatedRows = userDOMapper.updateByPrimaryKeySelective(userDO);
        if (updatedRows != 1) {
            return Response.fail(ResponseCodeEnum.USER_NOT_FOUND);
        }
        return Response.success();
    }
}
