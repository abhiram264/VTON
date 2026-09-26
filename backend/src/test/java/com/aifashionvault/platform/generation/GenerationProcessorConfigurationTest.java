package com.aifashionvault.platform.generation;

import java.time.Duration;


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
}
