package com.quanxiaoha.xiaohashu.search.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.google.common.collect.Lists;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.PageResponse;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.NumberUtils;
import com.quanxiaoha.xiaohashu.dto.RebuildUserDocumentReqDTO;
import com.quanxiaoha.xiaohashu.search.biz.constant.SearchPageConstants;
import com.quanxiaoha.xiaohashu.search.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.search.biz.index.UserIndex;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchUserReqVO;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchUserRspVO;
import com.quanxiaoha.xiaohashu.search.biz.service.UserService;
import com.quanxiaoha.xiaohashu.search.biz.service.DocumentIndexRefreshService;
import com.quanxiaoha.xiaohashu.search.biz.service.SearchPageSupport;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    @Resource
    private RestHighLevelClient restHighLevelClient;
    @Resource
    private DocumentIndexRefreshService documentIndexRefreshService;

    @Override
    public PageResponse<SearchUserRspVO> searchUser(SearchUserReqVO searchUserReqVO) {
        String keyword = searchUserReqVO.getKeyword();
        Integer pageNo = searchUserReqVO.getPageNo();
        int from = SearchPageSupport.offsetFor(pageNo);

        SearchRequest searchRequest = new SearchRequest(UserIndex.NAME);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        sourceBuilder.query(QueryBuilders.multiMatchQuery(
                keyword, UserIndex.FIELD_USER_NICKNAME, UserIndex.FIELD_USER_XIAOHASHU_ID));

        SortBuilder<?> sortBuilder = new FieldSortBuilder(UserIndex.FIELD_USER_FANS_TOTAL)
                .order(SortOrder.DESC);
        sourceBuilder.sort(sortBuilder);

        sourceBuilder.from(from);
        sourceBuilder.size(SearchPageConstants.PAGE_SIZE);
        sourceBuilder.trackTotalHits(true);

        HighlightBuilder highlightBuilder = new HighlightBuilder();
        highlightBuilder.field(UserIndex.FIELD_USER_NICKNAME)
                .preTags("<strong>")
                .postTags("</strong>");
        sourceBuilder.highlighter(highlightBuilder);

        searchRequest.source(sourceBuilder);

        List<SearchUserRspVO> searchUserRspVOS = null;
        long total = 0;
        try {
            log.info("==> SearchRequest: {}", searchRequest);
            SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
            total = searchResponse.getHits().getTotalHits().value;
            log.info("==> 命中文档总数, hits: {}", total);
            searchUserRspVOS = Lists.newArrayList();

            SearchHits hits = searchResponse.getHits();
            for (SearchHit hit : hits) {
                log.info("==> 文档数据: {}", hit.getSourceAsString());
                Map<String, Object> sourceAsMap = hit.getSourceAsMap();
                Long userId = ((Number) sourceAsMap.get(UserIndex.FIELD_USER_ID)).longValue();
                String nickname = (String) sourceAsMap.get(UserIndex.FIELD_USER_NICKNAME);
                String avatar = (String) sourceAsMap.get(UserIndex.FIELD_USER_AVATAR);
                String xiaohashuId = (String) sourceAsMap.get(UserIndex.FIELD_USER_XIAOHASHU_ID);
                Integer noteTotal = (Integer) sourceAsMap.get(UserIndex.FIELD_USER_NOTE_TOTAL);
                Integer fansTotal = (Integer) sourceAsMap.get(UserIndex.FIELD_USER_FANS_TOTAL);
                String highlightedNickname = null;
                if (CollUtil.isNotEmpty(hit.getHighlightFields())
                        && hit.getHighlightFields().containsKey(UserIndex.FIELD_USER_NICKNAME)) {
                    highlightedNickname = hit.getHighlightFields()
                            .get(UserIndex.FIELD_USER_NICKNAME).fragments()[0].string();
                }

                SearchUserRspVO searchUserRspVO = SearchUserRspVO.builder()
                        .userId(userId)
                        .nickname(nickname)
                        .avatar(avatar)
                        .xiaohashuId(xiaohashuId)
                        .noteTotal(noteTotal)
                        .fansTotal(NumberUtils.formatNumberString(fansTotal))
                        .highlightNickname(highlightedNickname)
                        .build();
                searchUserRspVOS.add(searchUserRspVO);
            }
        } catch (Exception e) {
            log.error("==> 查询 Elasticsearch 异常: ", e);
            throw new BizException(ResponseCodeEnum.SYSTEM_ERROR);
        }

        return PageResponse.success(searchUserRspVOS, pageNo, total);
    }

    @Override
    public Response<Long> rebuildDocument(RebuildUserDocumentReqDTO request) {
        Long userId = request.getId();
        documentIndexRefreshService.refreshUser(userId);
        return Response.success();
    }
}
