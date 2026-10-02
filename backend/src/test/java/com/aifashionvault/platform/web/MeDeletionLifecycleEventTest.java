package com.aifashionvault.platform.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import com.aifashionvault.analytics.AnalyticsEventName;
import com.aifashionvault.analytics.ProductMetricContext;
import com.aifashionvault.analytics.ProductMetrics;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.user.IdentityProvisioning;
import com.aifashionvault.user.IdentityResolution;
import com.aifashionvault.user.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C22 clause 1, deletion family: an accepted deletion request is recorded through
 * the existing analytics seam, and the payload carries nothing that identifies the
 * person or their content.
 */
class MeDeletionLifecycleEventTest {

	private static final UserId USER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

	@Test
	void anAcceptedDeletionRequestRecordsExactlyOneCoarseEvent() {
		RecordingMetrics metrics = new RecordingMetrics();
		RecordingProvisioning provisioning = new RecordingProvisioning();
		MeController controller = new MeController(providerOf(provisioning), providerOf(metrics));

		controller.delete(USER);

		assertThat(provisioning.deletions).hasSize(1);
		assertThat(metrics.events).hasSize(1);
		assertThat(metrics.events.get(0).name).isEqualTo(AnalyticsEventName.DELETION_REQUESTED);
		// The event carries the instant the request was accepted, and nothing else.
		ProductMetricContext context = metrics.events.get(0).context;
		assertThat(context.occurredAt()).isEqualTo(provisioning.deletions.get(0));
		assertThat(context.outcome()).isEqualTo(MeController.DELETION_REQUESTED_OUTCOME);
		assertThat(context.provider()).isNull();
		assertThat(context.model()).isNull();
		assertThat(context.cost()).isNull();
		assertThat(context.itemCount()).isNull();
	}

	@Test
	void theEventPayloadNeverCarriesTheUserIdOrAnyIdentifier() {
		RecordingMetrics metrics = new RecordingMetrics();
		MeController controller = new MeController(providerOf(new RecordingProvisioning()), providerOf(metrics));

		controller.delete(USER);

		ProductMetricContext context = metrics.events.get(0).context;
		assertThat(context.toString()).doesNotContain(USER.value().toString());
		assertThat(context.toString()).doesNotContain("http");
	}

	@Test
	void aRequestWithoutAProvisioningSeamRecordsNothing() {
		RecordingMetrics metrics = new RecordingMetrics();
		MeController controller = new MeController(absentProvider(), providerOf(metrics));

		assertThatThrownBy(() -> controller.delete(USER)).isInstanceOf(ResponseStatusException.class);

		assertThat(metrics.events).isEmpty();
	}

	@Test
	void aMetricsFailureNeverFailsTheDeletionRequest() {
		ProductMetrics failing = (name, context) -> {
			throw new IllegalStateException("metrics backend down");
		};
		RecordingProvisioning provisioning = new RecordingProvisioning();
		MeController controller = new MeController(providerOf(provisioning), providerOf(failing));

		controller.delete(USER);

		// The deletion still happened; analytics is an observer, not a gate.
		assertThat(provisioning.deletions).hasSize(1);
	}

	@Test
	void aDeploymentWithoutAMetricsBeanStillDeletes() {
		RecordingProvisioning provisioning = new RecordingProvisioning();
		MeController controller = new MeController(providerOf(provisioning), absentProvider());

		controller.delete(USER);

		assertThat(provisioning.deletions).hasSize(1);
	}

	private record Recorded(AnalyticsEventName name, ProductMetricContext context) {
	}

	private static final class RecordingMetrics implements ProductMetrics {

		private final List<Recorded> events = new ArrayList<>();

		@Override
		public void record(AnalyticsEventName eventName, ProductMetricContext context) {
			events.add(new Recorded(eventName, context));
		}
	}

	private static final class RecordingProvisioning implements IdentityProvisioning {

		private final List<Instant> deletions = new ArrayList<>();

		@Override
		public IdentityResolution resolve(String issuer, String subject) {
			throw new UnsupportedOperationException("resolve is not exercised by this test");
		}

		@Override
		public User beginDeletion(UserId userId, Instant deletedAt) {
			deletions.add(deletedAt);
			return null;
		}
	}

	private static <T> ObjectProvider<T> providerOf(T value) {
		return new ObjectProvider<>() {
			@Override
			public T getObject() {
				return value;
			}

			@Override
			public T getObject(Object... args) {
				return value;
			}

			@Override
			public T getIfAvailable() {
				return value;
			}
		};
	}

	private static <T> ObjectProvider<T> absentProvider() {
		return new ObjectProvider<>() {
			@Override
			public T getObject() {
				return null;
			}

			@Override
			public T getObject(Object... args) {
				return null;
			}
		};
	}
}
