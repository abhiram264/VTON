package com.aifashionvault.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * C19: the admission caps are configuration, and the configuration reaches the
 * store that enforces them. The DataSource below is never connected to: the
 * store's constructor only wraps it in a JdbcTemplate.
 */
class AdmissionCapsPropertiesTest {

	private static final DataSource UNCONNECTED =
		new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/never-connected");

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(AdmissionCapsProperties.Registrar.class, GenerationConfiguration.class)
		.withBean(DataSource.class, () -> UNCONNECTED);

	@Test
	void unsetConfigurationReproducesTheShippedCaps() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			AdmissionCapsProperties caps = context.getBean(AdmissionCapsProperties.class);
			assertThat(caps.toPolicy()).isEqualTo(new AdmissionPolicy(5, 2, 6));
			assertThat(caps.toPolicy()).isEqualTo(AdmissionPolicy.DEFAULT);
			JdbcGenerationStore store = context.getBeanProvider(JdbcGenerationStore.class).getIfAvailable();
			assertThat(store).isNotNull();
			assertThat(store.admissionPolicy()).isEqualTo(AdmissionPolicy.DEFAULT);
		});
	}

	@Test
	void configuredCapsBindAndReachTheStore() {
		runner.withPropertyValues(
				"vault.generation.admission.accepted-jobs-per-24-hours=9",
				"vault.generation.admission.max-active-jobs=4",
				"vault.generation.admission.max-products-per-job=1")
			.run(context -> {
				assertThat(context).hasNotFailed();
				AdmissionCapsProperties caps = context.getBean(AdmissionCapsProperties.class);
				assertThat(caps.getAcceptedJobsPer24Hours()).isEqualTo(9);
				assertThat(caps.getMaxActiveJobs()).isEqualTo(4);
				assertThat(caps.getMaxProductsPerJob()).isEqualTo(1);
				JdbcGenerationStore store = context.getBeanProvider(JdbcGenerationStore.class).getIfAvailable();
				assertThat(store).isNotNull();
				assertThat(store.admissionPolicy()).isEqualTo(new AdmissionPolicy(9, 4, 1));
				// The configured cap is the one the store consults, so a two-item
				// outfit is refused before any provider spend.
				assertThat(store.admissionPolicy().allowsProductCount(2)).isFalse();
			});
	}

	@Test
	void storeWithoutThePropertiesRegistrarKeepsTheShippedDefaults() {
		new ApplicationContextRunner()
			.withUserConfiguration(GenerationConfiguration.class)
			.withBean(DataSource.class, () -> UNCONNECTED)
			.run(context -> {
				assertThat(context).hasNotFailed();
				JdbcGenerationStore store = context.getBeanProvider(JdbcGenerationStore.class).getIfAvailable();
				assertThat(store).isNotNull();
				assertThat(store.admissionPolicy()).isEqualTo(AdmissionPolicy.DEFAULT);
			});
	}

	@Test
	void outOfRangeCapsCannotReachTheStore() {
		AdmissionCapsProperties caps = new AdmissionCapsProperties();
		caps.setMaxProductsPerJob(0);
		assertThatThrownBy(caps::toPolicy).isInstanceOf(IllegalArgumentException.class);
		caps.setMaxProductsPerJob(6);
		caps.setAcceptedJobsPer24Hours(0);
		assertThatThrownBy(caps::toPolicy).isInstanceOf(IllegalArgumentException.class);
		caps.setAcceptedJobsPer24Hours(5);
		caps.setMaxActiveJobs(0);
		assertThatThrownBy(caps::toPolicy).isInstanceOf(IllegalArgumentException.class);
	}
}
