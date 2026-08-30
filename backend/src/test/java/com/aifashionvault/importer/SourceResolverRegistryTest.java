package com.aifashionvault.importer;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SourceResolverRegistryTest {

	private static final SourceReference SOURCE = new SourceReference(URI.create("https://example.test/item"), null);
	private static final StorageKey RESOLVED_KEY = new StorageKey("imports/private/resolved-object");

	@Test
	void reverseOrderRegistrationsExecuteByPrecedenceUntilFirstTerminalResult() {
		ConcurrentLinkedQueue<Integer> calls = new ConcurrentLinkedQueue<>();
		SourceResolverRegistry registry = new SourceResolverRegistry(List.of(
			registration("GENERIC_URL", 1000, calls, ResolutionStatus.RESOLVED),
			registration("GENERIC_OPEN_GRAPH", 900, calls, ResolutionStatus.RESOLVED),
			registration("SHARED_IMAGE", 100, calls, ResolutionStatus.UNSUPPORTED)));

		ResolutionResult result = new ImportResolver(registry.createResolvers(context(1, "attempt-one"))).resolve(SOURCE);

		assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(calls).containsExactly(100, 900);
	}

	@Test
	void rejectsDuplicateIdsAndPrecedence() {
		assertThatThrownBy(() -> new SourceResolverRegistry(List.of(
			registration("SHARED_IMAGE", 100), registration("GENERIC_OPEN_GRAPH", 900),
			registration("GENERIC_OPEN_GRAPH", 700), registration("GENERIC_URL", 1000))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SourceResolverRegistry(List.of(
			registration("SHARED_IMAGE", 100), registration("PARTNER_CATALOG", 900),
			registration("GENERIC_OPEN_GRAPH", 900), registration("GENERIC_URL", 1000))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void registrationRejectsInvalidFields() {
		assertThatThrownBy(() -> registration("lowercase", 400)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> registration("", 400)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SourceResolverRegistration("OPTIONAL", 99, context -> resolver(ResolutionStatus.UNSUPPORTED)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SourceResolverRegistration("OPTIONAL", 1001, context -> resolver(ResolutionStatus.UNSUPPORTED)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SourceResolverRegistration("OPTIONAL", 400, null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void contextRejectsNullAndBlankAttemptValues() {
		ResolutionContext valid = context(1, "attempt-one");
		assertThatThrownBy(() -> new ResolutionContext(null, valid.importId(), valid.attemptToken(), valid.mediaAccess()))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ResolutionContext(valid.ownerId(), null, valid.attemptToken(), valid.mediaAccess()))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ResolutionContext(valid.ownerId(), valid.importId(), " ", valid.mediaAccess()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ResolutionContext(valid.ownerId(), valid.importId(), valid.attemptToken(), null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void rejectsMissingOrWrongSharedImageLeader() {
		assertThatThrownBy(() -> new SourceResolverRegistry(List.of(
			registration("GENERIC_OPEN_GRAPH", 900), registration("GENERIC_URL", 1000))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SourceResolverRegistry(List.of(
			registration("PARTNER_CATALOG", 100), registration("SHARED_IMAGE", 200),
			registration("GENERIC_OPEN_GRAPH", 900), registration("GENERIC_URL", 1000))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsMissingOrWrongGenericUrlTerminal() {
		assertThatThrownBy(() -> new SourceResolverRegistry(List.of(
			registration("SHARED_IMAGE", 100), registration("GENERIC_OPEN_GRAPH", 900))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SourceResolverRegistry(List.of(
			registration("SHARED_IMAGE", 100), registration("GENERIC_OPEN_GRAPH", 900),
			registration("PARTNER_CATALOG", 1000), registration("GENERIC_URL", 950))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsEmptyRegistrationsWrongBuiltInPrecedenceAndOptionalOutsideReservedBand() {
		assertThatThrownBy(() -> new SourceResolverRegistry(List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SourceResolverRegistry(List.of(
			registration("SHARED_IMAGE", 100), registration("GENERIC_OPEN_GRAPH", 400),
			registration("GENERIC_URL", 1000))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SourceResolverRegistry(List.of(
			registration("SHARED_IMAGE", 100), registration("PARTNER_CATALOG", 900),
			registration("GENERIC_URL", 1000))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void concurrentContextsCreateDistinctResolversWithAttemptLocalState() {
		ConcurrentLinkedQueue<ContextResolver> created = new ConcurrentLinkedQueue<>();
		SourceResolverFactory factory = context -> {
			ContextResolver resolver = new ContextResolver(context);
			created.add(resolver);
			return resolver;
		};
		SourceResolverRegistry registry = new SourceResolverRegistry(List.of(
			registration("SHARED_IMAGE", 100),
			new SourceResolverRegistration("PARTNER_CATALOG", 400, factory),
			registration("GENERIC_OPEN_GRAPH", 900), registration("GENERIC_URL", 1000)));
		ResolutionContext first = context(1, "attempt-one");
		ResolutionContext second = context(2, "attempt-two");

		CompletableFuture<List<SourceResolver>> firstChain = CompletableFuture.supplyAsync(() -> registry.createResolvers(first));
		CompletableFuture<List<SourceResolver>> secondChain = CompletableFuture.supplyAsync(() -> registry.createResolvers(second));
		firstChain.join();
		secondChain.join();

		assertThat(created).hasSize(2);
		assertThat(created).extracting(ContextResolver::context).containsExactlyInAnyOrder(first, second);
		assertThat(created).extracting(System::identityHashCode).doesNotHaveDuplicates();
	}

	@Test
	void optionalUnsupportedFallsThroughAndOptionalSuccessSkipsLaterResolvers() {
		ConcurrentLinkedQueue<Integer> fallthroughCalls = new ConcurrentLinkedQueue<>();
		SourceResolverRegistry fallthrough = new SourceResolverRegistry(List.of(
			registration("SHARED_IMAGE", 100, fallthroughCalls, ResolutionStatus.UNSUPPORTED),
			registration("PARTNER_CATALOG", 400, fallthroughCalls, ResolutionStatus.UNSUPPORTED),
			registration("GENERIC_OPEN_GRAPH", 900, fallthroughCalls, ResolutionStatus.UNSUPPORTED),
			registration("GENERIC_URL", 1000, fallthroughCalls, ResolutionStatus.RESOLVED)));
		assertThat(new ImportResolver(fallthrough.createResolvers(context(1, "attempt-one"))).resolve(SOURCE).status())
			.isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(fallthroughCalls).containsExactly(100, 400, 900, 1000);

		ConcurrentLinkedQueue<Integer> successCalls = new ConcurrentLinkedQueue<>();
		SourceResolverRegistry success = new SourceResolverRegistry(List.of(
			registration("SHARED_IMAGE", 100, successCalls, ResolutionStatus.UNSUPPORTED),
			registration("PARTNER_CATALOG", 400, successCalls, ResolutionStatus.RESOLVED),
			registration("GENERIC_OPEN_GRAPH", 900, successCalls, ResolutionStatus.UNSUPPORTED),
			registration("GENERIC_URL", 1000, successCalls, ResolutionStatus.RESOLVED)));
		assertThat(new ImportResolver(success.createResolvers(context(1, "attempt-two"))).resolve(SOURCE).status())
			.isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(successCalls).containsExactly(100, 400);
	}

	private static SourceResolverRegistration registration(String id, int precedence) {
		return new SourceResolverRegistration(id, precedence, context -> resolver(ResolutionStatus.UNSUPPORTED));
	}

	private static SourceResolverRegistration registration(
			String id, int precedence, ConcurrentLinkedQueue<Integer> calls, ResolutionStatus status) {
		return new SourceResolverRegistration(id, precedence, context -> new SourceResolver() {
			@Override
			public boolean supports(SourceReference source) {
				return true;
			}

			@Override
			public ResolutionResult resolve(SourceReference source) {
				calls.add(precedence);
				return result(status);
			}
		});
	}

	private static SourceResolver resolver(ResolutionStatus status) {
		return new SourceResolver() {
			@Override
			public boolean supports(SourceReference source) {
				return true;
			}

			@Override
			public ResolutionResult resolve(SourceReference source) {
				return result(status);
			}
		};
	}

	private static ResolutionResult result(ResolutionStatus status) {
		return new ResolutionResult(status, status == ResolutionStatus.RESOLVED ? RESOLVED_KEY : null,
			null, null, null, null);
	}

	private static ResolutionContext context(int suffix, String attemptToken) {
		return new ResolutionContext(
			new UserId(new UUID(0, suffix)),
			new ShareImportId(new UUID(1, suffix)),
			attemptToken,
			mock(ImportMediaAccess.class));
	}

	private record ContextResolver(ResolutionContext context) implements SourceResolver {
		@Override
		public boolean supports(SourceReference source) {
			return true;
		}

		@Override
		public ResolutionResult resolve(SourceReference source) {
			return result(ResolutionStatus.UNSUPPORTED);
		}
	}
}
