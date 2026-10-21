package com.quanxiaoha.xiaohashu.kv.biz;

import com.quanxiaoha.xiaohashu.kv.biz.domain.dataobject.NoteContentDO;
import com.quanxiaoha.xiaohashu.kv.biz.domain.repository.NoteContentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false"
})
class CassandraTests {

    @Autowired
    private NoteContentRepository noteContentRepository;

    @Test
    void supportsInsertUpdateFindAndDeleteForAnIsolatedNote() {
        UUID id = UUID.randomUUID();
        try {
            noteContentRepository.save(NoteContentDO.builder()
                    .id(id)
                    .content("Cassandra CRUD test")
                    .build());

            NoteContentDO inserted = noteContentRepository.findById(id).orElseThrow();
            assertEquals("Cassandra CRUD test", inserted.getContent());

            noteContentRepository.save(NoteContentDO.builder()
                    .id(id)
                    .content("Cassandra CRUD updated")
                    .build());

            NoteContentDO updated = noteContentRepository.findById(id).orElseThrow();
            assertEquals("Cassandra CRUD updated", updated.getContent());

            noteContentRepository.deleteById(id);
            assertFalse(noteContentRepository.findById(id).isPresent());
        } finally {
            noteContentRepository.deleteById(id);
        }
    }
}
