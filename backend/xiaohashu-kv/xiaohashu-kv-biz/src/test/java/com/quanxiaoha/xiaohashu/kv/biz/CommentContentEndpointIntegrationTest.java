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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false"
})
class CommentContentEndpointIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CommentContentRepository commentContentRepository;

    @Test
    void findsCommentBodiesByNoteMonthAndContentId() {
        long noteId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String firstMonth = YearMonth.now().minusMonths(1).toString();
        String secondMonth = YearMonth.now().toString();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        CommentContentDO first = CommentContentDO.builder()
                .primaryKey(CommentContentPrimaryKey.builder()
                        .noteId(noteId).yearMonth(firstMonth).contentId(firstId).build())
                .content("first " + UUID.randomUUID())
                .build();
        CommentContentDO second = CommentContentDO.builder()
                .primaryKey(CommentContentPrimaryKey.builder()
                        .noteId(noteId).yearMonth(secondMonth).contentId(secondId).build())
                .content("second " + UUID.randomUUID())
                .build();
        commentContentRepository.saveAll(List.of(first, second));

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/kv/comment/content/batchFind",
                    Map.of("noteId", noteId, "commentContentKeys", List.of(
                            Map.of("yearMonth", firstMonth, "contentId", firstId.toString()),
                            Map.of("yearMonth", secondMonth, "contentId", secondId.toString()))),
                    String.class);

            assertEquals(200, response.getStatusCode().value());
            assertTrue(response.getBody().contains(firstId.toString()));
            assertTrue(response.getBody().contains(secondId.toString()));
            assertTrue(response.getBody().contains(first.getContent()));
            assertTrue(response.getBody().contains(second.getContent()));
        } finally {
            commentContentRepository.deleteAll(List.of(first, second));
        }
    }
}
