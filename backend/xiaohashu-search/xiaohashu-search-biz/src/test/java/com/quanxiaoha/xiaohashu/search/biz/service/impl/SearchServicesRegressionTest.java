package com.quanxiaoha.xiaohashu.search.biz.service.impl;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.PageResponse;
import com.quanxiaoha.xiaohashu.search.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchNoteReqVO;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchNoteRspVO;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchUserReqVO;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchUserRspVO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchServicesRegressionTest {

    private RestHighLevelClient client;
    private UserServiceImpl userService;
    private NoteServiceImpl noteService;

    @BeforeEach
    void setUp() {
        client = mock(RestHighLevelClient.class);
        userService = new UserServiceImpl();
        noteService = new NoteServiceImpl();
        ReflectionTestUtils.setField(userService, "restHighLevelClient", client);
        ReflectionTestUtils.setField(noteService, "restHighLevelClient", client);
    }

    @Test
    void userSearchFailureIsReturnedAsARecognizableServiceFailure() throws Exception {
        when(client.search(any(SearchRequest.class), any(RequestOptions.class)))
                .thenThrow(new IOException("Elasticsearch unavailable"));

        assertThatThrownBy(() -> userService.searchUser(SearchUserReqVO.builder()
                .keyword("alice").pageNo(1).build()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ResponseCodeEnum.SYSTEM_ERROR.getErrorCode());
    }

    @Test
    void noteSearchFailureIsReturnedAsARecognizableServiceFailure() throws Exception {
        when(client.search(any(SearchRequest.class), any(RequestOptions.class)))
                .thenThrow(new IOException("Elasticsearch unavailable"));

        assertThatThrownBy(() -> noteService.searchNote(SearchNoteReqVO.builder()
                .keyword("coffee").pageNo(1).build()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ResponseCodeEnum.SYSTEM_ERROR.getErrorCode());
    }

    @ParameterizedTest
    @MethodSource("invalidPageNumbers")
    void userSearchRejectsNullOutOfRangeAndBeyondWindowPageNumbers(Integer pageNo) {
        assertThatThrownBy(() -> userService.searchUser(SearchUserReqVO.builder()
                .keyword("alice").pageNo(pageNo).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("页码");

        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("invalidPageNumbers")
    void noteSearchRejectsNullOutOfRangeAndBeyondWindowPageNumbers(Integer pageNo) {
        assertThatThrownBy(() -> noteService.searchNote(SearchNoteReqVO.builder()
                .keyword("coffee").pageNo(pageNo).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("页码");

        verifyNoInteractions(client);
    }

    @Test
    void requestValidationRejectsExplicitNullAndBeyondWindowPageNumbers() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            SearchUserReqVO nullPageUser = SearchUserReqVO.builder().keyword("alice").pageNo(null).build();
            SearchNoteReqVO nullPageNote = SearchNoteReqVO.builder().keyword("coffee").pageNo(null).build();
            SearchUserReqVO tooDeepUser = SearchUserReqVO.builder().keyword("alice").pageNo(1001).build();
            SearchNoteReqVO tooDeepNote = SearchNoteReqVO.builder().keyword("coffee").pageNo(1001).build();

            assertThat(validator.validate(nullPageUser)).extracting(ConstraintViolation::getPropertyPath)
                    .extracting(Object::toString).contains("pageNo");
            assertThat(validator.validate(nullPageNote)).extracting(ConstraintViolation::getPropertyPath)
                    .extracting(Object::toString).contains("pageNo");
            assertThat(validator.validate(tooDeepUser)).extracting(ConstraintViolation::getPropertyPath)
                    .extracting(Object::toString).contains("pageNo");
            assertThat(validator.validate(tooDeepNote)).extracting(ConstraintViolation::getPropertyPath)
                    .extracting(Object::toString).contains("pageNo");
        }
    }

    static Stream<Arguments> invalidPageNumbers() {
        return Stream.of(Arguments.of((Integer) null), Arguments.of(0), Arguments.of(-1),
                Arguments.of(1001), Arguments.of(Integer.MAX_VALUE));
    }

    @Test
    void userSearchTracksExactHitsAndAllowsTheLastPageWithinTheDefaultResultWindow() throws Exception {
        stubSearchHits(new SearchHits(new SearchHit[0],
                new TotalHits(12500, TotalHits.Relation.EQUAL_TO), Float.NaN));

        PageResponse<SearchUserRspVO> response = userService.searchUser(SearchUserReqVO.builder()
                .keyword("alice").pageNo(1000).build());

        assertThat(response.getTotalCount()).isEqualTo(12500);
        SearchRequest request = captureRequest();
        SearchSourceBuilder source = request.source();
        assertThat(source.from()).isEqualTo(9990);
        assertThat(source.size()).isEqualTo(10);
        assertThat(source.trackTotalHitsUpTo()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void noteSearchTracksExactHitsAndAllowsTheLastPageWithinTheDefaultResultWindow() throws Exception {
        stubSearchHits(new SearchHits(new SearchHit[0],
                new TotalHits(12500, TotalHits.Relation.EQUAL_TO), Float.NaN));

        PageResponse<SearchNoteRspVO> response = noteService.searchNote(SearchNoteReqVO.builder()
                .keyword("coffee").pageNo(1000).build());

        assertThat(response.getTotalCount()).isEqualTo(12500);
        SearchRequest request = captureRequest();
        SearchSourceBuilder source = request.source();
        assertThat(source.from()).isEqualTo(9990);
        assertThat(source.size()).isEqualTo(10);
        assertThat(source.trackTotalHitsUpTo()).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @MethodSource("validNoteIds")
    void noteSearchParsesIntegerLongAndNumericStringIds(Object noteId) throws Exception {
        stubSearchHits(new SearchHits(new SearchHit[]{noteHit(noteId)},
                new TotalHits(1, TotalHits.Relation.EQUAL_TO), 1.0f));

        PageResponse<SearchNoteRspVO> response = noteService.searchNote(SearchNoteReqVO.builder()
                .keyword("coffee").pageNo(1).build());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).extracting(SearchNoteRspVO::getNoteId).containsExactly(42L);
    }

    static Stream<Arguments> validNoteIds() {
        return Stream.of(Arguments.of(42), Arguments.of(42L), Arguments.of("42"));
    }

    @ParameterizedTest
    @MethodSource("invalidNoteIds")
    void noteSearchRejectsMalformedOrOutOfRangeIds(Object noteId) throws Exception {
        stubSearchHits(new SearchHits(new SearchHit[]{noteHit(noteId)},
                new TotalHits(1, TotalHits.Relation.EQUAL_TO), 1.0f));

        assertThatThrownBy(() -> noteService.searchNote(SearchNoteReqVO.builder()
                .keyword("coffee").pageNo(1).build()))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ResponseCodeEnum.SYSTEM_ERROR.getErrorCode());
    }

    static Stream<Arguments> invalidNoteIds() {
        return Stream.of(Arguments.of("not-a-number"), Arguments.of("9223372036854775808"),
                Arguments.of(42.5d), Arguments.of(0), Arguments.of(-1), Arguments.of("0"),
                Arguments.of("-1"), Arguments.of((Object) null));
    }

    private void stubSearchHits(SearchHits hits) throws IOException {
        SearchResponse response = mock(SearchResponse.class);
        when(response.getHits()).thenReturn(hits);
        when(client.search(any(SearchRequest.class), any(RequestOptions.class))).thenReturn(response);
    }

    private SearchHit noteHit(Object noteId) {
        String noteIdJson = noteId instanceof String ? "\"" + noteId + "\"" : String.valueOf(noteId);
        String source = "{\"id\":" + noteIdJson
                + ",\"cover\":\"cover\",\"title\":\"title\",\"avatar\":\"avatar\","
                + "\"nickname\":\"nickname\",\"update_time\":\"2024-01-02 03:04:05\","
                + "\"like_total\":1,\"comment_total\":2,\"collect_total\":3}";
        return new SearchHit(0).sourceRef(new BytesArray(source));
    }

    private SearchRequest captureRequest() throws Exception {
        org.mockito.ArgumentCaptor<SearchRequest> captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), any(RequestOptions.class));
        return captor.getValue();
    }
}
