package com.quanxiaoha.xiaohashu.note.biz.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NoteContentSagaSchemaTest {

    @Test
    void migrationPersistsRecoverablePayloadAndCanScrubItAfterCompletion() throws Exception {
        Path migration = Path.of("..", "..", "database", "25.15-note-content-saga.sql").toAbsolutePath().normalize();
        assertThat(Files.exists(migration))
                .as("the note content saga migration must be present")
                .isTrue();

        String sql = Files.readString(migration);
        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS `t_note_content_saga`")
                .contains("`payload` mediumtext DEFAULT NULL")
                .contains("`lease_owner` varchar(64)")
                .contains("`lease_version` bigint unsigned NOT NULL DEFAULT 0")
                .contains("`status` varchar(24) NOT NULL DEFAULT 'PENDING'")
                .contains("Temporary recovery payload; scrubbed after convergence");

        Path mapper = Path.of("src", "main", "resources", "mapper", "NoteContentSagaMapper.xml");
        String mapperXml = Files.readString(mapper);
        assertThat(mapperXml).contains("status IN ('PENDING', 'DELETE_PENDING', 'COMPENSATION_PENDING')");
        assertThat(mapperXml).contains("upsertCompensationPending")
                .contains("lease_version = lease_version + 1")
                .contains("selectActiveLeaseForUpdate")
                .contains("lease_until &gt; NOW()");

        String noteMapperXml = Files.readString(Path.of("src", "main", "resources", "mapper", "NoteDOMapper.xml"));
        assertThat(noteMapperXml).contains("selectByPrimaryKeyIncludingDeleted")
                .contains("where id = #{id,jdbcType=BIGINT}");
    }
}
