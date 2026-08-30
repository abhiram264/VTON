package com.aifashionvault.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifashionvault.shared.NotificationId;
import com.aifashionvault.shared.UserId;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class JdbcNotificationStore implements NotificationStore {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final RowMapper<Notification> NOTIFICATION_MAPPER = (rs, rowNum) -> new Notification(
		new NotificationId(rs.getObject("id", UUID.class)),
		new UserId(rs.getObject("user_id", UUID.class)),
		rs.getString("type"),
		payloadFromJson(rs.getString("payload")),
		toInstant(rs.getTimestamp("read_at")),
		rs.getTimestamp("created_at").toInstant());

	private final JdbcTemplate jdbc;

	public JdbcNotificationStore(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	public void insert(Notification notification) {
		if (notification == null) {
			throw new IllegalArgumentException("notification is required");
		}
		jdbc.update(
			"INSERT INTO notification (id, user_id, type, payload, read_at, created_at) VALUES (?, ?, ?, ?::jsonb, ?, ?)",
			notification.id().value(),
			notification.userId().value(),
			notification.type(),
			payloadToJson(notification.payload()),
			notification.readAt() == null ? null : Timestamp.from(notification.readAt()),
			Timestamp.from(notification.createdAt()));
	}

	@Override
	public Optional<Notification> findById(NotificationId id) {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		return one(jdbc.query(
			"SELECT id, user_id, type, payload, read_at, created_at FROM notification WHERE id = ?",
			NOTIFICATION_MAPPER,
			id.value()));
	}

	private static String payloadToJson(Map<String, String> payload) {
		try {
			return MAPPER.writeValueAsString(payload);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("notification payload could not be serialized", ex);
		}
	}

	private static Map<String, String> payloadFromJson(String json) {
		try {
			JsonNode node = MAPPER.readTree(json);
			if (node == null || !node.isObject()) {
				throw new IllegalStateException("notification payload must be a JSON object");
			}
			Map<String, String> payload = new LinkedHashMap<>();
			for (String key : node.propertyNames()) {
				JsonNode value = node.get(key);
				payload.put(key, value == null || value.isNull() ? null : value.stringValue());
			}
			return payload;
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("notification payload could not be parsed", ex);
		}
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static Optional<Notification> one(List<Notification> rows) {
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}
}
