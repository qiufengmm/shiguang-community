package com.quanxiaoha.xiaohashu.search.biz.service;

import java.io.IOException;
import java.util.Map;

public interface ElasticsearchDocumentWriter {
    void index(String indexName, long documentId, Map<String, Object> source) throws IOException;

    void delete(String indexName, long documentId) throws IOException;
}
