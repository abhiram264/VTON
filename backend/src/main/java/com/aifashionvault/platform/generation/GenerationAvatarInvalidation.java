package com.aifashionvault.platform.generation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.UserId;

public class GenerationAvatarInvalidation {

	private static final String MARK_SUBJECT_DELETED_SQL =
		"UPDATE generation_job SET status = 'FAILED', "
			+ "terminal_reason = CASE WHEN terminal_reason IS NULL THEN 'SUBJECT_DELETED' ELSE terminal_reason END, "
			+ "subject_deleted_at = ?, completed_at = COALESCE(completed_at, ?), updated_at = ?, "
			+ "status_version = status_version + 1 "
			+ "WHERE user_id = ? AND avatar_image_id IN (SELECT id FROM avatar_image WHERE profile_id = ?) "
			+ "AND subject_deleted_at IS NULL AND status <> 'COMPLETED'";

	private final JdbcTemplate jdbc;

	public GenerationAvatarInvalidation(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
	}

	public void afterAvatarDeleted(UserId userId, AvatarProfileId profileId, Clock clock) {
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		afterAvatarDeleted(userId, profileId, clock.instant());
	}

	public void afterAvatarDeleted(UserId userId, AvatarProfileId profileId, Instant when) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (profileId == null) {
			throw new IllegalArgumentException("profileId is required");
		}
		if (when == null) {
			throw new IllegalArgumentException("when is required");
		}
		jdbc.update(
			MARK_SUBJECT_DELETED_SQL,
			Timestamp.from(when),
			Timestamp.from(when),
			Timestamp.from(when),
			userId.value(),
			profileId.value());
	}
}
