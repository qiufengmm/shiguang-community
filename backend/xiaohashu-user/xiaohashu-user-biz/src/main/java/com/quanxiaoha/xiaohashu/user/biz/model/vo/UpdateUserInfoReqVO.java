package com.quanxiaoha.xiaohashu.user.biz.model.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

@Data
public class UpdateUserInfoReqVO {
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;
    private MultipartFile avatar;
    private String nickname;
    private String xiaohashuId;
    private Integer sex;
    private LocalDate birthday;
    private String introduction;
    private MultipartFile backgroundImg;
}
