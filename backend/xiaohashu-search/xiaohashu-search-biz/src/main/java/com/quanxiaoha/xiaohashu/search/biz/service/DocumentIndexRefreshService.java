package com.quanxiaoha.xiaohashu.search.biz.service;

import com.quanxiaoha.xiaohashu.search.biz.domain.mapper.SelectMapper;
import com.quanxiaoha.xiaohashu.search.biz.index.NoteIndex;
import com.quanxiaoha.xiaohashu.search.biz.index.UserIndex;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serializes refreshes by document key across search-service instances using a MySQL named lock.
 * Every refresh reads the current MySQL state while holding the lock, so an older Canal event
 * cannot write a stale query result after a newer refresh.
 */
@Service
public class DocumentIndexRefreshService {

    private final SelectMapper selectMapper;
    private final ElasticsearchDocumentWriter documentWriter;
    private final DocumentRefreshLock refreshLock;

    public DocumentIndexRefreshService(SelectMapper selectMapper,
                                       ElasticsearchDocumentWriter documentWriter,
                                       DocumentRefreshLock refreshLock) {
        this.selectMapper = selectMapper;
        this.documentWriter = documentWriter;
        this.refreshLock = refreshLock;
    }

    public void refreshNote(long noteId) {
        refreshLock.withLock(lockKey("note", noteId), () -> {
            List<Map<String, Object>> documents = selectMapper.selectEsNoteIndexData(noteId, null);
            if (documents == null || documents.isEmpty()) {
                delete(NoteIndex.NAME, noteId);
                return;
            }
            if (documents.size() != 1) {
                throw new IllegalStateException("Expected one note document for noteId=" + noteId);
            }
            index(NoteIndex.NAME, noteId, documents.get(0));
        });
    }

    public void refreshUser(long userId) {
        refreshLock.withLock(lockKey("user", userId), () -> {
            List<Map<String, Object>> documents = selectMapper.selectEsUserIndexData(userId);
            if (documents == null || documents.isEmpty()) {
                delete(UserIndex.NAME, userId);
                return;
            }
            if (documents.size() != 1) {
                throw new IllegalStateException("Expected one user document for userId=" + userId);
            }
            index(UserIndex.NAME, userId, documents.get(0));
        });
    }

    public void refreshUserAndNotes(long userId) {
        refreshUser(userId);
        List<Long> noteIds = selectMapper.selectNoteIdsByCreator(userId);
        if (noteIds == null) return;
        noteIds.stream().filter(Objects::nonNull).distinct().forEach(this::refreshNote);
    }

    private void index(String indexName, long documentId, Map<String, Object> source) {
        try {
            documentWriter.index(indexName, documentId, source);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to refresh " + indexName + ":" + documentId, e);
        }
    }

    private void delete(String indexName, long documentId) {
        try {
            documentWriter.delete(indexName, documentId);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + indexName + ":" + documentId, e);
        }
    }

    private String lockKey(String type, long id) {
        return "xh_es_" + type + "_" + id;
    }
}
