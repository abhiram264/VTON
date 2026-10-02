package com.aifashionvault.privacy;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.aifashionvault.shared.ConsentId;
import com.aifashionvault.shared.UserId;

/**
 * Consent lifecycle: record a grant naming its purpose and policy version,
 * withdraw it, and answer whether the owner currently holds it.
 *
 * <p>Enforcement belongs at the processing boundary, not here: this service
 * only answers {@link #isActive}. A caller that transfers a body image to a
 * third party must consult it first, so withdrawal takes effect for work
 * admitted after the withdrawal rather than only for newly uploaded photos.</p>
 *
 * <p>Idempotency: granting a purpose the owner already holds at the same
 * policy version returns the existing record instead of appending a duplicate.
 * Granting a different version is a new record, so a policy revision is
 * distinguishable from the version the owner actually agreed to. Withdrawal
 * marks the existing record revoked rather than deleting it — the grant
 * history is the evidence that consent was held at the time it was used.</p>
 */
public final class ConsentService {

	private final ConsentStore store;
	private final Clock clock;
	private final String policyVersion;

	public ConsentService(ConsentStore store, Clock clock, String policyVersion) {
		this.store = Objects.requireNonNull(store, "store is required");
		this.clock = clock == null ? Clock.systemUTC() : clock;
		if (policyVersion == null || policyVersion.isBlank()) {
			throw new IllegalArgumentException("policyVersion is required");
		}
		this.policyVersion = policyVersion.strip();
	}

	public String policyVersion() {
		return this.policyVersion;
	}

	/** Records the grant at the service's current policy version. */
	public Consent grant(UserId userId, ConsentPurpose purpose) {
		return grant(userId, purpose, this.policyVersion);
	}

	public Consent grant(UserId userId, ConsentPurpose purpose, String version) {
		Objects.requireNonNull(userId, "userId is required");
		Objects.requireNonNull(purpose, "purpose is required");
		if (version == null || version.isBlank()) {
			throw new IllegalArgumentException("version is required");
		}
		String trimmed = version.strip();
		return this.store.active(userId, purpose.wireName())
			.filter(existing -> existing.version().equals(trimmed))
			.orElseGet(() -> this.store.insert(new Consent(
				new ConsentId(UUID.randomUUID()),
				userId,
				purpose.wireName(),
				trimmed,
				Instant.now(this.clock),
				null)));
	}

	/** @return true when a grant existed and is now withdrawn */
	public boolean withdraw(UserId userId, ConsentPurpose purpose) {
		Objects.requireNonNull(userId, "userId is required");
		Objects.requireNonNull(purpose, "purpose is required");
		return this.store.revoke(userId, purpose.wireName(), Instant.now(this.clock)) > 0;
	}

	public boolean isActive(UserId userId, ConsentPurpose purpose) {
		if (userId == null || purpose == null) {
			return false;
		}
		return this.store.active(userId, purpose.wireName()).isPresent();
	}
}
