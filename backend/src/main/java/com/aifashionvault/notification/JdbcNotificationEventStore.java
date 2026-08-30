package com.aifashionvault.notification;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.UserId;

public class JdbcNotificationEventStore implements NotificationEventStore {

	static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

	private static final RowMapper<NotificationEvent> EVENT_MAPPER = (rs, rowNum) -> new NotificationEvent(
		rs.getObject("id", UUID.class),
		new UserId(rs.getObject("user_id", UUID.class)),
		new GenerationJobId(rs.getObject("generation_job_id", UUID.class)),
		rs.getString("type"),
		toLookId(rs.getObject("look_id", UUID.class)),
		rs.getTimestamp("created_at").toInstant(),
		rs.getTimestamp("deadline_at").toInstant(),
		rs.getString("reason"));

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcNotificationEventStore(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	@Override
	public boolean insert(NotificationEvent event) {
		if (event == null) {
			throw new IllegalArgumentException("event is required");
		}
		int rows = jdbc.update(
			"INSERT INTO generation_notification_event "
				+ "(id, user_id, generation_job_id, type, look_id, created_at, deadline_at, reason) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
				+ "ON CONFLICT (generation_job_id, type) DO NOTHING",
			event.id(),
			event.userId().value(),
			event.generationId().value(),
			event.type(),
			event.lookId() == null ? null : event.lookId().value(),
			Timestamp.from(event.createdAt()),
			Timestamp.from(event.deadlineAt()),
			event.reason());
		return rows > 0;
	}

	@Override
	public Optional<NotificationEvent> findById(UUID eventId) {
		if (eventId == null) {
			throw new IllegalArgumentException("eventId is required");
		}
		return one(jdbc.query(
			"SELECT id, user_id, generation_job_id, type, look_id, created_at, deadline_at, reason "
				+ "FROM generation_notification_event WHERE id = ?",
			EVENT_MAPPER,
			eventId));
	}

	@Override
	public boolean ensureDelivery(UUID eventId, String deviceIdentity) {
		if (eventId == null) {
			throw new IllegalArgumentException("eventId is required");
		}
		if (deviceIdentity == null || deviceIdentity.isBlank()) {
			throw new IllegalArgumentException("deviceIdentity is required");
		}
		int rows = jdbc.update(
			"INSERT INTO generation_notification_delivery "
				+ "(id, event_id, device_identity, status, attempt_count, next_attempt_at, "
				+ "provider_message_id, terminal_result) "
				+ "VALUES (?, ?, ?, ?, 0, NULL, NULL, NULL) "
				+ "ON CONFLICT (event_id, device_identity) DO NOTHING",
			UUID.randomUUID(),
			eventId,
			deviceIdentity,
			DeviceDelivery.PENDING);
		return rows > 0;
	}

	@Override
	public Optional<DeviceDelivery> findDelivery(UUID eventId, String deviceIdentity) {
		if (eventId == null) {
			throw new IllegalArgumentException("eventId is required");
		}
		if (deviceIdentity == null || deviceIdentity.isBlank()) {
			throw new IllegalArgumentException("deviceIdentity is required");
		}
		return one(jdbc.query(
			"SELECT id, event_id, device_identity, status, attempt_count, next_attempt_at, "
				+ "provider_message_id, terminal_result "
				+ "FROM generation_notification_delivery "
				+ "WHERE event_id = ? AND device_identity = ?",
			(rs, rowNum) -> mapDelivery(
				rs.getObject("id", UUID.class),
				rs.getObject("event_id", UUID.class),
				rs.getString("device_identity"),
				rs.getString("status"),
				rs.getInt("attempt_count"),
				rs.getTimestamp("next_attempt_at"),
				rs.getString("provider_message_id"),
				rs.getString("terminal_result")),
			eventId,
			deviceIdentity));
	}

	@Override
	public Optional<DueDelivery> claimDue(Instant now) {
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		return transactions.execute(status -> {
			List<DueDelivery> rows = jdbc.query(
				"SELECT e.id AS event_id, e.user_id, e.generation_job_id, e.type, e.look_id, "
					+ "e.created_at, e.deadline_at, e.reason, d.id AS delivery_id, "
					+ "d.device_identity, d.status, d.attempt_count, d.next_attempt_at, "
					+ "d.provider_message_id, d.terminal_result "
					+ "FROM generation_notification_delivery d "
					+ "JOIN generation_notification_event e ON e.id = d.event_id "
					+ "WHERE d.status = ? "
					+ "AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= ?) "
					+ "ORDER BY COALESCE(d.next_attempt_at, e.created_at), d.id "
					+ "FOR UPDATE OF d SKIP LOCKED "
					+ "LIMIT 1",
				(rs, rowNum) -> new DueDelivery(
					new NotificationEvent(
						rs.getObject("event_id", UUID.class),
						new UserId(rs.getObject("user_id", UUID.class)),
						new GenerationJobId(rs.getObject("generation_job_id", UUID.class)),
						rs.getString("type"),
						toLookId(rs.getObject("look_id", UUID.class)),
						rs.getTimestamp("created_at").toInstant(),
						rs.getTimestamp("deadline_at").toInstant(),
						rs.getString("reason")),
					mapDelivery(
						rs.getObject("delivery_id", UUID.class),
						rs.getObject("event_id", UUID.class),
						rs.getString("device_identity"),
						rs.getString("status"),
						rs.getInt("attempt_count"),
						rs.getTimestamp("next_attempt_at"),
						rs.getString("provider_message_id"),
						rs.getString("terminal_result"))),
				DeviceDelivery.PENDING,
				Timestamp.from(now));
			if (rows.isEmpty()) {
				return Optional.empty();
			}
			DueDelivery due = rows.getFirst();
			jdbc.update(
				"UPDATE generation_notification_delivery SET next_attempt_at = ? WHERE id = ?",
				Timestamp.from(now.plus(CLAIM_LEASE)),
				due.delivery().id());
			return Optional.of(due);
		});
	}

	@Override
	public void markAccepted(UUID deliveryId, int attemptCount, String providerMessageId, Instant now) {
		if (deliveryId == null) {
			throw new IllegalArgumentException("deliveryId is required");
		}
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		jdbc.update(
			"UPDATE generation_notification_delivery "
				+ "SET status = ?, attempt_count = ?, next_attempt_at = NULL, "
				+ "provider_message_id = ?, terminal_result = ? "
				+ "WHERE id = ?",
			DeviceDelivery.ACCEPTED,
			attemptCount,
			providerMessageId,
			DeviceDelivery.ACCEPTED_FOR_DELIVERY,
			deliveryId);
	}

	@Override
	public void scheduleRetry(UUID deliveryId, int attemptCount, Instant nextAttemptAt) {
		if (deliveryId == null) {
			throw new IllegalArgumentException("deliveryId is required");
		}
		if (nextAttemptAt == null) {
			throw new IllegalArgumentException("nextAttemptAt is required");
		}
		jdbc.update(
			"UPDATE generation_notification_delivery "
				+ "SET status = ?, attempt_count = ?, next_attempt_at = ?, terminal_result = NULL "
				+ "WHERE id = ?",
			DeviceDelivery.PENDING,
			attemptCount,
			Timestamp.from(nextAttemptAt),
			deliveryId);
	}

	@Override
	public void markTerminal(UUID deliveryId, int attemptCount, String terminalResult, Instant now) {
		if (deliveryId == null) {
			throw new IllegalArgumentException("deliveryId is required");
		}
		if (terminalResult == null || terminalResult.isBlank()) {
			throw new IllegalArgumentException("terminalResult is required");
		}
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		jdbc.update(
			"UPDATE generation_notification_delivery "
				+ "SET status = ?, attempt_count = ?, next_attempt_at = NULL, terminal_result = ? "
				+ "WHERE id = ?",
			DeviceDelivery.FAILED,
			attemptCount,
			terminalResult,
			deliveryId);
	}

	private static DeviceDelivery mapDelivery(
		UUID id,
		UUID eventId,
		String deviceIdentity,
		String status,
		int attemptCount,
		Timestamp nextAttemptAt,
		String providerMessageId,
		String terminalResult
	) {
		return new DeviceDelivery(
			id,
			eventId,
			deviceIdentity,
			status,
			attemptCount,
			nextAttemptAt == null ? null : nextAttemptAt.toInstant(),
			providerMessageId,
			terminalResult);
	}

	private static LookId toLookId(UUID lookId) {
		return lookId == null ? null : new LookId(lookId);
	}

	private static <T> Optional<T> one(List<T> rows) {
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}
}
