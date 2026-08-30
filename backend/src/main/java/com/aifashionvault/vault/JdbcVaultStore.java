package com.aifashionvault.vault;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.normalization.ProductCategory;
import com.aifashionvault.shared.SourceReferenceId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;
import com.aifashionvault.vault.VaultContracts.DeleteResult;
import com.aifashionvault.vault.VaultContracts.Filters;
import com.aifashionvault.vault.VaultContracts.JsonNullable;
import com.aifashionvault.vault.VaultContracts.Patch;

public final class JdbcVaultStore {
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String COLUMNS = "v.id, v.user_id, v.storage_key, v.source_reference_id, v.name, v.brand, v.category, v.subcategory, v.color, v.pattern, v.attributes::text attributes, v.created_at";
	private static final RowMapper<VaultItem> MAPPER = (rs, row) -> new VaultItem(
		new VaultItemId(rs.getObject("id", UUID.class)), new UserId(rs.getObject("user_id", UUID.class)),
		new StorageKey(rs.getString("storage_key")), sourceId(rs.getObject("source_reference_id", UUID.class)),
		rs.getString("name"), rs.getString("brand"), category(rs.getString("category")), rs.getString("subcategory"),
		rs.getString("color"), rs.getString("pattern"), json(rs.getString("attributes")), rs.getTimestamp("created_at").toInstant());

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcVaultStore(DataSource source) {
		if (source == null) throw new IllegalArgumentException("dataSource is required");
		this.jdbc = new JdbcTemplate(source);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
	}

	public VaultItem insert(VaultItem item) {
		jdbc.update("INSERT INTO vault_item (id,user_id,storage_key,source_reference_id,name,brand,category,subcategory,color,pattern,attributes,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?)",
			item.id().value(), item.userId().value(), item.storageKey().value(), value(item.sourceReferenceId()), item.name(), item.brand(),
			name(item.category()), item.subcategory(), item.color(), item.pattern(), json(item.attributes()), Timestamp.from(item.createdAt()));
		return item;
	}

	public boolean sourceExists(SourceReferenceId id) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM source_reference WHERE id=?)", Boolean.class, id.value()));
	}

	public Optional<VaultItem> findByIdForUser(VaultItemId id, UserId user) {
		return one(jdbc.query("SELECT " + COLUMNS + " FROM vault_item v WHERE v.id=? AND v.user_id=?", MAPPER, id.value(), user.value()));
	}

	public Optional<VaultItem> findByStorageKeyForUser(StorageKey key, UserId user) {
		return one(jdbc.query("SELECT " + COLUMNS + " FROM vault_item v WHERE v.storage_key=? AND v.user_id=?", MAPPER, key.value(), user.value()));
	}

	public List<VaultItem> listForUser(UserId user, Filters filters) {
		StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM vault_item v LEFT JOIN source_reference s ON s.id=v.source_reference_id WHERE v.user_id=?");
		List<Object> args = new ArrayList<>(); args.add(user.value());
		if (filters.category() != null) { sql.append(" AND v.category=?"); args.add(filters.category().name()); }
		if (filters.collection() != null) { sql.append(" AND EXISTS(SELECT 1 FROM collection_item ci JOIN collection c ON c.id=ci.collection_id WHERE ci.vault_item_id=v.id AND c.id=? AND c.user_id=?)"); args.add(filters.collection()); args.add(user.value()); }
		if (filters.brand() != null) { sql.append(" AND lower(v.brand)=lower(?)"); args.add(filters.brand()); }
		if (filters.color() != null) { sql.append(" AND lower(v.color)=lower(?)"); args.add(filters.color()); }
		if (filters.search() != null) { sql.append(" AND (lower(coalesce(v.name,'')) LIKE ? ESCAPE '\\\\' OR lower(coalesce(v.brand,'')) LIKE ? ESCAPE '\\\\' OR lower(coalesce(v.category,'')) LIKE ? ESCAPE '\\\\' OR lower(coalesce(v.subcategory,'')) LIKE ? ESCAPE '\\\\' OR lower(coalesce(v.color,'')) LIKE ? ESCAPE '\\\\' OR lower(coalesce(v.pattern,'')) LIKE ? ESCAPE '\\\\')"); String q=like(filters.search()); for(int i=0;i<6;i++) args.add(q); }
		if (filters.source() != null) { sql.append(" AND (lower(coalesce(s.retailer,'')) LIKE ? ESCAPE '\\\\' OR lower(coalesce(s.url,'')) LIKE ? ESCAPE '\\\\')"); String q=like(filters.source()); args.add(q); args.add(q); }
		if (filters.createdDate() != null) { sql.append(" AND v.created_at>=? AND v.created_at<?"); args.add(Timestamp.from(filters.createdDate().atStartOfDay().toInstant(ZoneOffset.UTC))); args.add(Timestamp.from(filters.createdDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))); }
		sql.append(" ORDER BY v.created_at DESC, v.id DESC");
		return jdbc.query(sql.toString(), MAPPER, args.toArray());
	}

	public Optional<VaultItem> patchForUser(VaultItemId id, UserId user, Patch patch) {
		if (patch.empty()) throw new IllegalArgumentException("patch is empty");
		List<String> sets = new ArrayList<>(); List<Object> args = new ArrayList<>();
		set(sets,args,"name",patch.name(),false); set(sets,args,"brand",patch.brand(),false);
		if (!patch.category().undefined()) { sets.add("category=?"); args.add(name(patch.category().value())); }
		set(sets,args,"subcategory",patch.subcategory(),false); set(sets,args,"color",patch.color(),false); set(sets,args,"pattern",patch.pattern(),false);
		if (!patch.attributes().undefined()) { sets.add("attributes=?::jsonb"); args.add(json(patch.attributes().value())); }
		args.add(id.value()); args.add(user.value());
		jdbc.update("UPDATE vault_item SET " + String.join(",", sets) + " WHERE id=? AND user_id=?", args.toArray());
		return findByIdForUser(id,user);
	}

	public Optional<VaultItem> lockForDelete(VaultItemId id, UserId user) {
		return one(jdbc.query("SELECT " + COLUMNS + " FROM vault_item v WHERE v.id=? AND v.user_id=? FOR UPDATE", MAPPER,id.value(),user.value()));
	}

	public DeleteResult deleteForUser(VaultItemId id, UserId user) {
		DeleteResult result=transactions.execute(status -> {
			if (lockForDelete(id,user).isEmpty()) return DeleteResult.NOT_FOUND;
			Boolean used=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM studio_item WHERE vault_item_id=? UNION ALL SELECT 1 FROM look_item WHERE vault_item_id=? UNION ALL SELECT 1 FROM generation_job_item WHERE vault_item_id=? UNION ALL SELECT 1 FROM generation_render_step_item WHERE vault_item_id=?)",Boolean.class,id.value(),id.value(),id.value(),id.value());
			if (Boolean.TRUE.equals(used)) return DeleteResult.IN_USE;
			return jdbc.update("DELETE FROM vault_item WHERE id=? AND user_id=?",id.value(),user.value())==1?DeleteResult.DELETED:DeleteResult.NOT_FOUND;
		}); return result;
	}

	private static void set(List<String>s,List<Object>a,String column,JsonNullable<String> value,boolean json){ if(!value.undefined()){s.add(column+"=?");a.add(value.value());}}
	private static String like(String value){return "%"+value.toLowerCase().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
	private static <T> Optional<T> one(List<T> rows){return rows.stream().findFirst();}
	private static UUID value(SourceReferenceId id){return id==null?null:id.value();}
	private static SourceReferenceId sourceId(UUID id){return id==null?null:new SourceReferenceId(id);}
	private static ProductCategory category(String value){return value==null?null:ProductCategory.valueOf(value);}
	private static String name(ProductCategory value){return value==null?null:value.name();}
	private static JsonNode json(String value){try{return value==null?null:JSON.readTree(value);}catch(JacksonException e){throw new IllegalStateException(e);}}
	private static String json(JsonNode value){try{return value==null?null:JSON.writeValueAsString(value);}catch(JacksonException e){throw new IllegalArgumentException(e);}}
}
