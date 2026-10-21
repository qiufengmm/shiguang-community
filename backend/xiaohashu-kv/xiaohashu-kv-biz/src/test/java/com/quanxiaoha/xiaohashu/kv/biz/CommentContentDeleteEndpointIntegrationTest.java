package com.quanxiaoha.xiaohashu.kv.biz;

import com.quanxiaoha.xiaohashu.kv.biz.domain.dataobject.CommentContentDO;
import com.quanxiaoha.xiaohashu.kv.biz.domain.dataobject.CommentContentPrimaryKey;
import com.quanxiaoha.xiaohashu.kv.biz.domain.repository.CommentContentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false"
})
class CommentContentDeleteEndpointIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CommentContentRepository commentContentRepository;

    @Test
    void deletesCommentBodyByNoteMonthAndContentId() {
        long noteId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String yearMonth = YearMonth.now().toString();
        UUID contentId = UUID.randomUUID();
        CommentContentPrimaryKey primaryKey = CommentContentPrimaryKey.builder()
                .noteId(noteId)
                .yearMonth(yearMonth)
                .contentId(contentId)
                .build();
        commentContentRepository.save(CommentContentDO.builder()
                .primaryKey(primaryKey)
                .content("delete test " + UUID.randomUUID())
                .build());

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/kv/comment/content/delete",
                    Map.of("noteId", noteId, "yearMonth", yearMonth, "contentId", contentId.toString()),
                    String.class);

            assertEquals(200, response.getStatusCode().value());
            assertFalse(commentContentRepository.findById(primaryKey).isPresent());
        } finally {
            commentContentRepository.deleteById(primaryKey);
        }
    }
}
