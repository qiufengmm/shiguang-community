package com.quanxiaoha.xiaohashu.comment.biz.rpc;

import com.quanxiaoha.framework.common.constant.DateConstants;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.comment.biz.model.bo.CommentBO;
import com.quanxiaoha.xiaohashu.kv.api.KeyValueFeignApi;
import com.quanxiaoha.xiaohashu.kv.dto.req.BatchAddCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.BatchFindCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.CommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.DeleteCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.FindCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.rsp.FindCommentContentRspDTO;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import cn.hutool.core.collection.CollUtil;

@Component
public class KeyValueRpcService {

    private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    @Resource
    private KeyValueFeignApi keyValueFeignApi;

    public boolean batchAddCommentContents(List<CommentBO> commentBOS) {
        List<CommentContentReqDTO> commentContents = commentBOS.stream()
                .filter(commentBO -> StringUtils.isNotBlank(commentBO.getContent()))
                .map(commentBO -> CommentContentReqDTO.builder()
                        .noteId(commentBO.getNoteId())
                        .yearMonth(commentBO.getCreateTime().format(YEAR_MONTH_FORMATTER))
                        .contentId(commentBO.getContentUuid())
                        .content(commentBO.getContent())
                        .build())
                .toList();

        if (commentContents.isEmpty()) {
            return true;
        }

        BatchAddCommentContentReqDTO request = BatchAddCommentContentReqDTO.builder()
                .comments(commentContents)
                .build();
        Response<?> response = keyValueFeignApi.batchAddCommentContent(request);
        return Objects.nonNull(response) && response.isSuccess();
    }

    public List<FindCommentContentRspDTO> batchFindCommentContent(
            Long noteId, List<FindCommentContentReqDTO> findCommentContentReqDTOS) {
        BatchFindCommentContentReqDTO request = BatchFindCommentContentReqDTO.builder()
                .noteId(noteId)
                .commentContentKeys(findCommentContentReqDTOS)
                .build();
        Response<List<FindCommentContentRspDTO>> response = keyValueFeignApi.batchFindCommentContent(request);
        if (Objects.isNull(response) || !response.isSuccess()
                || Objects.isNull(response.getData()) || CollUtil.isEmpty(response.getData())) {
            return null;
        }
        return response.getData();
    }

    public boolean deleteCommentContent(Long noteId, LocalDateTime createTime, String contentId) {
        DeleteCommentContentReqDTO request = DeleteCommentContentReqDTO.builder()
                .noteId(noteId)
                .yearMonth(DateConstants.DATE_FORMAT_Y_M.format(createTime))
                .contentId(contentId)
                .build();

        Response<?> response = keyValueFeignApi.deleteCommentContent(request);
        if (!response.isSuccess()) {
            throw new RuntimeException("删除评论内容失败");
        }
        return true;
    }
}
