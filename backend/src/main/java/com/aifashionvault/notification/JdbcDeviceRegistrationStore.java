package com.aifashionvault.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifashionvault.shared.UserId;

public class JdbcDeviceRegistrationStore implements DeviceRegistrationStore {

	private static final RowMapper<DeviceRegistration> REGISTRATION_MAPPER = (rs, rowNum) -> new DeviceRegistration(
		rs.getObject("id", UUID.class),
		new UserId(rs.getObject("user_id", UUID.class)),
		rs.getString("device_token"),
		rs.getString("platform"),
		rs.getTimestamp("created_at").toInstant(),
		rs.getTimestamp("updated_at").toInstant(),
		rs.getTimestamp("last_seen_at").toInstant());

	private static final RowMapper<NotificationPreference> PREFERENCE_MAPPER = (rs, rowNum) -> new NotificationPreference(
		new UserId(rs.getObject("user_id", UUID.class)),
		rs.getBoolean("push_enabled"),
		rs.getTimestamp("updated_at").toInstant());

	private final JdbcTemplate jdbc;

	public JdbcDeviceRegistrationStore(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	public DeviceRegistration register(UserId userId, String deviceToken, String platform, Instant now) {
		requireOwner(userId);
		requireToken(deviceToken);
		if (!DeviceRegistration.isSupportedPlatform(platform)) {
			throw new IllegalArgumentException("platform must be ANDROID or IOS");
		}
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		jdbc.update(
			"INSERT INTO device_registration "
				+ "(id, user_id, device_token, platform, created_at, updated_at, last_seen_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?) "
				+ "ON CONFLICT (device_token) DO UPDATE SET "
				+ "user_id = EXCLUDED.user_id, platform = EXCLUDED.platform, "
				+ "updated_at = EXCLUDED.updated_at, last_seen_at = EXCLUDED.last_seen_at",
			UUID.randomUUID(),
			userId.value(),
			deviceToken,
			platform,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now));
		return findByToken(deviceToken).orElseThrow(
			() -> new IllegalStateException("registered device token not found"));
	}

	@Override
	public boolean remove(UserId userId, String deviceToken) {
		requireOwner(userId);
		requireToken(deviceToken);
		return jdbc.update(
			"DELETE FROM device_registration WHERE device_token = ? AND user_id = ?",
			deviceToken,
			userId.value()) > 0;
	}

	@Override
	public List<DeviceRegistration> listByUser(UserId userId) {
		requireOwner(userId);
		return jdbc.query(
			"SELECT id, user_id, device_token, platform, created_at, updated_at, last_seen_at "
				+ "FROM device_registration WHERE user_id = ? ORDER BY created_at, device_token",
			REGISTRATION_MAPPER,
			userId.value());
	}

	@Override
	public boolean prune(String deviceToken) {
		requireToken(deviceToken);
		return jdbc.update("DELETE FROM device_registration WHERE device_token = ?", deviceToken) > 0;
	}

	@Override
	public int removeAllForUser(UserId userId) {
		requireOwner(userId);
		return jdbc.update("DELETE FROM device_registration WHERE user_id = ?", userId.value());
	}

	@Override
	public NotificationPreference upsertPreference(UserId userId, boolean pushEnabled, Instant now) {
		requireOwner(userId);
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		jdbc.update(
			"INSERT INTO notification_preference (user_id, push_enabled, updated_at) VALUES (?, ?, ?) "
				+ "ON CONFLICT (user_id) DO UPDATE SET "
				+ "push_enabled = EXCLUDED.push_enabled, updated_at = EXCLUDED.updated_at",
			userId.value(),
			pushEnabled,
			Timestamp.from(now));
		return findPreference(userId).orElseThrow(
			() -> new IllegalStateException("upserted notification preference not found"));
	}

	@Override
	public Optional<NotificationPreference> findPreference(UserId userId) {
		requireOwner(userId);
		List<NotificationPreference> rows = jdbc.query(
			"SELECT user_id, push_enabled, updated_at FROM notification_preference WHERE user_id = ?",
			PREFERENCE_MAPPER,
			userId.value());
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	private Optional<DeviceRegistration> findByToken(String deviceToken) {
		List<DeviceRegistration> rows = jdbc.query(
			"SELECT id, user_id, device_token, platform, created_at, updated_at, last_seen_at "
				+ "FROM device_registration WHERE device_token = ?",
			REGISTRATION_MAPPER,
			deviceToken);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	private static void requireOwner(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
	}

	private static void requireToken(String deviceToken) {
		if (deviceToken == null || deviceToken.isBlank()) {
			throw new IllegalArgumentException("deviceToken is required");
		}
	}
}
