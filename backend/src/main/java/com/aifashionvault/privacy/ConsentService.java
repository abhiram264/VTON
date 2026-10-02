package com.aifashionvault.privacy;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.aifashionvault.shared.ConsentId;
import com.aifashionvault.shared.UserId;

/**
 * The consent surface: read, grant and withdraw the bounded {@link ConsentPurpose}
 * set, recording the policy version the user actually agreed to. A withdrawal is
 * distinguishable from a purpose that was never granted ({@link #withdraw} returns
 * false), which is what makes "withdrawn" reportable honestly.
 */
public final class ConsentService {

	/** Policy versions are short, opaque release identifiers, never free text. */
	static final int MAX_VERSION_LENGTH = 64;

	private final ConsentStore store;
	private final Clock clock;

	public ConsentService(ConsentStore store, Clock clock) {
		this.store = Objects.requireNonNull(store, "consent store is required");
		this.clock = Objects.requireNonNull(clock, "clock is required");
	}

	public List<Consent> list(UserId ownerId) {
		return store.list(requireOwner(ownerId));
	}

	public Consent grant(UserId ownerId, ConsentPurpose purpose, String policyVersion) {
		UserId owner = requireOwner(ownerId);
		ConsentPurpose required = Objects.requireNonNull(purpose, "consent purpose is required");
		return store.grant(owner, new ConsentId(UUID.randomUUID()), required, requireVersion(policyVersion), clock.instant());
	}

	/** Returns true when an active grant was revoked; false when nothing was active. */
	public boolean withdraw(UserId ownerId, ConsentPurpose purpose) {
		UserId owner = requireOwner(ownerId);
		ConsentPurpose required = Objects.requireNonNull(purpose, "consent purpose is required");
		return store.withdraw(owner, required, clock.instant());
	}

	public boolean isActive(UserId ownerId, ConsentPurpose purpose) {
		return store.active(requireOwner(ownerId), Objects.requireNonNull(purpose, "consent purpose is required")).isPresent();
	}

	/** The gate: throws unless the owner holds an active grant for the purpose. */
	public Consent requireActive(UserId ownerId, ConsentPurpose purpose) {
		ConsentPurpose required = Objects.requireNonNull(purpose, "consent purpose is required");
		return store.active(requireOwner(ownerId), required)
			.orElseThrow(() -> new ConsentRequiredException(required));
	}

	static String requireVersion(String policyVersion) {
		if (policyVersion == null) {
			throw new IllegalArgumentException("policy version is required");
		}
		String normalized = policyVersion.strip();
		if (normalized.isEmpty() || normalized.length() > MAX_VERSION_LENGTH) {
			throw new IllegalArgumentException("policy version must be 1-" + MAX_VERSION_LENGTH + " characters");
		}
		return normalized;
	}

	private static UserId requireOwner(UserId ownerId) {
		return Objects.requireNonNull(ownerId, "consent owner is required");
	}
}
