package com.quanxiaoha.xiaohashu.search.biz.model.vo;

import com.quanxiaoha.xiaohashu.search.biz.constant.SearchPageConstants;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SearchUserReqVO {

    @NotBlank(message = "搜索关键词不能为空")
    private String keyword;

    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码不能小于 1")
    @Max(value = SearchPageConstants.MAX_PAGE_NO, message = "页码不能超过 {value}")
    private Integer pageNo = 1;
}
