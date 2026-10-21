package com.quanxiaoha.xiaohashu.user.dto.req;

import com.quanxiaoha.framework.common.validator.PhoneNumber;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户注册请求。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RegisterUserReqDTO {

    @NotBlank(message = "手机号不能为空")
    @PhoneNumber
    private String phone;
}
