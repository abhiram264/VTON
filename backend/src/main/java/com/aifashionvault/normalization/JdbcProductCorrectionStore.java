package com.aifashionvault.normalization;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

public final class JdbcProductCorrectionStore {

	private static final String COLUMNS = "id, user_id, vault_item_id, predicted_category, "
		+ "corrected_category, confidence, created_at";
	private static final RowMapper<CorrectionEvent> MAPPER = (rs, rowNum) -> new CorrectionEvent(
		rs.getObject("id", java.util.UUID.class),
		new UserId(rs.getObject("user_id", java.util.UUID.class)),
		new VaultItemId(rs.getObject("vault_item_id", java.util.UUID.class)),
		ProductCategory.valueOf(rs.getString("predicted_category")),
		ProductCategory.valueOf(rs.getString("corrected_category")),
		rs.getDouble("confidence"),
		rs.getTimestamp("created_at").toInstant());

	private final JdbcTemplate jdbc;

	public JdbcProductCorrectionStore(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
	}

	public CorrectionEvent append(CorrectionEvent event) {
		Objects.requireNonNull(event, "event");
		try {
			jdbc.update("INSERT INTO product_correction_event (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
				event.id(), event.userId().value(), event.vaultItemId().value(), event.predictedCategory().name(),
				event.correctedCategory().name(), event.confidence(), Timestamp.from(event.createdAt()));
			return event;
		}
		catch (DataAccessException ex) {
			Optional<CorrectionEvent> existing = findById(event.id());
			if (existing.isEmpty()) {
				throw ex;
			}
			if (existing.get().equals(event)) {
				return existing.get();
			}
			throw new CorrectionEventIdentityConflictException(event.id());
		}
	}

	public Optional<CorrectionEvent> find(UserId owner, java.util.UUID id) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(id, "id");
		return jdbc.query("SELECT " + COLUMNS + " FROM product_correction_event WHERE id = ? AND user_id = ?",
			MAPPER, id, owner.value()).stream().findFirst();
	}

	public List<CorrectionEvent> findAll(UserId owner) {
		Objects.requireNonNull(owner, "owner");
		return jdbc.query("SELECT " + COLUMNS + " FROM product_correction_event WHERE user_id = ? ORDER BY created_at, id",
			MAPPER, owner.value());
	}

	private Optional<CorrectionEvent> findById(java.util.UUID id) {
		return jdbc.query("SELECT " + COLUMNS + " FROM product_correction_event WHERE id = ?", MAPPER, id)
			.stream().findFirst();
	}
}
