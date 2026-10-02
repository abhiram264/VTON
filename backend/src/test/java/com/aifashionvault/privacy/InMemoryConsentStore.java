package com.aifashionvault.privacy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.aifashionvault.shared.ConsentId;
import com.aifashionvault.shared.UserId;

/** Minimal owner-scoped store double with the same semantics as {@link JdbcConsentStore}. */
final class InMemoryConsentStore implements ConsentStore {

	private final List<Consent> rows = new ArrayList<>();

	@Override
	public List<Consent> list(UserId ownerId) {
		return rows.stream().filter(row -> row.userId().equals(ownerId)).toList();
	}

	@Override
	public Optional<Consent> active(UserId ownerId, ConsentPurpose purpose) {
		return rows.stream()
			.filter(row -> row.userId().equals(ownerId))
			.filter(row -> row.purpose().equals(purpose.wireName()))
			.filter(row -> row.revokedAt() == null)
			.reduce((first, second) -> second);
	}

	@Override
	public Consent grant(UserId ownerId, ConsentId consentId, ConsentPurpose purpose, String version, Instant now) {
		Optional<Consent> current = active(ownerId, purpose);
		if (current.isPresent()) {
			if (current.get().version().equals(version)) {
				return current.get();
			}
			int index = rows.indexOf(current.get());
			Consent superseded = current.get();
			rows.set(index, new Consent(superseded.id(), superseded.userId(), superseded.purpose(),
				superseded.version(), superseded.grantedAt(), now));
		}
		Consent granted = new Consent(consentId, ownerId, purpose.wireName(), version, now, null);
		rows.add(granted);
		return granted;
	}

	@Override
	public boolean withdraw(UserId ownerId, ConsentPurpose purpose, Instant now) {
		Optional<Consent> current = active(ownerId, purpose);
		if (current.isEmpty()) {
			return false;
		}
		int index = rows.indexOf(current.get());
		Consent revoked = current.get();
		rows.set(index, new Consent(revoked.id(), revoked.userId(), revoked.purpose(), revoked.version(),
			revoked.grantedAt(), now));
		return true;
	}
}
