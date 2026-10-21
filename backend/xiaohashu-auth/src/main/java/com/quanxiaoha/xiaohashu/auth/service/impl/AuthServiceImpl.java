package com.quanxiaoha.xiaohashu.auth.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.google.common.base.Preconditions;
import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.auth.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.auth.enums.LoginTypeEnum;
import com.quanxiaoha.xiaohashu.auth.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.auth.model.vo.user.UpdatePasswordReqVO;
import com.quanxiaoha.xiaohashu.auth.model.vo.user.UserLoginReqVO;
import com.quanxiaoha.xiaohashu.auth.rpc.UserRpcService;
import com.quanxiaoha.xiaohashu.auth.service.AuthService;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByPhoneRspDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByPhoneRspDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * @description: 用户服务实现
 **/
@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final DefaultRedisScript<Long> CONSUME_VERIFICATION_CODE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    @Resource
    private UserRpcService userRpcService;
    @Resource
    private PasswordEncoder passwordEncoder;

    /**
     * 登录与注册
     *
     * @param userLoginReqVO
     * @return
     */
    @Override
    public Response<String> loginAndRegister(UserLoginReqVO userLoginReqVO) {
        String phone = userLoginReqVO.getPhone();
        Integer type = userLoginReqVO.getType();

        LoginTypeEnum loginTypeEnum = LoginTypeEnum.valueOf(type);

        // 登录类型错误
        if (Objects.isNull(loginTypeEnum)) {
            throw new BizException(ResponseCodeEnum.LOGIN_TYPE_ERROR);
        }

        Long userId = null;

        // 判断登录类型
        switch (loginTypeEnum) {
            case VERIFICATION_CODE: // 验证码登录
                String verificationCode = userLoginReqVO.getCode();

                // 校验入参验证码是否为空（Guava Preconditions，抛出的 IllegalArgumentException 由全局异常处理器统一兜底）
                Preconditions.checkArgument(StringUtils.isNotBlank(verificationCode), "验证码不能为空");

                // 构建验证码 Redis Key
                String key = RedisKeyConstants.buildVerificationCodeKey(phone);
                // 查询存储在 Redis 中该用户的登录验证码
                String sentCode = (String) redisTemplate.opsForValue().get(key);

                // 判断用户提交的验证码，与 Redis 中的验证码是否一致
                if (!StringUtils.equals(verificationCode, sentCode)) {
                    throw new BizException(ResponseCodeEnum.VERIFICATION_CODE_ERROR);
                }

                // 通过用户服务查询手机号对应的记录
                FindUserByPhoneRspDTO codeLoginUser = userRpcService.findUserByPhone(phone);

                log.info("验证码登录用户是否已注册: {}", Objects.nonNull(codeLoginUser));

                // 判断是否注册
                if (Objects.isNull(codeLoginUser)) {
                    // 未注册时，由用户服务创建用户记录及默认角色。
                    userId = userRpcService.registerUser(phone);
                    if (Objects.isNull(userId)) {
                        throw new BizException(ResponseCodeEnum.LOGIN_FAIL);
                    }
                } else {
                    // 已注册，则获取其用户 ID
                    userId = codeLoginUser.getId();
                }

                // 在下游用户查询/注册成功后原子消费，避免相同验证码并发复用。
                Long consumed = redisTemplate.execute(CONSUME_VERIFICATION_CODE_SCRIPT, List.of(key), verificationCode);
                if (!Long.valueOf(1L).equals(consumed)) {
                    throw new BizException(ResponseCodeEnum.VERIFICATION_CODE_ERROR);
                }
                break;
            case PASSWORD: // 密码登录
                // 密码不能为空
                String password = userLoginReqVO.getPassword();
                Preconditions.checkArgument(StringUtils.isNotBlank(password), "密码不能为空");

                // 通过用户服务查询手机号对应的用户凭据
                FindUserByPhoneRspDTO user = userRpcService.findUserByPhone(phone);

                // 判断该手机号是否注册过
                if (Objects.isNull(user)) {
                    throw new BizException(ResponseCodeEnum.USER_NOT_FOUND);
                }

                // 拿到数据库中存储的密文密码
                String encodePassword = user.getPassword();

                // 匹配密码是否一致
                boolean isPasswordCorrect = passwordEncoder.matches(password, encodePassword);

                // 如果不正确，则抛出业务异常，提示用户名或者密码不正确
                if (!isPasswordCorrect) {
                    throw new BizException(ResponseCodeEnum.PHONE_OR_PASSWORD_ERROR);
                }

                userId = user.getId();
                break;
            default:
                break;
        }

        // SaToken 登录用户, 入参为用户 ID
        StpUtil.login(userId);

        // 获取 Token 令牌
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();

        // 返回 Token 令牌
        return Response.success(tokenInfo.tokenValue);
    }

    /**
     * 退出登录
     *
     * @return
     */
    @Override
    public Response<?> logout() {
        // 当前请求对应的用户 ID（由 HeaderUserId2ContextFilter 从请求头取出来放进上下文）
        Long userId = LoginUserContextHolder.getUserId();

        log.info("==> 用户退出登录, userId: {}", userId);

        // 退出登录 (指定用户 ID)
        StpUtil.logout(userId);

        return Response.success();
    }

    /**
     * 修改密码
     *
     * @param updatePasswordReqVO
     * @return
     */
    @Override
    public Response<?> updatePassword(UpdatePasswordReqVO updatePasswordReqVO) {
        // 新密码
        String newPassword = updatePasswordReqVO.getNewPassword();
        // 密码加密
        String encodePassword = passwordEncoder.encode(newPassword);

        // 通过用户服务更新密码，当前用户 ID 由 Feign 上下文拦截器透传
        if (!userRpcService.updatePassword(encodePassword)) {
            throw new BizException(ResponseCodeEnum.PASSWORD_UPDATE_FAIL);
        }

        return Response.success();
    }

}
