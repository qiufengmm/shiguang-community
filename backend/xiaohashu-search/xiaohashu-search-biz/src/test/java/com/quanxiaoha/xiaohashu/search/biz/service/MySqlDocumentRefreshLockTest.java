package com.quanxiaoha.xiaohashu.search.biz.service;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySqlDocumentRefreshLockTest {

    @Test
    void myBatisRefreshCompletesWithOnlyOneAvailablePoolConnection() {
        Fixture fixture = new Fixture(1);

        fixture.lock.withLock("xh_es_note_42", () -> assertThat(fixture.mapper.currentVersion()).isEqualTo(7L));

        assertThat(fixture.dataSource.checkouts).isEqualTo(1);
        assertThat(fixture.dataSource.events).containsExactly("acquire", "read", "release", "commit", "close");
    }

    @Test
    void refreshSuspendsTheCallerTransactionAndUsesCurrentStateIsolation() {
        Fixture fixture = new Fixture(2);
        TransactionTemplate caller = new TransactionTemplate(fixture.transactionManager);
        caller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        AtomicReference<Connection> callerConnection = new AtomicReference<>();

        caller.executeWithoutResult(status -> {
            callerConnection.set(fixture.jdbc.execute((ConnectionCallback<Connection>) connection -> connection));
            assertThat(fixture.mapper.currentVersion()).isEqualTo(7L);
            fixture.lock.withLock("xh_es_note_42", () -> {
                fixture.jdbc.execute((ConnectionCallback<Void>) connection -> {
                assertThat(DataSourceUtils.getTargetConnection(connection))
                        .isNotSameAs(DataSourceUtils.getTargetConnection(callerConnection.get()));
                    assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                    return null;
                });
                assertThat(fixture.mapper.currentVersion()).isEqualTo(7L);
            });
            fixture.jdbc.execute((ConnectionCallback<Void>) connection -> {
                assertThat(DataSourceUtils.getTargetConnection(connection))
                        .isSameAs(DataSourceUtils.getTargetConnection(callerConnection.get()));
                assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                return null;
            });
        });

        assertThat(fixture.dataSource.checkouts).isEqualTo(2);
    }

    @Test
    void refreshFailureReleasesTheNamedLockAndRollsBackBeforeReturningTheConnection() {
        Fixture fixture = new Fixture(1);
        IllegalStateException failure = new IllegalStateException("Elasticsearch unavailable");

        assertThatThrownBy(() -> fixture.lock.withLock("xh_es_note_42", () -> { throw failure; }))
                .isSameAs(failure);

        assertThat(fixture.dataSource.events).containsExactly("acquire", "release", "rollback", "close");
    }

    @Test
    void lockTimeoutDoesNotRunTheRefreshOrReleaseAnUnownedLock() {
        Fixture fixture = new Fixture(1);
        fixture.dataSource.acquired = false;
        AtomicBoolean refreshed = new AtomicBoolean();

        assertThatThrownBy(() -> fixture.lock.withLock("xh_es_note_42", () -> refreshed.set(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out");

        assertThat(refreshed).isFalse();
        assertThat(fixture.dataSource.events).containsExactly("acquire", "rollback", "close");
    }

    interface VersionMapper {
        @Select("SELECT refresh_version")
        long currentVersion();
    }

    private static final class Fixture {
        final BoundedDataSource dataSource;
        final JdbcTemplate jdbc;
        final VersionMapper mapper;
        final MySqlDocumentRefreshLock lock;
        final DataSourceTransactionManager transactionManager;

        Fixture(int maximumConnections) {
            dataSource = new BoundedDataSource(maximumConnections);
            jdbc = new JdbcTemplate(dataSource);
            transactionManager = new DataSourceTransactionManager(dataSource);
            Configuration configuration = new Configuration(new Environment("test",
                    new SpringManagedTransactionFactory(), dataSource));
            configuration.addMapper(VersionMapper.class);
            mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration))
                    .getMapper(VersionMapper.class);
            lock = new MySqlDocumentRefreshLock(jdbc, transactionManager);
        }
    }

    /** Only the external JDBC protocol is stubbed; Spring and MyBatis manage real transaction resources. */
    private static final class BoundedDataSource extends AbstractDataSource {
        final int maximumConnections;
        final List<String> events = new ArrayList<>();
        int activeConnections;
        int checkouts;
        boolean acquired = true;

        BoundedDataSource(int maximumConnections) {
            this.maximumConnections = maximumConnections;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (activeConnections == maximumConnections) throw new SQLException("connection pool exhausted");
            activeConnections++;
            checkouts++;
            AtomicBoolean autoCommit = new AtomicBoolean(true);
            AtomicInteger isolation = new AtomicInteger(Connection.TRANSACTION_REPEATABLE_READ);
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenAnswer(invocation -> autoCommit.get());
            doAnswer(invocation -> { autoCommit.set(invocation.getArgument(0)); return null; })
                    .when(connection).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
            when(connection.getTransactionIsolation()).thenAnswer(invocation -> isolation.get());
            doAnswer(invocation -> { isolation.set(invocation.getArgument(0)); return null; })
                    .when(connection).setTransactionIsolation(anyInt());
            when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
                    statement(connection, invocation.getArgument(0)));
            doAnswer(invocation -> { events.add("commit"); return null; }).when(connection).commit();
            doAnswer(invocation -> { events.add("rollback"); return null; }).when(connection).rollback();
            doAnswer(invocation -> { activeConnections--; events.add("close"); return null; }).when(connection).close();
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        private PreparedStatement statement(Connection connection, String sql) throws SQLException {
            PreparedStatement statement = mock(PreparedStatement.class);
            when(statement.getConnection()).thenReturn(connection);
            ResultSet result = mock(ResultSet.class);
            when(result.next()).thenReturn(true, false);
            if (sql.contains("GET_LOCK")) {
                when(result.getInt(1)).thenReturn(acquired ? 1 : 0);
                when(statement.executeQuery()).thenAnswer(invocation -> { events.add("acquire"); return result; });
            } else if (sql.contains("RELEASE_LOCK")) {
                when(result.getInt(1)).thenReturn(1);
                when(statement.executeQuery()).thenAnswer(invocation -> { events.add("release"); return result; });
            } else {
                ResultSetMetaData metadata = mock(ResultSetMetaData.class);
                when(metadata.getColumnCount()).thenReturn(1);
                when(metadata.getColumnLabel(1)).thenReturn("refresh_version");
                when(metadata.getColumnName(1)).thenReturn("refresh_version");
                when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);
                when(metadata.getColumnClassName(1)).thenReturn(Long.class.getName());
                when(result.getMetaData()).thenReturn(metadata);
                when(result.getLong("refresh_version")).thenReturn(7L);
                when(statement.execute()).thenAnswer(invocation -> { events.add("read"); return true; });
                when(statement.getResultSet()).thenReturn(result);
                when(statement.getUpdateCount()).thenReturn(-1);
            }
            return statement;
        }
    }
}
