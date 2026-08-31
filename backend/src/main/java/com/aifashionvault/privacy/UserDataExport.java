package com.aifashionvault.privacy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.aifashionvault.shared.UserId;

public record UserDataExport(String schemaVersion, UserId userId, Instant generatedAt, Map<String, Object> account,
		List<Map<String, Object>> consents, List<Map<String, Object>> avatars, List<Map<String, Object>> vaultItems,
		List<Map<String, Object>> looks, List<Map<String, Object>> generations) {

	public UserDataExport {
		if (schemaVersion == null || schemaVersion.isBlank() || userId == null || generatedAt == null || account == null
				|| consents == null || avatars == null || vaultItems == null || looks == null || generations == null) {
			throw new IllegalArgumentException("export fields are required");
		}
		account = Map.copyOf(account);
		consents = immutableRows(consents);
		avatars = immutableRows(avatars);
		vaultItems = immutableRows(vaultItems);
		looks = immutableRows(looks);
		generations = immutableRows(generations);
	}

	private static List<Map<String, Object>> immutableRows(List<Map<String, Object>> rows) {
		return rows.stream().map(Map::copyOf).toList();
	}
}
