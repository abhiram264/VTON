package com.aifashionvault.privacy;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aifashionvault.shared.UserId;

public record UserDataExport(String schemaVersion, UserId userId, Instant generatedAt, Map<String, Object> account,
		List<Map<String, Object>> consents, List<Map<String, Object>> avatars, List<Map<String, Object>> vaultItems,
		List<Map<String, Object>> looks, List<Map<String, Object>> generations) {

	public UserDataExport {
		if (schemaVersion == null || schemaVersion.isBlank() || userId == null || generatedAt == null || account == null
				|| consents == null || avatars == null || vaultItems == null || looks == null || generations == null) {
			throw new IllegalArgumentException("export fields are required");
		}
		account = immutableMap(account);
		consents = immutableRows(consents);
		avatars = immutableRows(avatars);
		vaultItems = immutableRows(vaultItems);
		looks = immutableRows(looks);
		generations = immutableRows(generations);
	}

	private static List<Map<String, Object>> immutableRows(List<Map<String, Object>> rows) {
		if (rows.stream().anyMatch(row -> row == null)) {
			throw new IllegalArgumentException("export rows must not be null");
		}
		return rows.stream().map(UserDataExport::immutableMap).toList();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> immutableMap(Map<?, ?> source) {
		return Map.copyOf((Map<String, Object>) immutableValue(source,
				Collections.newSetFromMap(new IdentityHashMap<>())));
	}

	private static Object immutableValue(Object value, Set<Object> visiting) {
		if (value == null || value instanceof String || value instanceof Boolean
				|| value instanceof Byte || value instanceof Short || value instanceof Integer
				|| value instanceof Long || value instanceof BigDecimal || value instanceof BigInteger) {
			return value;
		}
		if (value instanceof Double d) {
			if (!Double.isFinite(d)) {
				throw new IllegalArgumentException("export numbers must be finite");
			}
			return value;
		}
		if (value instanceof Float f) {
			if (!Float.isFinite(f)) {
				throw new IllegalArgumentException("export numbers must be finite");
			}
			return value;
		}
		if (value instanceof Map<?, ?> map) {
			if (!visiting.add(value)) {
				throw new IllegalArgumentException("export values must not be cyclic");
			}
			Map<String, Object> copy = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!(entry.getKey() instanceof String key)) {
					throw new IllegalArgumentException("export keys must be strings");
				}
				copy.put(key, immutableValue(entry.getValue(), visiting));
			}
			visiting.remove(value);
			return Collections.unmodifiableMap(copy);
		}
		if (value instanceof List<?> list) {
			if (!visiting.add(value)) {
				throw new IllegalArgumentException("export values must not be cyclic");
			}
			List<Object> copy = new ArrayList<>(list.size());
			for (Object element : list) {
				copy.add(immutableValue(element, visiting));
			}
			visiting.remove(value);
			return Collections.unmodifiableList(copy);
		}
		if (value.getClass().isArray()) {
			if (value instanceof byte[]) {
				throw new IllegalArgumentException("export must not contain binary data");
			}
			if (!visiting.add(value)) {
				throw new IllegalArgumentException("export values must not be cyclic");
			}
			List<Object> copy = new ArrayList<>(Array.getLength(value));
			for (int index = 0; index < Array.getLength(value); index++) {
				copy.add(immutableValue(Array.get(value, index), visiting));
			}
			visiting.remove(value);
			return Collections.unmodifiableList(copy);
		}
		throw new IllegalArgumentException("unsupported export value type: " + value.getClass().getName());
	}
}
