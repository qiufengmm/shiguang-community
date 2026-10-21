package com.quanxiaoha.xiaohashu.search.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.google.common.collect.Lists;
import com.quanxiaoha.framework.common.constant.DateConstants;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.PageResponse;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.DateUtils;
import com.quanxiaoha.framework.common.util.NumberUtils;
import com.quanxiaoha.xiaohashu.dto.RebuildNoteDocumentReqDTO;
import com.quanxiaoha.xiaohashu.search.biz.constant.SearchPageConstants;
import com.quanxiaoha.xiaohashu.search.biz.enums.NotePublishTimeRangeEnum;
import com.quanxiaoha.xiaohashu.search.biz.enums.NoteSortTypeEnum;
import com.quanxiaoha.xiaohashu.search.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.search.biz.index.NoteIndex;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchNoteReqVO;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchNoteRspVO;
import com.quanxiaoha.xiaohashu.search.biz.service.NoteService;
import com.quanxiaoha.xiaohashu.search.biz.service.DocumentIndexRefreshService;
import com.quanxiaoha.xiaohashu.search.biz.service.SearchPageSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.lucene.search.function.FieldValueFactorFunction;
import org.elasticsearch.common.lucene.search.function.FunctionScoreQuery;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.FieldValueFactorFunctionBuilder;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class NoteServiceImpl implements NoteService {

    @Resource
    private RestHighLevelClient restHighLevelClient;
    @Resource
    private DocumentIndexRefreshService documentIndexRefreshService;

    @Override
    public PageResponse<SearchNoteRspVO> searchNote(SearchNoteReqVO searchNoteReqVO) {
        String keyword = searchNoteReqVO.getKeyword();
        Integer pageNo = searchNoteReqVO.getPageNo();
        Integer type = searchNoteReqVO.getType();
        Integer sort = searchNoteReqVO.getSort();
        Integer publishTimeRange = searchNoteReqVO.getPublishTimeRange();
        int from = SearchPageSupport.offsetFor(pageNo);

        SearchRequest searchRequest = new SearchRequest(NoteIndex.NAME);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery().must(
                QueryBuilders.multiMatchQuery(keyword)
                        .field(NoteIndex.FIELD_NOTE_TITLE, 2.0f)
                        .field(NoteIndex.FIELD_NOTE_TOPIC)
        );

        if (Objects.nonNull(type)) {
            boolQueryBuilder.filter(QueryBuilders.termQuery(NoteIndex.FIELD_NOTE_TYPE, type));
        }

        NotePublishTimeRangeEnum notePublishTimeRangeEnum = NotePublishTimeRangeEnum.valueOf(publishTimeRange);
        if (Objects.nonNull(notePublishTimeRangeEnum)) {
            String endTime = LocalDateTime.now().format(DateConstants.DATE_FORMAT_Y_M_D_H_M_S);
            String startTime = null;

            switch (notePublishTimeRangeEnum) {
                case DAY -> startTime = DateUtils.localDateTime2String(LocalDateTime.now().minusDays(1));
                case WEEK -> startTime = DateUtils.localDateTime2String(LocalDateTime.now().minusWeeks(1));
                case HALF_YEAR -> startTime = DateUtils.localDateTime2String(LocalDateTime.now().minusMonths(6));
            }
            if (StringUtils.isNoneBlank(startTime)) {
                boolQueryBuilder.filter(QueryBuilders.rangeQuery(NoteIndex.FIELD_NOTE_CREATE_TIME)
                        .gte(startTime)
                        .lte(endTime));
            }
        }

        NoteSortTypeEnum noteSortTypeEnum = NoteSortTypeEnum.valueOf(sort);
        if (Objects.nonNull(noteSortTypeEnum)) {
            switch (noteSortTypeEnum) {
                case LATEST -> sourceBuilder.sort(new FieldSortBuilder(NoteIndex.FIELD_NOTE_CREATE_TIME)
                        .order(SortOrder.DESC));
                case MOST_LIKE -> sourceBuilder.sort(new FieldSortBuilder(NoteIndex.FIELD_NOTE_LIKE_TOTAL)
                        .order(SortOrder.DESC));
                case MOST_COMMENT -> sourceBuilder.sort(new FieldSortBuilder(NoteIndex.FIELD_NOTE_COMMENT_TOTAL)
                        .order(SortOrder.DESC));
                case MOST_COLLECT -> sourceBuilder.sort(new FieldSortBuilder(NoteIndex.FIELD_NOTE_COLLECT_TOTAL)
                        .order(SortOrder.DESC));
            }
            sourceBuilder.query(boolQueryBuilder);
        } else {
            sourceBuilder.sort(new FieldSortBuilder("_score").order(SortOrder.DESC));
            FunctionScoreQueryBuilder.FilterFunctionBuilder[] filterFunctionBuilders =
                    new FunctionScoreQueryBuilder.FilterFunctionBuilder[]{
                            new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                                    new FieldValueFactorFunctionBuilder(NoteIndex.FIELD_NOTE_LIKE_TOTAL)
                                            .factor(0.5f)
                                            .modifier(FieldValueFactorFunction.Modifier.SQRT)
                                            .missing(0)),
                            new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                                    new FieldValueFactorFunctionBuilder(NoteIndex.FIELD_NOTE_COLLECT_TOTAL)
                                            .factor(0.3f)
                                            .modifier(FieldValueFactorFunction.Modifier.SQRT)
                                            .missing(0)),
                            new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                                    new FieldValueFactorFunctionBuilder(NoteIndex.FIELD_NOTE_COMMENT_TOTAL)
                                            .factor(0.2f)
                                            .modifier(FieldValueFactorFunction.Modifier.SQRT)
                                            .missing(0))
                    };

            FunctionScoreQueryBuilder functionScoreQueryBuilder = QueryBuilders.functionScoreQuery(
                            boolQueryBuilder, filterFunctionBuilders)
                    .scoreMode(FunctionScoreQuery.ScoreMode.SUM)
                    .boostMode(CombineFunction.SUM);
            sourceBuilder.query(functionScoreQueryBuilder);
        }

        sourceBuilder.from(from);
        sourceBuilder.size(SearchPageConstants.PAGE_SIZE);
        sourceBuilder.trackTotalHits(true);

        HighlightBuilder highlightBuilder = new HighlightBuilder();
        highlightBuilder.field(NoteIndex.FIELD_NOTE_TITLE)
                .preTags("<strong>")
                .postTags("</strong>");
        sourceBuilder.highlighter(highlightBuilder);
        searchRequest.source(sourceBuilder);

        List<SearchNoteRspVO> searchNoteRspVOS = null;
        long total = 0;
        try {
            log.info("==> SearchRequest: {}", searchRequest.source());
            SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
            total = searchResponse.getHits().getTotalHits().value;
            log.info("==> 命中文档总数, hits: {}", total);
            searchNoteRspVOS = Lists.newArrayList();

            SearchHits hits = searchResponse.getHits();
            for (SearchHit hit : hits) {
                log.info("==> 文档数据: {}", hit.getSourceAsString());
                Map<String, Object> sourceAsMap = hit.getSourceAsMap();
                Long noteId = parseNoteId(sourceAsMap.get(NoteIndex.FIELD_NOTE_ID));
                String cover = (String) sourceAsMap.get(NoteIndex.FIELD_NOTE_COVER);
                String title = (String) sourceAsMap.get(NoteIndex.FIELD_NOTE_TITLE);
                String avatar = (String) sourceAsMap.get(NoteIndex.FIELD_NOTE_AVATAR);
                String nickname = (String) sourceAsMap.get(NoteIndex.FIELD_NOTE_NICKNAME);
                String updateTimeStr = (String) sourceAsMap.get(NoteIndex.FIELD_NOTE_UPDATE_TIME);
                LocalDateTime updateTime = LocalDateTime.parse(
                        updateTimeStr, DateConstants.DATE_FORMAT_Y_M_D_H_M_S);
                Integer likeTotal = (Integer) sourceAsMap.get(NoteIndex.FIELD_NOTE_LIKE_TOTAL);
                Integer commentTotal = (Integer) sourceAsMap.get(NoteIndex.FIELD_NOTE_COMMENT_TOTAL);
                Integer collectTotal = (Integer) sourceAsMap.get(NoteIndex.FIELD_NOTE_COLLECT_TOTAL);

                String highlightedTitle = null;
                if (CollUtil.isNotEmpty(hit.getHighlightFields())
                        && hit.getHighlightFields().containsKey(NoteIndex.FIELD_NOTE_TITLE)) {
                    highlightedTitle = hit.getHighlightFields()
                            .get(NoteIndex.FIELD_NOTE_TITLE).fragments()[0].string();
                }

                SearchNoteRspVO searchNoteRspVO = SearchNoteRspVO.builder()
                        .noteId(noteId)
                        .cover(cover)
                        .title(title)
                        .highlightTitle(highlightedTitle)
                        .avatar(avatar)
                        .nickname(nickname)
                        .updateTime(DateUtils.formatRelativeTime(updateTime))
                        .likeTotal(NumberUtils.formatNumberString(likeTotal))
                        .commentTotal(NumberUtils.formatNumberString(commentTotal))
                        .collectTotal(NumberUtils.formatNumberString(collectTotal))
                        .build();
                searchNoteRspVOS.add(searchNoteRspVO);
            }
        } catch (IOException e) {
            log.error("==> 查询 Elasticsearch 异常: ", e);
            throw new BizException(ResponseCodeEnum.SYSTEM_ERROR);
        }

        return PageResponse.success(searchNoteRspVOS, pageNo, total);
    }

    private Long parseNoteId(Object value) {
        try {
            long noteId;
            if (value instanceof Integer || value instanceof Long) {
                noteId = ((Number) value).longValue();
            } else if (value instanceof String stringValue) {
                noteId = Long.parseLong(stringValue.trim());
            } else {
                throw new IllegalArgumentException("Unsupported note ID type");
            }
            if (noteId <= 0) {
                throw new IllegalArgumentException("Note ID must be positive");
            }
            return noteId;
        } catch (IllegalArgumentException e) {
            log.error("==> Elasticsearch 返回非法笔记 ID: {}", value, e);
            throw new BizException(ResponseCodeEnum.SYSTEM_ERROR);
        }
    }

    @Override
    public Response<Long> rebuildDocument(RebuildNoteDocumentReqDTO request) {
        Long noteId = request.getId();
        documentIndexRefreshService.refreshNote(noteId);
        return Response.success();
    }
}
