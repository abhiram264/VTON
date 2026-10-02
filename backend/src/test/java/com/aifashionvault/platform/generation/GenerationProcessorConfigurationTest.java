package com.aifashionvault.platform.generation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.generation.CallbackInboxStore;
import com.aifashionvault.generation.GenerationConfiguration;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.JdbcGenerationStore;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.privacy.Consent;
import com.aifashionvault.privacy.ConsentService;
import com.aifashionvault.privacy.ConsentStore;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=",
	"vault.generation.recovery.enabled=true"
})
class GenerationProcessorConfigurationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres")
		.withStartupTimeout(Duration.ofMinutes(5));

	@Autowired
	private ApplicationContext context;

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(
			GenerationConfiguration.class,
			GenerationProcessorConfiguration.class,
			GenerationRecoveryConfiguration.class)
		.withPropertyValues("vault.generation.recovery.enabled=true");

	@Test
	void generationBeansRegisterWithLateDataSource() {
		assertThat(context.getBeanProvider(GenerationWorkProcessor.class).getIfAvailable()).isNotNull();
		assertThat(context.getBeanProvider(GenerationWorkWorker.class).getIfAvailable()).isNotNull();
		assertThat(context.getBeanProvider(CallbackInboxStore.class).getIfAvailable()).isNotNull();
		assertThat(context.getBeanProvider(GenerationStore.class).getIfAvailable()).isNotNull();
		assertThat(context.getBeanProvider(JdbcGenerationStore.class).getIfAvailable()).isNotNull();
		assertThat(context.getBeanProvider(GenerationDueJobScanner.class).getIfAvailable()).isNotNull();
	}
	@Test
	void generationConfigurationStaysLoadableWhenDataSourceIsAbsent() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeanProvider(GenerationWorkProcessor.class).getIfAvailable()).isNull();
			assertThat(context.getBeanProvider(GenerationWorkWorker.class).getIfAvailable()).isNull();
			assertThat(context.getBeanProvider(CallbackInboxStore.class).getIfAvailable()).isNull();
			assertThat(context.getBeanProvider(GenerationStore.class).getIfAvailable()).isNull();
			assertThat(context.getBeanProvider(JdbcGenerationStore.class).getIfAvailable()).isNull();
			assertThat(context.getBeanProvider(GenerationDueJobScanner.class).getIfAvailable()).isNull();
		});
	}

	/**
	 * C06 enforcement boundary: the transfer component IS the consent gate, so it
	 * must never be constructed without a consent service. A context that has an
	 * object store but no {@code ConsentService} must produce no transfer component
	 * — and therefore no generation processor — rather than one that moves body
	 * images to a third-party provider with no consent check.
	 */
	@Test
	void transferComponentIsAbsentWhenNoConsentServiceExists() {
		runner.withBean(ObjectStore.class, UnusedObjectStore::new).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeanProvider(PrivacyApprovedInputTransfer.class).getIfAvailable()).isNull();
			assertThat(context.getBeanProvider(GenerationWorkProcessor.class).getIfAvailable()).isNull();
		});
	}

	/** The complement: with both an object store and a consent service the gate is built. */
	@Test
	void transferComponentIsBuiltWhenObjectStoreAndConsentServiceAreBothPresent() {
		runner.withBean(ObjectStore.class, UnusedObjectStore::new)
			.withBean(ConsentService.class, () -> new ConsentService(
				new NoConsentStore(), Clock.systemUTC(), "test-policy"))
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeanProvider(PrivacyApprovedInputTransfer.class).getIfAvailable()).isNotNull();
			});
	}

	/** Nothing in this test calls the store; every method fails loudly if it is reached. */
	static final class UnusedObjectStore implements ObjectStore {
		@Override
		public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ObjectMetadata head(ObjectRef object) {
			throw new UnsupportedOperationException();
		}

		@Override
		public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void delete(ObjectRef object) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ClaimResult claim(ClaimCommand command) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void purgeUserObjects(UserId userId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void cleanupTemp() {
			throw new UnsupportedOperationException();
		}

		@Override
		public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			throw new UnsupportedOperationException();
		}
	}

	static final class NoConsentStore implements ConsentStore {
		@Override
		public Consent insert(Consent consent) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<Consent> active(UserId userId, String purpose) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int revoke(UserId userId, String purpose, Instant revokedAt) {
			throw new UnsupportedOperationException();
		}
	}
}
