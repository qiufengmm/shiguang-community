package com.quanxiaoha.xiaohashu.search.biz.service;

import com.quanxiaoha.xiaohashu.search.biz.domain.mapper.SelectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexRefreshServiceTest {

    @Test
    void removesAnElasticsearchDocumentWhenTheSourceNoLongerProducesAnIndexableNote() throws Exception {
        SelectMapper mapper = mock(SelectMapper.class);
        ElasticsearchDocumentWriter writer = mock(ElasticsearchDocumentWriter.class);
        when(mapper.selectEsNoteIndexData(123456L, null)).thenReturn(List.of());

        new DocumentIndexRefreshService(mapper, writer, (key, refresh) -> refresh.run()).refreshNote(123456L);

        verify(writer).delete("note", 123456L);
    }

    @Test
    void propagatesAnElasticsearchIndexFailureSoCanalCanRetry() throws Exception {
        SelectMapper mapper = mock(SelectMapper.class);
        ElasticsearchDocumentWriter writer = mock(ElasticsearchDocumentWriter.class);
        when(mapper.selectEsNoteIndexData(234567L, null)).thenReturn(List.of(Map.of("id", 234567L)));
        doThrow(new IOException("elasticsearch unavailable"))
                .when(writer).index(eq("note"), eq(234567L), any());

        assertThatThrownBy(() -> new DocumentIndexRefreshService(mapper, writer, (key, refresh) -> refresh.run())
                .refreshNote(234567L))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("elasticsearch unavailable");
    }
}
