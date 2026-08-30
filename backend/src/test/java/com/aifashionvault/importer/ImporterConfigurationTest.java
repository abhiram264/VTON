package com.aifashionvault.importer;

import java.time.Duration;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.objectstore.ObjectStoreImportMediaAccess;
import com.aifashionvault.shared.AccountMediaPurge;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the importer purge registers in a real booted context with a late
 * auto-configured DataSource (vton-test-datasource-beans convention) and
 * stays absent when there is no DataSource.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class ImporterConfigurationTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	@Autowired
	private ApplicationContext context;

	@DynamicPropertySource
	static void registerDatasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Test
	void jdbcShareImportStoreRegistersInBootedContext() {
		assertThat(context.getBeanProvider(JdbcShareImportStore.class).getIfAvailable()).isNotNull();
	}

	@Test
	void shareImportAccountMediaPurgeRegistersWithMediaAccessInBootedContext() {
		ShareImportAccountMediaPurge purge = context.getBeanProvider(ShareImportAccountMediaPurge.class)
			.getIfAvailable();

		assertThat(purge).isNotNull().isInstanceOf(AccountMediaPurge.class);
		assertThat(context.getBeanProvider(ImportMediaAccess.class).getIfAvailable())
			.isNotNull()
			.isInstanceOf(ObjectStoreImportMediaAccess.class);
	}

	@Test
	void importerBeansAreAbsentWithoutDataSource() {
		new ApplicationContextRunner()
			.withUserConfiguration(ImporterConfiguration.class)
			.run(ctx -> {
				assertThat(ctx).hasNotFailed();
				assertThat(ctx.getBeanProvider(JdbcShareImportStore.class).getIfAvailable()).isNull();
				assertThat(ctx.getBeanProvider(ShareImportAccountMediaPurge.class).getIfAvailable()).isNull();
				assertThat(ctx.getBeanProvider(ImportMediaAccess.class).getIfAvailable()).isNull();
			});
	}
}
