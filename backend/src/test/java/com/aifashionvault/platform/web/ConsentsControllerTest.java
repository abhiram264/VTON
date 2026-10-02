package com.aifashionvault.platform.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import com.aifashionvault.privacy.Consent;
import com.aifashionvault.privacy.ConsentPurpose;
import com.aifashionvault.privacy.ConsentService;
import com.aifashionvault.privacy.ConsentStore;

import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C06: the consent surface must answer honestly.
 *
 * <p>It is driven here through the real {@link ConsentService} over an
 * in-memory {@link ConsentStore}, so {@code active} is produced by the same
 * primitive the server-side enforcement gate uses rather than by a test-local
 * assumption about it.</p>
 */
class ConsentsControllerTest {

	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UserId OTHER = new UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
	private static final String POLICY_VERSION = "2026-10-03";

	private static final String BODY_IMAGE = ConsentPurpose.BODY_IMAGE_PROCESSING.wireName();
	private static final String THIRD_PARTY = ConsentPurpose.THIRD_PARTY_AI_TRANSFER.wireName();

	/** Minimal in-memory store: the contract the service is written against. */
	private static final class MemoryConsentStore implements ConsentStore {
		private final List<Consent> rows = new ArrayList<>();

		@Override
		public Consent insert(Consent consent) {
			rows.add(consent);
			return consent;
		}

		@Override
		public Optional<Consent> active(UserId userId, String purpose) {
			for (int index = rows.size() - 1; index >= 0; index--) {
				Consent row = rows.get(index);
				if (row.userId().equals(userId) && row.purpose().equals(purpose) && row.revokedAt() == null) {
					return Optional.of(row);
				}
			}
			return Optional.empty();
		}

		@Override
		public int revoke(UserId userId, String purpose, Instant revokedAt) {
			int revoked = 0;
			for (int index = 0; index < rows.size(); index++) {
				Consent row = rows.get(index);
				if (row.userId().equals(userId) && row.purpose().equals(purpose) && row.revokedAt() == null) {
					rows.set(index, new Consent(row.id(), row.userId(), row.purpose(), row.version(),
						row.grantedAt(), revokedAt));
					revoked++;
				}
			}
			return revoked;
		}
	}

	private static ConsentService service(ConsentStore store) {
		return new ConsentService(store, Clock.fixed(NOW, ZoneOffset.UTC), POLICY_VERSION);
	}

	private static ConsentsController controller(ConsentStore store) {
		ConsentService service = service(store);
		return new ConsentsController(provider(service), provider(store));
	}

	private static <T> ObjectProvider<T> provider(T value) {
		return new ObjectProvider<>() {
			@Override
			public T getObject(Object... args) {
				return value;
			}

			@Override
			public T getIfAvailable() {
				return value;
			}

			@Override
			public T getIfUnique() {
				return value;
			}

			@Override
			public T getObject() {
				return value;
			}
		};
	}

	private static <T> ObjectProvider<T> emptyProvider() {
		return new ObjectProvider<>() {
			@Override
			public T getObject(Object... args) {
				throw new IllegalStateException("absent");
			}

			@Override
			public T getIfAvailable() {
				return null;
			}

			@Override
			public T getIfUnique() {
				return null;
			}

			@Override
			public T getObject() {
				throw new IllegalStateException("absent");
			}
		};
	}

	@Test
	void everySupportedPurposeIsReportedWithTheServersPolicyVersion() {
		ConsentsController controller = controller(new MemoryConsentStore());

		ConsentsController.ConsentsResponse response = controller.list(OWNER);

		assertThat(response.policyVersion()).isEqualTo(POLICY_VERSION);
		assertThat(response.purposes()).extracting(ConsentsController.ConsentState::purpose)
			.containsExactlyInAnyOrder(BODY_IMAGE, THIRD_PARTY);
		assertThat(response.purposes()).allSatisfy(state -> assertThat(state.active()).isFalse());
	}

	@Test
	void grantRecordsThePolicyVersionAndReadsBackActive() {
		ConsentsController controller = controller(new MemoryConsentStore());

		ConsentsController.ConsentState granted = controller.grant(OWNER, BODY_IMAGE);

		assertThat(granted.active()).isTrue();
		assertThat(granted.version()).isEqualTo(POLICY_VERSION);
		assertThat(granted.grantedAt()).isEqualTo(NOW);
		assertThat(granted.policyVersion()).isEqualTo(POLICY_VERSION);

		ConsentsController.ConsentsResponse read = controller.list(OWNER);
		assertThat(read.purposes()).filteredOn(state -> BODY_IMAGE.equals(state.purpose()))
			.singleElement()
			.satisfies(state -> {
				assertThat(state.active()).isTrue();
				assertThat(state.version()).isEqualTo(POLICY_VERSION);
			});
	}

	@Test
	void grantingTheSamePurposeTwiceStaysOneActiveConsent() {
		MemoryConsentStore store = new MemoryConsentStore();
		ConsentsController controller = controller(store);

		controller.grant(OWNER, BODY_IMAGE);
		ConsentsController.ConsentState second = controller.grant(OWNER, BODY_IMAGE);

		assertThat(second.active()).isTrue();
		assertThat(store.rows.stream().filter(row -> row.revokedAt() == null).count()).isEqualTo(1);
	}

	@Test
	void withdrawMakesThePurposeInactiveAndRepeatingItIsIdempotent() {
		ConsentsController controller = controller(new MemoryConsentStore());
		controller.grant(OWNER, BODY_IMAGE);

		ConsentsController.ConsentState withdrawn = controller.withdraw(OWNER, BODY_IMAGE);
		ConsentsController.ConsentState again = controller.withdraw(OWNER, BODY_IMAGE);

		assertThat(withdrawn.active()).isFalse();
		assertThat(withdrawn.version()).isNull();
		assertThat(again.active()).isFalse();
	}

	@Test
	void aSecondOwnerSeesOnlyTheirOwnConsent() {
		ConsentsController controller = controller(new MemoryConsentStore());
		controller.grant(OWNER, BODY_IMAGE);

		assertThat(controller.list(OTHER).purposes()).allSatisfy(state -> assertThat(state.active()).isFalse());
		assertThat(controller.list(OWNER).purposes()).filteredOn(state -> BODY_IMAGE.equals(state.purpose()))
			.singleElement()
			.satisfies(state -> assertThat(state.active()).isTrue());
	}

	@Test
	void anUnknownPurposeIsRejectedAndChangesNothing() {
		MemoryConsentStore store = new MemoryConsentStore();
		ConsentsController controller = controller(store);

		assertThatThrownBy(() -> controller.grant(OWNER, "marketing_emails"))
			.isInstanceOf(RuntimeException.class);

		assertThat(controller.unknownPurpose().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(controller.unknownPurpose().getBody().code()).isEqualTo(ConsentsController.CONSENT_PURPOSE_UNKNOWN);
		assertThat(store.rows).isEmpty();
	}

	@Test
	void anUnconfiguredSurfaceReportsUnavailableRatherThanGranted() {
		ConsentsController controller = new ConsentsController(emptyProvider(), emptyProvider());

		assertThatThrownBy(() -> controller.list(OWNER)).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> controller.grant(OWNER, BODY_IMAGE)).isInstanceOf(RuntimeException.class);

		assertThat(controller.unavailable().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(controller.unavailable().getBody().code()).isEqualTo(ConsentsController.CONSENT_UNAVAILABLE);
	}

	@Test
	void aGrantRecordsThePurposeAndTheServiceClock() {
		MemoryConsentStore store = new MemoryConsentStore();
		controller(store).grant(OWNER, THIRD_PARTY);

		assertThat(store.rows).singleElement().satisfies(row -> {
			assertThat(row.purpose()).isEqualTo(THIRD_PARTY);
			assertThat(row.grantedAt()).isEqualTo(NOW);
			assertThat(row.revokedAt()).isNull();
			assertThat(row.id()).isNotNull();
		});
	}
}
