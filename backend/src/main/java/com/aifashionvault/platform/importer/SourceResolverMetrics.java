package com.aifashionvault.platform.importer;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

import com.aifashionvault.importer.ResolutionResult;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.importer.SourceResolver;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Privacy-safe, low-cardinality metrics around an invoked source resolver. */
public final class SourceResolverMetrics {

	public static final String OUTCOME = "share.import.resolver.outcome";
	public static final String DURATION = "share.import.resolver.duration";

	private static final Pattern VALID_RESOLVER_ID = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

	private final MeterRegistry registry;

	public SourceResolverMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	public SourceResolver decorate(String resolverId, SourceResolver delegate) {
		if (resolverId == null || !VALID_RESOLVER_ID.matcher(resolverId).matches()) {
			throw new IllegalArgumentException("resolverId must match [A-Z][A-Z0-9_]{0,63}");
		}
		Objects.requireNonNull(delegate, "delegate");
		if (registry == null) {
			return delegate;
		}
		return new InstrumentedResolver(resolverId, delegate);
	}

	private final class InstrumentedResolver implements SourceResolver {

		private final String resolverId;
		private final SourceResolver delegate;

		private InstrumentedResolver(String resolverId, SourceResolver delegate) {
			this.resolverId = resolverId;
			this.delegate = delegate;
		}

		@Override
		public boolean supports(SourceReference source) {
			return delegate.supports(source);
		}

		@Override
		public ResolutionResult resolve(SourceReference source) {
			long started = System.nanoTime();
			try {
				ResolutionResult result = delegate.resolve(source);
				record(result.status().name(), result.reason() == null ? "NONE" : result.reason().name(), started);
				return result;
			}
			catch (RuntimeException | Error failure) {
				record("EXCEPTION", "NONE", started);
				throw failure;
			}
		}

		private void record(String status, String reason, long started) {
			try {
				Counter.builder(OUTCOME)
					.tags("resolver", resolverId, "status", status, "reason", reason)
					.register(registry)
					.increment();
				Timer.builder(DURATION)
					.tags("resolver", resolverId, "status", status)
					.register(registry)
					.record(Duration.ofNanos(Math.max(0, System.nanoTime() - started)));
			}
			catch (RuntimeException ignored) {
				// Resolution must not depend on observability availability.
			}
		}
	}
}
