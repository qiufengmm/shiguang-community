package com.quanxiaoha.xiaohashu.user.biz.service.impl;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.count.dto.FindUserCountsByIdRspDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.FindUserByPhoneReqDTO;
import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.UserDO;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.UserDOMapper;
import com.quanxiaoha.xiaohashu.user.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.user.biz.model.vo.FindUserProfileReqVO;
import com.quanxiaoha.xiaohashu.user.biz.model.vo.FindUserProfileRspVO;
import com.quanxiaoha.xiaohashu.user.biz.model.vo.UpdateUserInfoReqVO;
import com.quanxiaoha.xiaohashu.user.biz.rpc.CountRpcService;
import com.quanxiaoha.xiaohashu.user.biz.rpc.DistributedIdGeneratorRpcService;
import com.quanxiaoha.xiaohashu.user.biz.rpc.OssRpcService;
import com.quanxiaoha.xiaohashu.user.biz.constant.RoleConstants;
import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.RoleDO;
import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.UserRoleDO;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.RoleDOMapper;
import com.quanxiaoha.xiaohashu.user.biz.domain.mapper.UserRoleDOMapper;
import com.quanxiaoha.xiaohashu.user.dto.req.RegisterUserReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.UpdateUserPasswordReqDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserDOMapper userDOMapper;

    @Mock
    private UserRoleDOMapper userRoleDOMapper;

    @Mock
    private RoleDOMapper roleDOMapper;

    @Mock
    private OssRpcService ossRpcService;

    @Mock
    private DistributedIdGeneratorRpcService distributedIdGeneratorRpcService;

    @Mock
    private CountRpcService countRpcService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> stringRedisValueOperations;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeEach
    void setCurrentUserId() {
        LoginUserContextHolder.setUserId(42L);
    }

    @AfterEach
    void clearCurrentUserId() {
        LoginUserContextHolder.remove();
    }

    @Test
    void updatesOnlyProvidedFieldsForCurrentUser() {
        UpdateUserInfoReqVO request = new UpdateUserInfoReqVO();
        request.setUserId(42L);
        request.setNickname("小明");

        userService.updateUserInfo(request);

        ArgumentCaptor<UserDO> userCaptor = ArgumentCaptor.forClass(UserDO.class);
        verify(userDOMapper).updateByPrimaryKeySelective(userCaptor.capture());
        assertEquals(42L, userCaptor.getValue().getId());
        assertEquals("小明", userCaptor.getValue().getNickname());
        assertNotNull(userCaptor.getValue().getUpdateTime());
        verify(redisTemplate).delete(java.util.Arrays.asList("user:info:42", "user:profile:42"));
        verify(rocketMQTemplate).asyncSend(eq("DelayDeleteUserRedisCacheTopic"), any(), any(), eq(3000L), eq(1));
    }

    @Test
    void skipsDatabaseUpdateWhenNoFieldWasProvided() {
        UpdateUserInfoReqVO request = new UpdateUserInfoReqVO();
        request.setUserId(42L);
        userService.updateUserInfo(request);

        verify(userDOMapper, never()).updateByPrimaryKeySelective(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsInvalidNicknameWithoutUpdatingDatabase() {
        UpdateUserInfoReqVO request = new UpdateUserInfoReqVO();
        request.setUserId(42L);
        request.setNickname("x@");

        assertThrows(IllegalArgumentException.class, () -> userService.updateUserInfo(request));
        verify(userDOMapper, never()).updateByPrimaryKeySelective(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void uploadsAvatarAndSavesItsUrl() {
        UpdateUserInfoReqVO request = new UpdateUserInfoReqVO();
        request.setUserId(42L);
        request.setAvatar(new org.springframework.mock.web.MockMultipartFile(
                "avatar", "avatar.png", "image/png", new byte[]{1}));
        org.mockito.Mockito.when(ossRpcService.uploadFile(request.getAvatar()))
                .thenReturn("https://oss.example/avatar.png");

        userService.updateUserInfo(request);

        ArgumentCaptor<UserDO> userCaptor = ArgumentCaptor.forClass(UserDO.class);
        verify(userDOMapper).updateByPrimaryKeySelective(userCaptor.capture());
        verify(ossRpcService).uploadFile(request.getAvatar());
        assertEquals("https://oss.example/avatar.png", userCaptor.getValue().getAvatar());
    }

    @Test
    void rejectsAvatarWhenUploadFails() {
        UpdateUserInfoReqVO request = new UpdateUserInfoReqVO();
        request.setUserId(42L);
        request.setAvatar(new org.springframework.mock.web.MockMultipartFile(
                "avatar", "avatar.png", "image/png", new byte[]{1}));

        org.mockito.Mockito.when(ossRpcService.uploadFile(request.getAvatar())).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> userService.updateUserInfo(request));
        assertEquals(ResponseCodeEnum.UPLOAD_AVATAR_FAIL.getErrorCode(), exception.getErrorCode());
        verify(userDOMapper, never()).updateByPrimaryKeySelective(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void uploadsBackgroundImageAndSavesItsUrl() {
        UpdateUserInfoReqVO request = new UpdateUserInfoReqVO();
        request.setUserId(42L);
        request.setBackgroundImg(new org.springframework.mock.web.MockMultipartFile(
                "backgroundImg", "background.png", "image/png", new byte[]{1}));
        org.mockito.Mockito.when(ossRpcService.uploadFile(request.getBackgroundImg()))
                .thenReturn("https://oss.example/background.png");

        userService.updateUserInfo(request);

        ArgumentCaptor<UserDO> userCaptor = ArgumentCaptor.forClass(UserDO.class);
        verify(userDOMapper).updateByPrimaryKeySelective(userCaptor.capture());
        verify(ossRpcService).uploadFile(request.getBackgroundImg());
        assertEquals("https://oss.example/background.png", userCaptor.getValue().getBackgroundImg());
    }

    @Test
    void rejectsBackgroundImageWhenUploadFails() {
        UpdateUserInfoReqVO request = new UpdateUserInfoReqVO();
        request.setUserId(42L);
        request.setBackgroundImg(new org.springframework.mock.web.MockMultipartFile(
                "backgroundImg", "background.png", "image/png", new byte[]{1}));
        org.mockito.Mockito.when(ossRpcService.uploadFile(request.getBackgroundImg())).thenReturn("");

        BizException exception = assertThrows(BizException.class, () -> userService.updateUserInfo(request));
        assertEquals(ResponseCodeEnum.UPLOAD_BACKGROUND_IMG_FAIL.getErrorCode(), exception.getErrorCode());
        verify(userDOMapper, never()).updateByPrimaryKeySelective(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsUpdatingAnotherUsersProfile() {
        UpdateUserInfoReqVO request = new UpdateUserInfoReqVO();
        request.setUserId(43L);
        request.setNickname("他人主页");

        BizException exception = assertThrows(BizException.class, () -> userService.updateUserInfo(request));

        assertEquals(ResponseCodeEnum.CANT_UPDATE_OTHER_USER_PROFILE.getErrorCode(), exception.getErrorCode());
        verify(userDOMapper, never()).updateByPrimaryKeySelective(any());
        verify(redisTemplate, never()).delete(any(java.util.Collection.class));
        verify(rocketMQTemplate, never()).asyncSend(any(), any(), any(), any(Long.class), any(Integer.class));
    }

    @Test
    void returnsExistingUserIdWithoutCreatingAnotherAccount() {
        RegisterUserReqDTO request = RegisterUserReqDTO.builder().phone("13800138000").build();
        when(userDOMapper.selectByPhone(request.getPhone())).thenReturn(UserDO.builder().id(17L).build());

        var response = userService.register(request);

        assertEquals(17L, response.getData());
        verify(userDOMapper, never()).insert(any(UserDO.class));
        verify(userRoleDOMapper, never()).insert(any(UserRoleDO.class));
    }

    @Test
    void createsUserRoleAndRedisRoleCacheForNewPhone() {
        RegisterUserReqDTO request = RegisterUserReqDTO.builder().phone("13800138000").build();
        when(userDOMapper.selectByPhone(request.getPhone())).thenReturn(null);
        // 拾光号 ID 与用户 ID 均改由分布式 ID 生成服务（Leaf 号段模式）下发
        when(distributedIdGeneratorRpcService.getXiaohashuId()).thenReturn("10000");
        when(distributedIdGeneratorRpcService.getUserId()).thenReturn("99");
        when(stringRedisTemplate.opsForValue()).thenReturn(stringRedisValueOperations);
        when(roleDOMapper.selectByPrimaryKey(RoleConstants.COMMON_USER_ROLE_ID))
                .thenReturn(RoleDO.builder().roleKey("common_user").build());
        doAnswer(invocation -> {
            UserDO user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        }).when(userDOMapper).insert(any(UserDO.class));

        var response = userService.register(request);

        assertEquals(99L, response.getData());
        ArgumentCaptor<UserDO> userCaptor = ArgumentCaptor.forClass(UserDO.class);
        verify(userDOMapper).insert(userCaptor.capture());
        assertEquals("10000", userCaptor.getValue().getXiaohashuId());
        assertEquals("小红薯10000", userCaptor.getValue().getNickname());
        assertEquals(request.getPhone(), userCaptor.getValue().getPhone());
        ArgumentCaptor<UserRoleDO> userRoleCaptor = ArgumentCaptor.forClass(UserRoleDO.class);
        verify(userRoleDOMapper).insert(userRoleCaptor.capture());
        assertEquals(99L, userRoleCaptor.getValue().getUserId());
        assertEquals(RoleConstants.COMMON_USER_ROLE_ID, userRoleCaptor.getValue().getRoleId());
        verify(stringRedisValueOperations).set(eq("user:roles:99"), eq("[\"common_user\"]"));
    }

    @Test
    void returnsOnlyIdAndPasswordForExistingPhone() {
        FindUserByPhoneReqDTO request = FindUserByPhoneReqDTO.builder().phone("13800138000").build();
        when(userDOMapper.selectByPhone(request.getPhone())).thenReturn(
                UserDO.builder().id(17L).phone(request.getPhone()).password("encoded-password").build());

        var response = userService.findByPhone(request);

        assertEquals(17L, response.getData().getId());
        assertEquals("encoded-password", response.getData().getPassword());
    }

    @Test
    void reportsUserNotFoundForUnknownPhone() {
        FindUserByPhoneReqDTO request = FindUserByPhoneReqDTO.builder().phone("13800138000").build();
        when(userDOMapper.selectByPhone(request.getPhone())).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> userService.findByPhone(request));

        assertEquals(ResponseCodeEnum.USER_NOT_FOUND.getErrorCode(), exception.getErrorCode());
    }

    @Test
    void returnsUserProfileWithAgeAndAggregatedCounters() {
        Long userId = 671234567890123L;
        UserDO user = UserDO.builder()
                .id(userId)
                .avatar("https://cdn.example/avatar.webp")
                .nickname("测试用户")
                .xiaohashuId("test-xhs-id")
                .sex(1)
                .birthday(LocalDate.now().minusYears(30))
                .introduction("个人简介")
                .build();
        when(userDOMapper.selectByPrimaryKey(userId)).thenReturn(user);
        when(countRpcService.findUserCountById(userId)).thenReturn(FindUserCountsByIdRspDTO.builder()
                .userId(userId)
                .fansTotal(123L)
                .followingTotal(456L)
                .likeTotal(789L)
                .collectTotal(321L)
                .noteTotal(16L)
                .build());
        var response = userService.findUserProfile(FindUserProfileReqVO.builder().userId(userId).build());

        assertTrue(response.isSuccess());
        FindUserProfileRspVO profile = response.getData();
        assertEquals(userId, profile.getUserId());
        assertEquals("https://cdn.example/avatar.webp", profile.getAvatar());
        assertEquals("测试用户", profile.getNickname());
        assertEquals("test-xhs-id", profile.getXiaohashuId());
        assertEquals(1, profile.getSex());
        assertEquals(30, profile.getAge());
        assertEquals("个人简介", profile.getIntroduction());
        assertEquals("123", profile.getFansTotal());
        assertEquals("456", profile.getFollowingTotal());
        assertEquals("1110", profile.getLikeAndCollectTotal());
        assertEquals("16", profile.getNoteTotal());
        assertEquals("789", profile.getLikeTotal());
        assertEquals("321", profile.getCollectTotal());
    }

    @Test
    void reportsUserNotFoundWhenProfileUserDoesNotExist() {
        Long userId = 671234567890124L;
        when(userDOMapper.selectByPrimaryKey(userId)).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> userService.findUserProfile(FindUserProfileReqVO.builder().userId(userId).build()));

        assertEquals(ResponseCodeEnum.USER_NOT_FOUND.getErrorCode(), exception.getErrorCode());
        verify(countRpcService, never()).findUserCountById(any());
    }

    @Test
    void queriesDatabaseAndCountServiceBecauseProfileCachesAreDisabled() {
        Long userId = 811234567890123L;
        when(userDOMapper.selectByPrimaryKey(userId)).thenReturn(
                UserDO.builder().id(userId).nickname("数据库主页").build());
        when(countRpcService.findUserCountById(userId)).thenReturn(FindUserCountsByIdRspDTO.builder()
                .userId(userId).fansTotal(18L).build());

        var response = userService.findUserProfile(FindUserProfileReqVO.builder().userId(userId).build());

        assertTrue(response.isSuccess());
        assertEquals(userId, response.getData().getUserId());
        assertEquals("数据库主页", response.getData().getNickname());
        assertEquals("18", response.getData().getFansTotal());
        verify(userDOMapper).selectByPrimaryKey(userId);
        verify(countRpcService).findUserCountById(userId);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void doesNotWriteDatabaseProfileToRedisWhileProfileCachingIsDisabled() {
        Long userId = 822234567890123L;
        when(userDOMapper.selectByPrimaryKey(userId)).thenReturn(
                UserDO.builder().id(userId).nickname("数据库主页").build());
        when(countRpcService.findUserCountById(userId)).thenReturn(null);

        var response = userService.findUserProfile(FindUserProfileReqVO.builder().userId(userId).build());

        assertTrue(response.isSuccess());
        assertEquals("数据库主页", response.getData().getNickname());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void queriesDatabaseAgainBecauseLocalProfileCacheIsDisabled() {
        Long userId = 833234567890123L;
        when(userDOMapper.selectByPrimaryKey(userId)).thenReturn(
                UserDO.builder().id(userId).nickname("本地缓存主页").build());
        when(countRpcService.findUserCountById(userId)).thenReturn(null);

        userService.findUserProfile(FindUserProfileReqVO.builder().userId(userId).build());
        org.mockito.Mockito.clearInvocations(userDOMapper, countRpcService, redisTemplate);

        var response = userService.findUserProfile(FindUserProfileReqVO.builder().userId(userId).build());

        assertEquals("本地缓存主页", response.getData().getNickname());
        verify(userDOMapper).selectByPrimaryKey(userId);
        verify(redisTemplate, never()).opsForValue();
        verify(countRpcService).findUserCountById(userId);
    }

    @Test
    void bypassesLocalProfileCacheWhenTheOwnerViewsTheirProfile() {
        Long userId = 844234567890123L;
        when(userDOMapper.selectByPrimaryKey(userId)).thenReturn(
                UserDO.builder().id(userId).nickname("主页数据库值").build());
        when(countRpcService.findUserCountById(userId)).thenReturn(null);

        userService.findUserProfile(FindUserProfileReqVO.builder().userId(userId).build());
        org.mockito.Mockito.clearInvocations(userDOMapper, redisTemplate, countRpcService);
        LoginUserContextHolder.setUserId(userId);

        var response = userService.findUserProfile(FindUserProfileReqVO.builder().userId(userId).build());

        assertEquals("主页数据库值", response.getData().getNickname());
        verify(redisTemplate, never()).opsForValue();
        verify(userDOMapper, times(1)).selectByPrimaryKey(userId);
        verify(countRpcService, times(1)).findUserCountById(userId);
    }

    @Test
    void refreshesCountersFromCountServiceWhenOwnerReadsDatabaseProfile() {
        Long userId = 855234567890123L;
        when(userDOMapper.selectByPrimaryKey(userId)).thenReturn(
                UserDO.builder().id(userId).nickname("数据库主页").build());
        when(countRpcService.findUserCountById(userId)).thenReturn(FindUserCountsByIdRspDTO.builder()
                .userId(userId)
                .fansTotal(99L)
                .followingTotal(88L)
                .likeTotal(77L)
                .collectTotal(22L)
                .noteTotal(11L)
                .build());
        LoginUserContextHolder.setUserId(userId);

        var response = userService.findUserProfile(FindUserProfileReqVO.builder().userId(userId).build());

        assertEquals("数据库主页", response.getData().getNickname());
        assertEquals("99", response.getData().getFansTotal());
        assertEquals("88", response.getData().getFollowingTotal());
        assertEquals("99", response.getData().getLikeAndCollectTotal());
        assertEquals("11", response.getData().getNoteTotal());
        assertEquals("77", response.getData().getLikeTotal());
        assertEquals("22", response.getData().getCollectTotal());
        verify(countRpcService, times(1)).findUserCountById(userId);
        verify(userDOMapper).selectByPrimaryKey(userId);
    }

    @Test
    void updatesPasswordForCurrentUser() {
        UpdateUserPasswordReqDTO request = UpdateUserPasswordReqDTO.builder()
                .encodePassword("encoded-password")
                .build();
        when(userDOMapper.updateByPrimaryKeySelective(any(UserDO.class))).thenReturn(1);

        var response = userService.updatePassword(request);

        assertTrue(response.isSuccess());
        ArgumentCaptor<UserDO> userCaptor = ArgumentCaptor.forClass(UserDO.class);
        verify(userDOMapper).updateByPrimaryKeySelective(userCaptor.capture());
        assertEquals(42L, userCaptor.getValue().getId());
        assertEquals("encoded-password", userCaptor.getValue().getPassword());
        assertNotNull(userCaptor.getValue().getUpdateTime());
    }

    @Test
    void failsPasswordUpdateWhenCurrentUserDoesNotExist() {
        UpdateUserPasswordReqDTO request = UpdateUserPasswordReqDTO.builder()
                .encodePassword("encoded-password")
                .build();
        when(userDOMapper.updateByPrimaryKeySelective(any(UserDO.class))).thenReturn(0);

        var response = userService.updatePassword(request);

        assertFalse(response.isSuccess());
    }
}
