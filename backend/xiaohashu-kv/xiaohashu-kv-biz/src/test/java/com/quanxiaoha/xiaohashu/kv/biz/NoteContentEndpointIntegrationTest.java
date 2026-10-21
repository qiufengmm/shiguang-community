package com.quanxiaoha.xiaohashu.kv.biz;

import com.quanxiaoha.xiaohashu.kv.biz.domain.dataobject.NoteContentDO;
import com.quanxiaoha.xiaohashu.kv.biz.domain.repository.NoteContentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false"
})
class NoteContentEndpointIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NoteContentRepository noteContentRepository;

    @Test
    void addsNoteContentAndReturnsSuccess() {
        String content = "KV endpoint test " + UUID.randomUUID();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/kv/note/content/add",
                    Map.of("uuid", UUID.randomUUID().toString(), "content", content),
                    String.class);

            assertEquals(200, response.getStatusCode().value());
            assertTrue(response.getBody().contains("\"success\":true"));
            assertTrue(noteContentRepository.findAll().stream()
                    .anyMatch(note -> content.equals(note.getContent())));
        } finally {
            noteContentRepository.findAll().stream()
                    .filter(note -> content.equals(note.getContent()))
                    .map(NoteContentDO::getId)
                    .forEach(noteContentRepository::deleteById);
        }
    }

    @Test
    void rejectsAddRequestsWithMissingIdOrBlankContent() {
        ResponseEntity<String> missingId = restTemplate.postForEntity(
                "/kv/note/content/add",
                Map.of("content", "content without an id"),
                String.class);
        ResponseEntity<String> blankContent = restTemplate.postForEntity(
                "/kv/note/content/add",
                Map.of("uuid", UUID.randomUUID().toString(), "content", " "),
                String.class);

        assertEquals(200, missingId.getStatusCode().value());
        assertTrue(missingId.getBody().contains("\"errorCode\":\"KV-10001\""));
        assertEquals(200, blankContent.getStatusCode().value());
        assertTrue(blankContent.getBody().contains("\"errorCode\":\"KV-10001\""));
    }

    @Test
    void findsNoteContentByItsUuid() {
        UUID id = UUID.randomUUID();
        String content = "KV find endpoint test " + UUID.randomUUID();
        noteContentRepository.save(NoteContentDO.builder().id(id).content(content).build());

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/kv/note/content/find",
                    Map.of("uuid", id.toString()),
                    String.class);

            assertEquals(200, response.getStatusCode().value());
            assertTrue(response.getBody().contains(id.toString()));
            assertTrue(response.getBody().contains(content));
        } finally {
            noteContentRepository.deleteById(id);
        }
    }

    @Test
    void returnsBusinessErrorWhenNoteContentDoesNotExist() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/kv/note/content/find",
                Map.of("uuid", UUID.randomUUID().toString()),
                String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("\"success\":false"));
        assertTrue(response.getBody().contains("\"errorCode\":\"KV-20000\""));
    }

    @Test
    void rejectsMalformedNoteUuid() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/kv/note/content/find",
                Map.of("uuid", "not-a-uuid"),
                String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("\"errorCode\":\"KV-10001\""));
    }

    @Test
    void deletesNoteContentByItsUuid() {
        UUID id = UUID.randomUUID();
        noteContentRepository.save(NoteContentDO.builder()
                .id(id)
                .content("KV delete endpoint test")
                .build());

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/kv/note/content/delete",
                    Map.of("uuid", id.toString()),
                    String.class);

            assertEquals(200, response.getStatusCode().value());
            assertTrue(response.getBody().contains("\"success\":true"));
            assertTrue(noteContentRepository.findById(id).map(NoteContentDO::getIsDeleted).orElse(false));
            ResponseEntity<String> lateAddResponse = restTemplate.postForEntity(
                    "/kv/note/content/add",
                    Map.of("uuid", id.toString(), "content", "late retry after delete " + UUID.randomUUID()),
                    String.class);
            assertTrue(lateAddResponse.getBody().contains("\"success\":false"));
            ResponseEntity<String> findResponse = restTemplate.postForEntity(
                    "/kv/note/content/find",
                    Map.of("uuid", id.toString()),
                    String.class);
            assertTrue(findResponse.getBody().contains("\"success\":false"));
        } finally {
            noteContentRepository.deleteById(id);
        }
    }

    @Test
    void lateWriteAfterDeleteCannotResurrectNoteContent() {
        UUID id = UUID.randomUUID();
        try {
            ResponseEntity<String> deleteResponse = restTemplate.postForEntity(
                    "/kv/note/content/delete",
                    Map.of("uuid", id.toString()),
                    String.class);
            assertEquals(200, deleteResponse.getStatusCode().value());
            assertTrue(deleteResponse.getBody().contains("\"success\":true"));
            assertTrue(noteContentRepository.findById(id).map(NoteContentDO::getIsDeleted).orElse(false));

            ResponseEntity<String> lateWriteResponse = restTemplate.postForEntity(
                    "/kv/note/content/add",
                    Map.of("uuid", id.toString(), "content", "late Cassandra write " + UUID.randomUUID()),
                    String.class);
            assertEquals(200, lateWriteResponse.getStatusCode().value());
            assertTrue(lateWriteResponse.getBody().contains("\"success\":false"));

            ResponseEntity<String> findResponse = restTemplate.postForEntity(
                    "/kv/note/content/find",
                    Map.of("uuid", id.toString()),
                    String.class);
            assertTrue(findResponse.getBody().contains("\"success\":false"));
        } finally {
            noteContentRepository.deleteById(id);
        }
    }

    @Test
    void rejectsDeleteRequestsWithBlankNoteId() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/kv/note/content/delete",
                Map.of("uuid", " "),
                String.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("\"errorCode\":\"KV-10001\""));
    }
}
