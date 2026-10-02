package com.aifashionvault.privacy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.aifashionvault.shared.UserId;

/**
 * Durable consent records over the existing V010 {@code consent} table.
 *
 * <p>Consent is a per-purpose grant with a recorded policy version, not a
 * boolean on the user: the user record deliberately carries no consent
 * component (pinned by ConsentTest), so a grant can name exactly what it
 * covers and a later policy revision is a distinguishable record.</p>
 */
public interface ConsentStore {

	/** Inserts one grant record. Callers dedupe; this is the raw write. */
	Consent insert(Consent consent);

	/** The newest non-revoked grant for this owner and purpose, if any. */
	Optional<Consent> active(UserId userId, String purpose);

	/** Every grant for the owner, oldest first, so the surface can show the history. */
	List<Consent> list(UserId userId);

	/**
	 * Revokes every non-revoked grant for this owner and purpose.
	 *
	 * @return the number of records revoked; 0 means nothing was active
	 */
	int revoke(UserId userId, String purpose, Instant revokedAt);
}
