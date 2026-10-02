package com.aifashionvault.privacy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.aifashionvault.shared.ConsentId;
import com.aifashionvault.shared.UserId;

/**
 * Owner-scoped persistence for consent decisions over the existing {@code consent}
 * table (V010). Every read and write is scoped by owner, so a foreign user id can
 * never observe or revoke someone else's consent.
 *
 * <p>Withdrawal is a state transition on the existing grant, never a delete: the
 * historical grant (purpose + policy version + grantedAt + revokedAt) is the audit
 * record of what the user was told and when.</p>
 */
public interface ConsentStore {

	/** All grants for the owner, oldest first (history preserved). */
	List<Consent> list(UserId ownerId);

	/** The single active (non-revoked) grant for a purpose, if any. */
	Optional<Consent> active(UserId ownerId, ConsentPurpose purpose);

	/**
	 * Records a grant. Re-granting the same purpose with the same policy version is
	 * idempotent; a different version supersedes the active grant by revoking it
	 * before the new row is written.
	 */
	Consent grant(UserId ownerId, ConsentId consentId, ConsentPurpose purpose, String version, Instant now);

	/** Revokes the active grant for a purpose. Returns false when nothing was active. */
	boolean withdraw(UserId ownerId, ConsentPurpose purpose, Instant now);
}
