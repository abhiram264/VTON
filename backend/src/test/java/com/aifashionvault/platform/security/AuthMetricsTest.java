package com.aifashionvault.platform.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthMetricsTest {

	private SimpleMeterRegistry registry;
	private AuthMetrics metrics;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		metrics = new AuthMetrics(registry);
	}

	@Test
	void constructorRequiresNonNullRegistry() {
		assertThatThrownBy(() -> new AuthMetrics(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("registry");
	}

	@Test
	void recordAuthSuccessIncrementsUntaggedCounter() {
		metrics.recordAuthSuccess();

		Counter counter = registry.find(AuthMetrics.AUTH_SUCCESS).counter();
		assertThat(counter).isNotNull();
		assertThat(counter.count()).isEqualTo(1.0);
		assertThat(counter.getId().getTags()).isEmpty();
	}

	@Test
	void recordAuthFailureRecordsBoundedReasons() {
		metrics.recordAuthFailure(AuthMetrics.REASON_MISSING);
		metrics.recordAuthFailure(AuthMetrics.REASON_MALFORMED);
		metrics.recordAuthFailure(AuthMetrics.REASON_REJECTED);

		Counter missing = registry.find(AuthMetrics.AUTH_FAILURE)
			.tag("reason", AuthMetrics.REASON_MISSING)
			.counter();
		assertThat(missing).isNotNull();
		assertThat(missing.count()).isEqualTo(1.0);

		Counter malformed = registry.find(AuthMetrics.AUTH_FAILURE)
			.tag("reason", AuthMetrics.REASON_MALFORMED)
			.counter();
		assertThat(malformed).isNotNull();
		assertThat(malformed.count()).isEqualTo(1.0);

		Counter rejected = registry.find(AuthMetrics.AUTH_FAILURE)
			.tag("reason", AuthMetrics.REASON_REJECTED)
			.counter();
		assertThat(rejected).isNotNull();
		assertThat(rejected.count()).isEqualTo(1.0);
	}

	@Test
	void recordAuthFailureRejectsUnboundedReasons() {
		assertThatThrownBy(() -> metrics.recordAuthFailure(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordAuthFailure(""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordAuthFailure("   "))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordAuthFailure("EXPIRED"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordAuthFailure("INVALID"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordAuthFailure("Bearer eyJhbGciOiJIUzI1NiJ9"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordAuthFailure("UNBOUNDED_REASON"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void meterRegistryExceptionDuringSuccessIsSwallowed() {
		AuthMetrics safeMetrics = new AuthMetrics(new ThrowingMeterRegistry());

		safeMetrics.recordAuthSuccess();
	}

	@Test
	void meterRegistryExceptionDuringFailureIsSwallowed() {
		AuthMetrics safeMetrics = new AuthMetrics(new ThrowingMeterRegistry());

		safeMetrics.recordAuthFailure(AuthMetrics.REASON_MISSING);
		safeMetrics.recordAuthFailure(AuthMetrics.REASON_MALFORMED);
		safeMetrics.recordAuthFailure(AuthMetrics.REASON_REJECTED);
	}

	private static final class ThrowingMeterRegistry extends SimpleMeterRegistry {
		@Override
		protected Counter newCounter(Meter.Id id) {
			throw new RuntimeException("Registry boom");
		}
	}

	@Test
	void redactionCanaryAssertsNoSensitiveValuesInMeterNamesOrTags() {
		metrics.recordAuthSuccess();
		metrics.recordAuthFailure(AuthMetrics.REASON_MISSING);
		metrics.recordAuthFailure(AuthMetrics.REASON_MALFORMED);
		metrics.recordAuthFailure(AuthMetrics.REASON_REJECTED);

		for (Meter meter : registry.getMeters()) {
			Meter.Id id = meter.getId();
			String name = id.getName();
			assertThat(name).doesNotContain("token", "subject", "issuer", "secret", "bearer", "authorization");
			for (Tag tag : id.getTags()) {
				assertThat(tag.getKey()).isEqualTo("reason");
				assertThat(tag.getValue()).isIn("MISSING", "MALFORMED", "REJECTED");
				assertThat(tag.getValue()).doesNotContain("token", "subject", "issuer", "secret", "bearer", "authorization");
			}
		}
	}
}
