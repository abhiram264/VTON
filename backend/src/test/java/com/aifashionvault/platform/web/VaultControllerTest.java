package com.aifashionvault.platform.web;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import java.util.TimeZone;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.platform.objectstore.StoredObject;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.vault.JdbcVaultStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class VaultControllerTest {
	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String INVALID = "{\"code\":\"VAULT_INVALID_REQUEST\"}";
	private static final String NOT_FOUND = "{\"code\":\"VAULT_ITEM_NOT_FOUND\"}";

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void clearDatabase() {
		jdbc.update("TRUNCATE vault_item, source_reference, identity_mapping, users CASCADE");
	}

	@Test
	void allEndpointFormsRequireAuthentication() throws Exception {
		String id = UUID.randomUUID().toString();
		mockMvc.perform(post("/vault/items").contentType(MediaType.APPLICATION_JSON).content(createBody("TOP", "Boots")))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/vault/items")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/vault/items/" + id)).andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/vault/items/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New\"}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/vault/items/" + id)).andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedCrudAndFiltersExposeOnlyPublicFields() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		MvcResult empty = mockMvc.perform(get("/vault/items").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk()).andReturn();
		assertThat(empty.getResponse().getContentAsString()).isEqualTo("[]");

		JsonNode boots = create(token, "FOOTWEAR", "Trail Boots");
		create(token, "TOP", "Linen Shirt");
		String id = boots.get("id").stringValue();
		assertPublicResource(boots);

		MvcResult filtered = mockMvc.perform(get("/vault/items").queryParam("category", "FOOTWEAR")
				.queryParam("brand", "ACME").queryParam("color", "black").queryParam("search", "trail")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk()).andReturn();
		JsonNode list = JSON.readTree(filtered.getResponse().getContentAsString());
		assertThat(list.size()).isEqualTo(1);
		assertThat(list.get(0).get("id").stringValue()).isEqualTo(id);

		MvcResult got = mockMvc.perform(get("/vault/items/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk()).andReturn();
		assertPublicResource(JSON.readTree(got.getResponse().getContentAsString()));

		MvcResult patched = mockMvc.perform(patch("/vault/items/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":null,\"category\":\"UNKNOWN\",\"attributes\":{\"season\":\"winter\"}}"))
			.andExpect(status().isOk()).andReturn();
		JsonNode updated = JSON.readTree(patched.getResponse().getContentAsString());
		assertThat(updated.get("name").isNull()).isTrue();
		assertThat(updated.get("brand").stringValue()).isEqualTo("Acme");
		assertThat(updated.get("category").stringValue()).isEqualTo("UNKNOWN");
		assertThat(updated.get("attributes").get("season").stringValue()).isEqualTo("winter");

		mockMvc.perform(delete("/vault/items/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent()).andExpect(content().string(""));
		mockMvc.perform(get("/vault/items/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNotFound()).andExpect(content().json(NOT_FOUND));
	}

	@Test
	void foreignMissingAndMalformedIdentifiersAreOpaque() throws Exception {
		String owner = InProcessJwtFixture.bearerToken();
		String foreign = InProcessJwtFixture.bearerTokenForSubject("foreign-vault-owner");
		String id = create(owner, "TOP", "Private").get("id").stringValue();
		for (String candidate : List.of(id, UUID.randomUUID().toString(), "not-a-uuid")) {
			mockMvc.perform(get("/vault/items/" + candidate).header(HttpHeaders.AUTHORIZATION, bearer(foreign)))
				.andExpect(status().isNotFound()).andExpect(content().json(NOT_FOUND));
			mockMvc.perform(patch("/vault/items/" + candidate).header(HttpHeaders.AUTHORIZATION, bearer(foreign))
					.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"stolen\"}"))
				.andExpect(status().isNotFound()).andExpect(content().json(NOT_FOUND));
			mockMvc.perform(delete("/vault/items/" + candidate).header(HttpHeaders.AUTHORIZATION, bearer(foreign)))
				.andExpect(status().isNotFound()).andExpect(content().json(NOT_FOUND));
		}
		mockMvc.perform(get("/vault/items/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isOk());
	}

	@Test
	void invalidCategoriesAttributesFiltersAndPatchesAreRejectedWithoutMutation() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		for (String body : List.of(createBody("NOT_A_CATEGORY", "Bad"), createBody("TOP", "Bad").replace("{\"size\":\"M\"}", "[]"))) {
			mockMvc.perform(post("/vault/items").header(HttpHeaders.AUTHORIZATION, bearer(token))
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest()).andExpect(content().json(INVALID));
		}
		String id = create(token, "TOP", "Original").get("id").stringValue();
		mockMvc.perform(get("/vault/items").queryParam("category", "BAD").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isBadRequest()).andExpect(content().json(INVALID));
		mockMvc.perform(get("/vault/items").queryParam("brand", " ").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isBadRequest()).andExpect(content().json(INVALID));
		mockMvc.perform(get("/vault/items").queryParam("createdDate", "30-08-2026").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isBadRequest()).andExpect(content().json(INVALID));
		mockMvc.perform(get("/vault/items").queryParam("collection", "bad").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isBadRequest()).andExpect(content().json(INVALID));
		for (String body : List.of("{}", "{\"category\":\"BAD\"}", "{\"attributes\":42}", "{\"storageKey\":\"forged\"}")) {
			mockMvc.perform(patch("/vault/items/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token))
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest()).andExpect(content().json(INVALID));
		}
		MvcResult unchanged = mockMvc.perform(get("/vault/items/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk()).andReturn();
		assertThat(JSON.readTree(unchanged.getResponse().getContentAsString()).get("name").stringValue()).isEqualTo("Original");
	}
	@Test
	void createRejectsUnknownFieldsAndReplayIsIdempotent() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		mockMvc.perform(post("/vault/items").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content(createBody("TOP", "Unknown").replace("}", ",\"storageKey\":\"forged\"}")))
			.andExpect(status().isBadRequest()).andExpect(content().json(INVALID));
		String upload = UUID.randomUUID().toString();
		String body = "{\"uploadId\":\"" + upload + "\",\"name\":\"Replay\",\"brand\":\"Acme\",\"category\":\"TOP\",\"attributes\":{\"size\":\"M\"}}";
		MvcResult first = mockMvc.perform(post("/vault/items").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn();
		MvcResult second = mockMvc.perform(post("/vault/items").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn();
		assertThat(JSON.readTree(second.getResponse().getContentAsString()).get("id").stringValue())
			.isEqualTo(JSON.readTree(first.getResponse().getContentAsString()).get("id").stringValue());
	}

	private JsonNode create(String token, String category, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/vault/items").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(createBody(category, name)))
			.andExpect(status().isCreated()).andReturn();
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private static String createBody(String category, String name) {
		return "{\"uploadId\":\"" + UUID.randomUUID() + "\",\"name\":\"" + name
			+ "\",\"brand\":\"Acme\",\"category\":\"" + category
			+ "\",\"color\":\"Black\",\"attributes\":{\"size\":\"M\"}}";
	}

	private static void assertPublicResource(JsonNode node) {
		assertThat(node.propertyNames()).containsExactly("id", "sourceReferenceId", "name", "brand", "category",
			"subcategory", "color", "pattern", "attributes", "createdAt");
		assertThat(node.toString()).doesNotContain("userId", "storageKey", "imageAssetId", "bucket", "objectKey", "Url", "bytes");
	}

	private static String bearer(String token) { return "Bearer " + token; }

	@TestConfiguration
	static class TestBeans {
		@Bean @Primary ExternalTokenVerifier verifier() { return InProcessJwtFixture.verifier(); }
		@Bean @Primary FakeVaultObjectStore fakeVaultObjectStore() { return new FakeVaultObjectStore(); }
		@Bean JdbcVaultStore jdbcVaultStore(DataSource dataSource) { return new JdbcVaultStore(dataSource); }
	}

	static final class FakeVaultObjectStore implements ObjectStore {
		@Override public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			return new UploadCapability(uploadId, "PUT", URI.create("https://example.invalid"), Map.of(), Instant.now().plusSeconds(60));
		}
		@Override public ObjectMetadata head(ObjectRef object) { return new ObjectMetadata(100, "image/jpeg", "v1"); }
		@Override public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) { return new byte[0]; }
		@Override public void delete(ObjectRef object) { }
		@Override public ClaimResult claim(ClaimCommand command) { return new ClaimResult.Copied(); }
		@Override public void purgeUserObjects(UserId userId) { }
		@Override public void cleanupTemp() { }
		@Override public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			return new PresignedAccess(object, "GET", URI.create("https://example.invalid"), Map.of(), Instant.now().plus(ttl));
		}
		@Override public List<StoredObject> list(StorageDomain boundary, String prefix) { return List.of(); }
	}
}
