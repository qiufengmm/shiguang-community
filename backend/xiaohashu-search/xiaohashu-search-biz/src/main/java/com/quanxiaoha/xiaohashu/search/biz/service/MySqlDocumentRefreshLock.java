package com.quanxiaoha.xiaohashu.search.biz.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Uses MySQL named locks so multiple search-service instances serialize the same document refresh. */
@Component
public class MySqlDocumentRefreshLock implements DocumentRefreshLock {

    private static final int LOCK_TIMEOUT_SECONDS = 30;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate refreshTransaction;

    public MySqlDocumentRefreshLock(JdbcTemplate jdbcTemplate,
                                    PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.refreshTransaction = new TransactionTemplate(transactionManager);
        this.refreshTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.refreshTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public void withLock(String key, Runnable refresh) {
        if (key == null || key.length() > 64) {
            throw new IllegalArgumentException("MySQL advisory lock key must contain 1 to 64 characters");
        }
        refreshTransaction.executeWithoutResult(status -> jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            acquire(connection, key);
            Throwable refreshFailure = null;
            try {
                // The transaction binds this connection to the thread, so MyBatis refresh queries
                // reuse the connection that owns the MySQL named lock.
                refresh.run();
            } catch (RuntimeException | Error failure) {
                refreshFailure = failure;
                throw failure;
            } finally {
                try {
                    release(connection, key);
                } catch (SQLException | RuntimeException releaseFailure) {
                    if (refreshFailure != null) {
                        refreshFailure.addSuppressed(releaseFailure);
                    } else {
                        throw releaseFailure;
                    }
                }
            }
            return null;
        }));
    }

    private void acquire(java.sql.Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)");) {
            statement.setString(1, key);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1 || resultSet.wasNull()) {
                    throw new IllegalStateException("Timed out waiting for Elasticsearch refresh lock " + key);
                }
            }
        }
    }

    private void release(java.sql.Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException("Failed to release Elasticsearch refresh lock " + key);
                }
            }
        }
    }
}
