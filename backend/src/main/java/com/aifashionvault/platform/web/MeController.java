package com.aifashionvault.platform.web;

import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.aifashionvault.analytics.AnalyticsEventName;
import com.aifashionvault.analytics.ProductMetricContext;
import com.aifashionvault.analytics.ProductMetrics;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.user.IdentityProvisioning;

@RestController
@RequestMapping("/me")
public class MeController {

	/**
	 * Coarse outcome label for the deletion lifecycle event. Deliberately not the
	 * user id, email or any media reference: the payload of a lifecycle event must
	 * never identify a person or leak content (C22 clause 2).
	 */
	static final String DELETION_REQUESTED_OUTCOME = "deletion_requested";

	private final ObjectProvider<IdentityProvisioning> identityProvisioning;
	private final ObjectProvider<ProductMetrics> productMetrics;

	public MeController(ObjectProvider<IdentityProvisioning> identityProvisioning) {
		this(identityProvisioning, null);
	}

	@Autowired
	public MeController(
		ObjectProvider<IdentityProvisioning> identityProvisioning,
		ObjectProvider<ProductMetrics> productMetrics
	) {
		this.identityProvisioning = identityProvisioning;
		this.productMetrics = productMetrics;
	}

	@GetMapping
	public MeResponse me(@AuthenticationPrincipal UserId userId) {
		return new MeResponse(userId.value().toString());
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserId userId) {
		IdentityProvisioning provisioning = this.identityProvisioning.getIfAvailable();
		if (provisioning == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		Instant requestedAt = Instant.now();
		provisioning.beginDeletion(userId, requestedAt);
		recordDeletionRequested(requestedAt);
	}

	/**
	 * Records the accepted deletion request through the existing analytics seam.
	 * Best-effort by design: analytics is an observer of a privacy operation, so a
	 * missing bean or a metrics failure must never turn a successful deletion into
	 * an error for the user. Only reached after {@code beginDeletion} returned, so a
	 * rejected or unauthorized request records nothing.
	 */
	private void recordDeletionRequested(Instant requestedAt) {
		ProductMetrics metrics = productMetrics == null ? null : productMetrics.getIfAvailable();
		if (metrics == null) {
			return;
		}
		try {
			metrics.record(AnalyticsEventName.DELETION_REQUESTED,
				new ProductMetricContext(requestedAt, DELETION_REQUESTED_OUTCOME, null, null, null, null, null, null, null));
		}
		catch (RuntimeException ignored) {
			// Observability must not fail the operation it observes.
		}
	}
}
