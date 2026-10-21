package com.quanxiaoha.xiaohashu.search.biz.service;

import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
public class ElasticsearchDocumentWriterImpl implements ElasticsearchDocumentWriter {

    private final RestHighLevelClient restHighLevelClient;

    public ElasticsearchDocumentWriterImpl(RestHighLevelClient restHighLevelClient) {
        this.restHighLevelClient = restHighLevelClient;
    }

    @Override
    public void index(String indexName, long documentId, Map<String, Object> source) throws IOException {
        IndexRequest request = new IndexRequest(indexName)
                .id(Long.toString(documentId))
                .source(source);
        restHighLevelClient.index(request, RequestOptions.DEFAULT);
    }

    @Override
    public void delete(String indexName, long documentId) throws IOException {
        restHighLevelClient.delete(new DeleteRequest(indexName, Long.toString(documentId)), RequestOptions.DEFAULT);
    }
}
