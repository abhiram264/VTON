package com.aifashionvault.platform.web;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.generation.vton.DefaultProviderRegistry;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ProductInputClaimCommand;
import com.aifashionvault.platform.generation.RecordingVtonProvider;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-worker integration proof for the merged C10/C11 Studio HTTP surface and the
 * merged C12 multi-item create. Each side is green alone; this asserts they COMPOSE:
 * a draft built through the real {@code /studio/sessions} routes is the exact intent
 * {@code /generations/multi} freezes, with the session lineage and the submitted
 * garment order preserved into the durable accepted job.
 */
@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=",
	"vault.generation.multi-item-enabled=true"
})
@AutoConfigureMockMvc
class StudioMultiItemGenerationIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired RecordingVtonProvider provider;

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void clearTables() {
		jdbc.update("TRUNCATE TABLE generation_admission_counter, generation_admission_reservation, look_item, look, quality_result, "
			+ "generation_callback_inbox, generation_work_outbox, generation_quota_reservation, "
			+ "generation_notification_delivery, generation_notification_event, generation_provider_capacity_slot, "
			+ "generation_attempt, generation_render_step_item, generation_render_step, generation_job_product, "
			+ "generation_job, vault_item, studio_item, studio_session, avatar_image, avatar_profile, identity_mapping, "
			+ "users CASCADE");
	}

	@Test
	void aDraftBuiltThroughStudioRoutesIsTheIntentMultiCreateFreezes() throws Exception {
		Owner owner = seedOwner();
		UUID top = insertVaultItem(owner.userId(), "TOPS");
		UUID bottom = insertVaultItem(owner.userId(), "BOTTOMS");
		UUID shoes = insertVaultItem(owner.userId(), "FOOTWEAR");

		// Build the draft through the REAL Studio routes.
		String sessionId = createStudioSession(owner);
		JsonNode draft = addItem(owner, sessionId, top, "UPPER_BODY");
		draft = addItem(owner, sessionId, bottom, "LOWER_BODY", draft.get("updatedAt").stringValue());
		draft = addItem(owner, sessionId, shoes, "FEET", draft.get("updatedAt").stringValue());
		// The draft's own API order (the store sorts by slot then position, not insertion),
		// which is exactly the order the Studio screen submits.
		List<String> draftOrder = itemIdsOf(draft);
		assertThat(draftOrder).containsExactlyInAnyOrder(top.toString(), bottom.toString(), shoes.toString());

		// The draft's items and lineage are what multi-create freezes, in the draft's order.
		MvcResult result = mockMvc.perform(post("/generations/multi")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "studio-draft-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studioSessionId\":\"" + sessionId + "\",\"productVaultItemIds\":[\""
					+ String.join("\",\"", draftOrder) + "\"],\"mode\":\"standard\"}"))
			.andExpect(status().isAccepted())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();

		UUID jobId = UUID.fromString(MAPPER.readTree(result.getResponse().getContentAsString()).get("id").stringValue());
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/generations/" + jobId);
		assertThat(jdbc.queryForObject("SELECT studio_session_id FROM generation_job WHERE id = ?", UUID.class, jobId))
			.isEqualTo(UUID.fromString(sessionId));
		assertThat(renderStepOrder(jobId)).extracting(UUID::toString).containsExactlyElementsOf(draftOrder);
		assertThat(jdbc.queryForObject("SELECT total_steps FROM generation_job WHERE id = ?", Integer.class, jobId))
			.isEqualTo(3);
		assertThat(count("generation_work_outbox")).isEqualTo(1);
		assertThat(provider.submitCount()).isZero();
	}

	@Test
	void aForeignOwnersStudioSessionIsRefusedByMultiCreate() throws Exception {
		Owner owner = seedOwner();
		Owner stranger = seedOwnerForSubject("studio-seam-stranger");
		UUID strangerItem = insertVaultItem(stranger.userId(), "TOPS");
		String strangerSession = createStudioSession(stranger);
		addItem(stranger, strangerSession, strangerItem, "UPPER_BODY");
		UUID owned = insertVaultItem(owner.userId(), "TOPS");

		mockMvc.perform(post("/generations/multi")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "foreign-studio-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studioSessionId\":\"" + strangerSession + "\",\"productVaultItemIds\":[\"" + owned
					+ "\"],\"mode\":\"standard\"}"))
			.andExpect(status().isNotFound())
			.andExpect(content().string("{\"code\":\"UNAUTHORIZED_OR_NOT_FOUND\"}"));

		assertThat(count("generation_job")).isZero();
		assertThat(count("generation_work_outbox")).isZero();
	}

	@Test
	void editingTheDraftAfterSubmissionDoesNotChangeTheAcceptedJobsInputs() throws Exception {
		Owner owner = seedOwner();
		UUID top = insertVaultItem(owner.userId(), "TOPS");
		UUID bottom = insertVaultItem(owner.userId(), "BOTTOMS");
		UUID shoes = insertVaultItem(owner.userId(), "FOOTWEAR");
		UUID lateArrival = insertVaultItem(owner.userId(), "TOPS");

		String sessionId = createStudioSession(owner);
		JsonNode draft = addItem(owner, sessionId, top, "UPPER_BODY");
		draft = addItem(owner, sessionId, bottom, "LOWER_BODY", draft.get("updatedAt").stringValue());
		draft = addItem(owner, sessionId, shoes, "FEET", draft.get("updatedAt").stringValue());
		List<String> submittedOrder = itemIdsOf(draft);

		// The user submits the outfit...
		MvcResult accepted = mockMvc.perform(post("/generations/multi")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "frozen-intent-key")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studioSessionId\":\"" + sessionId + "\",\"productVaultItemIds\":[\""
					+ String.join("\",\"", submittedOrder) + "\"],\"mode\":\"standard\"}"))
			.andExpect(status().isAccepted())
			.andReturn();
		UUID jobId = UUID.fromString(MAPPER.readTree(accepted.getResponse().getContentAsString()).get("id").stringValue());
		List<String> frozenBeforeEdit = renderStepOrder(jobId).stream().map(UUID::toString).toList();
		assertThat(frozenBeforeEdit).containsExactlyElementsOf(submittedOrder);

		// ...and then keeps editing the draft: adds a garment, then clears the
		// outfit entirely. Neither may reach the job that was already accepted.
		JsonNode current = getSession(owner, sessionId);
		addItem(owner, sessionId, lateArrival, "ACCESSORY", current.get("updatedAt").stringValue());
		JsonNode afterAdd = getSession(owner, sessionId);
		mockMvc.perform(post("/studio/sessions/" + sessionId + "/commands")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedUpdatedAt\":\"" + afterAdd.get("updatedAt").stringValue()
					+ "\",\"command\":\"clearOutfit\"}"))
			.andExpect(status().isOk());
		assertThat(itemIdsOf(getSession(owner, sessionId))).isEmpty();

		// The accepted job still holds exactly the submitted items, in order.
		assertThat(renderStepOrder(jobId).stream().map(UUID::toString).toList())
			.containsExactlyElementsOf(frozenBeforeEdit);
		assertThat(jobProducts(jobId).stream().map(UUID::toString).toList())
			.containsExactlyInAnyOrderElementsOf(submittedOrder);
		assertThat(jdbc.queryForObject("SELECT total_steps FROM generation_job WHERE id = ?", Integer.class, jobId))
			.isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT studio_session_id FROM generation_job WHERE id = ?", UUID.class, jobId))
			.isEqualTo(UUID.fromString(sessionId));
		assertThat(jobProducts(jobId)).doesNotContain(lateArrival);
	}

	private JsonNode getSession(Owner owner, String sessionId) throws Exception {
		MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/studio/sessions/" + sessionId)
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))).andExpect(status().isOk()).andReturn();
		return MAPPER.readTree(result.getResponse().getContentAsString());
	}

	private List<UUID> jobProducts(UUID jobId) {
		return jdbc.query("SELECT vault_item_id FROM generation_job_product WHERE generation_job_id = ?",
			(rs, row) -> rs.getObject("vault_item_id", UUID.class), jobId);
	}

	private String createStudioSession(Owner owner) throws Exception {
		MvcResult result = mockMvc.perform(post("/studio/sessions")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isCreated()).andReturn();
		return MAPPER.readTree(result.getResponse().getContentAsString()).get("sessionId").stringValue();
	}

	/** Adds one garment and returns the draft's new API representation. */
	private JsonNode addItem(Owner owner, String sessionId, UUID vaultItemId, String slot) throws Exception {
		MvcResult created = mockMvc.perform(MockMvcRequestBuilders.get("/studio/sessions/" + sessionId)
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))).andExpect(status().isOk()).andReturn();
		return addItem(owner, sessionId, vaultItemId, slot,
			MAPPER.readTree(created.getResponse().getContentAsString()).get("updatedAt").stringValue());
	}

	private JsonNode addItem(Owner owner, String sessionId, UUID vaultItemId, String slot, String expectedUpdatedAt)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/studio/sessions/" + sessionId + "/commands")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedUpdatedAt\":\"" + expectedUpdatedAt + "\",\"command\":\"addItem\",\"slot\":\""
					+ slot + "\",\"vaultItemId\":\"" + vaultItemId + "\",\"position\":0}"))
			.andExpect(status().isOk()).andReturn();
		return MAPPER.readTree(result.getResponse().getContentAsString());
	}

	private static List<String> itemIdsOf(JsonNode session) {
		List<String> ids = new ArrayList<>();
		session.get("itemIds").forEach(node -> ids.add(node.stringValue()));
		return ids;
	}

	private List<UUID> renderStepOrder(UUID jobId) {
		return jdbc.query(
			"SELECT i.vault_item_id FROM generation_render_step s "
				+ "JOIN generation_render_step_item i ON i.render_step_id = s.id "
				+ "WHERE s.generation_job_id = ? ORDER BY s.step_index, i.item_ordinal",
			(rs, row) -> rs.getObject("vault_item_id", UUID.class),
			jobId);
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private Owner seedOwner() throws Exception {
		return seedOwnerForSubject(InProcessJwtFixture.subject(), InProcessJwtFixture.bearerToken());
	}

	private Owner seedOwnerForSubject(String subject) throws Exception {
		return seedOwnerForSubject(subject, InProcessJwtFixture.bearerTokenForSubject(subject));
	}

	private Owner seedOwnerForSubject(String subject, String token) throws Exception {
		mockMvc.perform(MockMvcRequestBuilders.get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk());
		UUID user = jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject = ?", UUID.class, subject);
		UserId userId = new UserId(user);
		insertProfileWithImage(userId);
		return new Owner(token, userId);
	}

	private void insertProfileWithImage(UserId userId) {
		UUID image = UUID.randomUUID();
		UUID profile = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("INSERT INTO avatar_profile (id, user_id, primary_image_id, status, revision, created_at, updated_at) VALUES (?, ?, NULL, 'ACTIVE', 1, ?, ?)",
			profile, userId.value(), now, now);
		jdbc.update("INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) VALUES (?, ?, ?, 'ACCEPTED', ?)",
			image, profile, "users/" + userId.value() + "/avatars/" + image + ".jpg", now);
		jdbc.update("UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?", image, profile);
	}

	private UUID insertVaultItem(UserId userId, String category) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO vault_item (id, user_id, storage_key, name, category, created_at) VALUES (?, ?, ?, ?, ?, ?)",
			id, userId.value(), "users/" + userId.value() + "/vault/" + id + ".jpg", "item-" + id, category,
			Timestamp.from(Instant.now()));
		return id;
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private record Owner(String token, UserId userId) {}

	@TestConfiguration
	static class Configuration {
		@Bean @Primary ExternalTokenVerifier verifier() {
			return InProcessJwtFixture.verifier();
		}

		@Bean @Primary RecordingVtonProvider provider() {
			return new RecordingVtonProvider();
		}

		@Bean @Primary ProviderRegistry testProviderRegistry(RecordingVtonProvider recordingProvider) {
			return new DefaultProviderRegistry(
				List.of(recordingProvider), recordingProvider.providerKey(), java.util.Map.of());
		}

		// RULE: never reuse a production bean name for a test bean (BeanDefinitionOverrideException).
		@Bean GenerationMediaStore stubGenerationMediaStore() {
			return new GenerationMediaStore() {
				@Override public void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType) { }
				@Override public ClaimResult claim(com.aifashionvault.platform.objectstore.GenerationClaimCommand command) {
					throw new UnsupportedOperationException("generation claim");
				}
				@Override public void preflightProductInput(ProductInputClaimCommand command) { }
				@Override public ClaimResult claimProductInput(ProductInputClaimCommand command) {
					return new ClaimResult.Copied();
				}
				@Override public void releaseProductInput(ProductInputClaimCommand command) { }
			};
		}
	}
}
