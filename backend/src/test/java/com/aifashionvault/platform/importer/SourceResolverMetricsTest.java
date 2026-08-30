package com.aifashionvault.platform.importer;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.aifashionvault.importer.ResolutionReason;
import com.aifashionvault.importer.ResolutionResult;
import com.aifashionvault.importer.ResolutionStatus;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.importer.SourceResolver;
import com.aifashionvault.shared.StorageKey;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceResolverMetricsTest {

	private static final SourceReference SOURCE =
		new SourceReference(URI.create("https://example.test/item"), null);

	@ParameterizedTest
	@MethodSource("outcomes")
	void recordsExactlyOneOutcomeAndDurationWithFrozenTags(ResolutionResult result) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		SourceResolver decorated = new SourceResolverMetrics(registry).decorate("GENERIC_OPEN_GRAPH", resolver(true, result));

		assertThat(decorated.supports(SOURCE)).isTrue();
		assertThat(decorated.resolve(SOURCE)).isSameAs(result);

		String reason = result.reason() == null ? "NONE" : result.reason().name();
		assertThat(registry.find(SourceResolverMetrics.OUTCOME)
			.tags("resolver", "GENERIC_OPEN_GRAPH", "status", result.status().name(), "reason", reason)
			.counter().count()).isEqualTo(1);
		assertThat(registry.find(SourceResolverMetrics.DURATION)
			.tags("resolver", "GENERIC_OPEN_GRAPH", "status", result.status().name())
			.timer().count()).isEqualTo(1);
		assertThat(registry.getMeters()).hasSize(2);
		assertFrozenTagKeys(registry.getMeters());
	}

	@Test
	void supportsFalseEmitsNoMetric() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		SourceResolver decorated = new SourceResolverMetrics(registry).decorate("GENERIC_URL", resolver(false, unsupported()));

		assertThat(decorated.supports(SOURCE)).isFalse();

		assertThat(registry.getMeters()).isEmpty();
	}

	@Test
	void exceptionRecordsBoundedStatusAndRethrowsSameInstance() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		IllegalStateException failure = new IllegalStateException("secret.example.test/object-key");
		SourceResolver delegate = new SourceResolver() {
			@Override
			public boolean supports(SourceReference source) {
				return true;
			}

			@Override
			public ResolutionResult resolve(SourceReference source) {
				throw failure;
			}
		};
		SourceResolver decorated = new SourceResolverMetrics(registry).decorate("SHARED_IMAGE", delegate);

		assertThatThrownBy(() -> decorated.resolve(SOURCE)).isSameAs(failure);
		assertThat(registry.find(SourceResolverMetrics.OUTCOME)
			.tags("resolver", "SHARED_IMAGE", "status", "EXCEPTION", "reason", "NONE")
			.counter().count()).isEqualTo(1);
		assertThat(registry.find(SourceResolverMetrics.DURATION)
			.tags("resolver", "SHARED_IMAGE", "status", "EXCEPTION")
			.timer().count()).isEqualTo(1);
		assertThat(registry.getMeters().toString()).doesNotContain("secret.example.test", "object-key");
		assertFrozenTagKeys(registry.getMeters());
	}

	@Test
	void absentRegistryIsNoOpAndInvalidResolverIdsAreRejected() {
		SourceResolver delegate = resolver(true, unsupported());
		SourceResolver decorated = new SourceResolverMetrics(null).decorate("GENERIC_URL", delegate);

		assertThat(decorated).isSameAs(delegate);
		assertThatThrownBy(() -> new SourceResolverMetrics(new SimpleMeterRegistry())
			.decorate("resolver-from-class-name", delegate))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static Stream<ResolutionResult> outcomes() {
		return Stream.of(
			new ResolutionResult(ResolutionStatus.RESOLVED, new StorageKey("safe/test.jpg"), null, null, null, null),
			new ResolutionResult(ResolutionStatus.NEEDS_IMAGE, null, null, null, null,
				ResolutionReason.URL_RESOLUTION_FAILED),
			new ResolutionResult(ResolutionStatus.RETRYABLE_FAILURE, null, null, null, null,
				ResolutionReason.DEPENDENCY_UNAVAILABLE),
			new ResolutionResult(ResolutionStatus.TERMINAL_FAILURE, null, null, null, null,
				ResolutionReason.INVALID_SOURCE),
			unsupported());
	}

	private static SourceResolver resolver(boolean supported, ResolutionResult result) {
		return new SourceResolver() {
			@Override
			public boolean supports(SourceReference source) {
				return supported;
			}

			@Override
			public ResolutionResult resolve(SourceReference source) {
				return result;
			}
		};
	}

	private static ResolutionResult unsupported() {
		return new ResolutionResult(ResolutionStatus.UNSUPPORTED, null, null, null, null, null);
	}

	private static void assertFrozenTagKeys(List<Meter> meters) {
		for (Meter meter : meters) {
			List<String> keys = meter.getId().getTags().stream().map(Tag::getKey).toList();
			if (SourceResolverMetrics.OUTCOME.equals(meter.getId().getName())) {
				assertThat(keys).containsExactly("reason", "resolver", "status");
			} else {
				assertThat(keys).containsExactly("resolver", "status");
			}
		}
	}
}
