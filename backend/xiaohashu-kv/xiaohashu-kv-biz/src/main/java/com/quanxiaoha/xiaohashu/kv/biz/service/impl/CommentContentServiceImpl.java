package com.quanxiaoha.xiaohashu.kv.biz.service.impl;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.kv.biz.domain.dataobject.CommentContentDO;
import com.quanxiaoha.xiaohashu.kv.biz.domain.dataobject.CommentContentPrimaryKey;
import com.quanxiaoha.xiaohashu.kv.biz.domain.repository.CommentContentRepository;
import com.quanxiaoha.xiaohashu.kv.biz.service.CommentContentService;
import com.quanxiaoha.xiaohashu.kv.dto.req.BatchAddCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.BatchFindCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.DeleteCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.CommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.req.FindCommentContentReqDTO;
import com.quanxiaoha.xiaohashu.kv.dto.rsp.FindCommentContentRspDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CommentContentServiceImpl implements CommentContentService {

    @Resource
    private CassandraTemplate cassandraTemplate;

    @Resource
    private CommentContentRepository commentContentRepository;

    @Override
    public Response<?> batchAddCommentContent(BatchAddCommentContentReqDTO batchAddCommentContentReqDTO) {
        List<CommentContentDO> contentDOS = batchAddCommentContentReqDTO.getComments().stream()
                .map(commentContentReqDTO -> {
                    CommentContentPrimaryKey primaryKey = CommentContentPrimaryKey.builder()
                            .noteId(commentContentReqDTO.getNoteId())
                            .yearMonth(commentContentReqDTO.getYearMonth())
                            .contentId(UUID.fromString(commentContentReqDTO.getContentId()))
                            .build();

                    return CommentContentDO.builder()
                            .primaryKey(primaryKey)
                            .content(commentContentReqDTO.getContent())
                            .build();
                })
                .toList();

        cassandraTemplate.batchOps()
                .insert(contentDOS)
                .execute();

        return Response.success();
    }

    @Override
    public Response<?> batchFindCommentContent(BatchFindCommentContentReqDTO batchFindCommentContentReqDTO) {
        Long noteId = batchFindCommentContentReqDTO.getNoteId();
        List<FindCommentContentReqDTO> commentContentKeys = batchFindCommentContentReqDTO.getCommentContentKeys();
        List<String> yearMonths = commentContentKeys.stream()
                .map(FindCommentContentReqDTO::getYearMonth)
                .distinct()
                .collect(Collectors.toList());
        List<UUID> contentIds = commentContentKeys.stream()
                .map(commentContentKey -> UUID.fromString(commentContentKey.getContentId()))
                .distinct()
                .collect(Collectors.toList());

        List<CommentContentDO> commentContentDOS = commentContentRepository
                .findByPrimaryKeyNoteIdAndPrimaryKeyYearMonthInAndPrimaryKeyContentIdIn(noteId, yearMonths, contentIds);
        List<FindCommentContentRspDTO> results = commentContentDOS.stream()
                .map(commentContentDO -> FindCommentContentRspDTO.builder()
                        .contentId(String.valueOf(commentContentDO.getPrimaryKey().getContentId()))
                        .content(commentContentDO.getContent())
                        .build())
                .toList();
        return Response.success(results);
    }

    @Override
    public Response<?> deleteCommentContent(DeleteCommentContentReqDTO deleteCommentContentReqDTO) {
        Long noteId = deleteCommentContentReqDTO.getNoteId();
        String yearMonth = deleteCommentContentReqDTO.getYearMonth();
        UUID contentId = UUID.fromString(deleteCommentContentReqDTO.getContentId());

        commentContentRepository.deleteByPrimaryKeyNoteIdAndPrimaryKeyYearMonthAndPrimaryKeyContentId(
                noteId, yearMonth, contentId);

        return Response.success();
    }
}
