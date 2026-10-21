package com.quanxiaoha.xiaohashu.kv.biz.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.quanxiaoha.xiaohashu.kv.biz.domain.dataobject.NoteContentDO;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Persists note bodies with a permanent per-UUID tombstone so delayed writes cannot resurrect deleted content.
 */
@Service
public class NoteContentPersistenceService {

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO note_content (id, content, is_deleted)
            VALUES (?, ?, false) IF NOT EXISTS
            """;
    private static final String INSERT_TOMBSTONE_IF_ABSENT = """
            INSERT INTO note_content (id, content, is_deleted)
            VALUES (?, null, true) IF NOT EXISTS
            """;
    private static final String MARK_DELETED_IF_EXISTS = """
            UPDATE note_content SET content = null, is_deleted = true
            WHERE id = ? IF EXISTS
            """;
    private static final String SELECT_BY_ID = "SELECT content, is_deleted FROM note_content WHERE id = ?";

    private final CqlSession cqlSession;
    private final PreparedStatement insertIfAbsent;
    private final PreparedStatement insertTombstoneIfAbsent;
    private final PreparedStatement markDeletedIfExists;
    private final PreparedStatement selectById;

    public NoteContentPersistenceService(CqlSession cqlSession) {
        this.cqlSession = cqlSession;
        this.insertIfAbsent = cqlSession.prepare(INSERT_IF_ABSENT);
        this.insertTombstoneIfAbsent = cqlSession.prepare(INSERT_TOMBSTONE_IF_ABSENT);
        this.markDeletedIfExists = cqlSession.prepare(MARK_DELETED_IF_EXISTS);
        this.selectById = cqlSession.prepare(SELECT_BY_ID);
    }

    /**
     * Writes a body only when no row or deletion fence already exists for this UUID.
     * An identical existing live body is treated as a successful retry.
     */
    public boolean saveIfNotDeleted(UUID id, String content) {
        ResultSet result = cqlSession.execute(atLocalQuorum(insertIfAbsent.bind(id, content)));
        if (result.wasApplied()) {
            return true;
        }

        var existing = selectAtQuorum(id);
        return existing != null
                && (existing.isNull("is_deleted") || !existing.getBoolean("is_deleted"))
                && content.equals(existing.getString("content"));
    }

    public Optional<NoteContentDO> findLive(UUID id) {
        var row = selectAtQuorum(id);
        if (row == null || (!row.isNull("is_deleted") && row.getBoolean("is_deleted"))) {
            return Optional.empty();
        }
        String content = row.getString("content");
        if (content == null) {
            return Optional.empty();
        }
        return Optional.of(NoteContentDO.builder()
                .id(id)
                .content(content)
                .isDeleted(row.isNull("is_deleted") ? null : row.getBoolean("is_deleted"))
                .build());
    }

    /**
     * Creates a durable fence even when the row is absent. Both paths are LWT operations, so racing
     * inserts/deletes are serialized by Cassandra for this UUID partition.
     */
    public void tombstone(UUID id) {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (cqlSession.execute(atLocalQuorum(insertTombstoneIfAbsent.bind(id))).wasApplied()) {
                return;
            }
            if (cqlSession.execute(atLocalQuorum(markDeletedIfExists.bind(id))).wasApplied()) {
                return;
            }
        }
        throw new IllegalStateException("Could not persist note content deletion fence for UUID " + id);
    }

    private com.datastax.oss.driver.api.core.cql.Row selectAtQuorum(UUID id) {
        return cqlSession.execute(selectById.bind(id)
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM)).one();
    }

    private BoundStatement atLocalQuorum(BoundStatement statement) {
        return statement.setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM);
    }
}
