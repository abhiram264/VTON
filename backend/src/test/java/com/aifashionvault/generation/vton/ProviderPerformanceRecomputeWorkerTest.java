package com.aifashionvault.generation.vton;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProviderPerformanceRecomputeWorkerTest {

	private static final Instant NOW = Instant.parse("2026-04-02T15:30:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void scheduledRecomputeIsANoOpWhenTheStoreIsAbsent() {
		ProviderPerformanceRecomputeWorker worker =
			new ProviderPerformanceRecomputeWorker(new StoreProvider(null), CLOCK);

		assertThatCode(worker::scheduledRecompute).doesNotThrowAnyException();
	}

	@Test
	void scheduledRecomputeUsesExactTrailingWindowOnce() {
		JdbcProviderPerformanceStore store = mock(JdbcProviderPerformanceStore.class);
		ProviderPerformanceRecomputeWorker worker =
			new ProviderPerformanceRecomputeWorker(new StoreProvider(store), CLOCK);

		worker.scheduledRecompute();

		verify(store).recompute(
			Instant.parse("2026-03-30T00:00:00Z"),
			Instant.parse("2026-04-03T00:00:00Z"));
	}

	@Test
	void scheduledRecomputeContainsStoreFailures() {
		JdbcProviderPerformanceStore failingStore =
			new JdbcProviderPerformanceStore(new UnavailableDataSource(), CLOCK);
		ProviderPerformanceRecomputeWorker worker =
			new ProviderPerformanceRecomputeWorker(new StoreProvider(failingStore), CLOCK);

		assertThatCode(worker::scheduledRecompute).doesNotThrowAnyException();
	}

	/** Yields a fixed store (possibly null) from {@link #getIfAvailable()}. */
	private static final class StoreProvider implements ObjectProvider<JdbcProviderPerformanceStore> {

		private final JdbcProviderPerformanceStore store;

		private StoreProvider(JdbcProviderPerformanceStore store) {
			this.store = store;
		}

		@Override
		public JdbcProviderPerformanceStore getIfAvailable() {
			return store;
		}
	}

	/** Fails every connection attempt, so the store cannot open its transaction. */
	private static final class UnavailableDataSource implements DataSource {

		@Override
		public Connection getConnection() throws SQLException {
			throw new SQLException("database unavailable");
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			throw new SQLException("database unavailable");
		}

		@Override
		public PrintWriter getLogWriter() {
			return null;
		}

		@Override
		public void setLogWriter(PrintWriter out) {
		}

		@Override
		public void setLoginTimeout(int seconds) {
		}

		@Override
		public int getLoginTimeout() {
			return 0;
		}

		@Override
		public Logger getParentLogger() throws SQLFeatureNotSupportedException {
			throw new SQLFeatureNotSupportedException();
		}

		@Override
		public <T> T unwrap(Class<T> iface) {
			return null;
		}

		@Override
		public boolean isWrapperFor(Class<?> iface) {
			return false;
		}
	}
}
