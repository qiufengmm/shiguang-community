package com.quanxiaoha.xiaohashu.user.dto.req;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 根据用户 ID 查询用户信息
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindUserByIdReqDTO {

    /**
     * 用户 ID
     */
    @NotNull(message = "用户 ID 不能为空")
    private Long id;

}
