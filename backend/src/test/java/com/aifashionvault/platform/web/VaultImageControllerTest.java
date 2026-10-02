package com.aifashionvault.platform.web;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

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
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StoredObject;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owner-authorized Vault display capability.
 *
 * <p>The capability is a short-lived bearer URL, so the contract under test is:
 * minted per read (never cached into the item representation), strictly
 * owner-scoped, and distinguishable from "item is gone" when only the bytes are
 * unreadable.</p>
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class VaultImageControllerTest {
	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String NOT_FOUND = "{\"code\":\"VAULT_ITEM_NOT_FOUND\"}";
	private static final String MEDIA_UNAVAILABLE = "{\"code\":\"VAULT_MEDIA_UNAVAILABLE\"}";

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired FakeVaultObjectStore objects;

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
		this.objects.reset();
	}

	@Test
	void imageRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/vault/items/" + UUID.randomUUID() + "/image"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void ownerGetsShortLivedCapabilityWhileForeignReadsStayOpaque() throws Exception {
		String owner = InProcessJwtFixture.bearerToken();
		String foreign = InProcessJwtFixture.bearerTokenForSubject("foreign-vault-image-owner");
		String id = create(owner);
		String storageKey = jdbc.queryForObject(
			"SELECT storage_key FROM vault_item WHERE id=?", String.class, UUID.fromString(id));

		MvcResult result = mockMvc.perform(get("/vault/items/" + id + "/image")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isOk()).andReturn();
		JsonNode capability = JSON.readTree(result.getResponse().getContentAsString());
		assertThat(capability.propertyNames()).containsExactly("url", "expiresAt");
		assertThat(capability.get("url").stringValue()).startsWith("https://example.invalid/");
		assertThat(capability.get("expiresAt").stringValue()).isNotBlank();
		assertThat(result.getResponse().getContentAsString())
			.doesNotContain(storageKey, "VAULT/", "storageKey", "objectKey");

		for (String candidate : List.of(id, UUID.randomUUID().toString(), "not-a-uuid")) {
			mockMvc.perform(get("/vault/items/" + candidate + "/image")
					.header(HttpHeaders.AUTHORIZATION, bearer(foreign)))
				.andExpect(status().isNotFound()).andExpect(content().json(NOT_FOUND));
		}
	}

	@Test
	void capabilityIsReissuedPerRead() throws Exception {
		String owner = InProcessJwtFixture.bearerToken();
		String id = create(owner);
		Instant first = expiresAt(owner, id);
		Instant second = expiresAt(owner, id);
		assertThat(second).isAfter(first);
	}

	@Test
	void unreadableBytesAreUnavailableNotMissing() throws Exception {
		String owner = InProcessJwtFixture.bearerToken();
		String id = create(owner);
		this.objects.failPresign = true;
		mockMvc.perform(get("/vault/items/" + id + "/image").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isServiceUnavailable()).andExpect(content().json(MEDIA_UNAVAILABLE));
		this.objects.failPresign = false;
		mockMvc.perform(get("/vault/items/" + id + "/image").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
			.andExpect(status().isOk());
	}

	private Instant expiresAt(String token, String id) throws Exception {
		MvcResult result = mockMvc.perform(get("/vault/items/" + id + "/image")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk()).andReturn();
		return Instant.parse(JSON.readTree(result.getResponse().getContentAsString())
			.get("expiresAt").stringValue());
	}

	private String create(String token) throws Exception {
		String body = "{\"uploadId\":\"" + UUID.randomUUID() + "\",\"name\":\"Trail Boots\","
			+ "\"brand\":\"Acme\",\"category\":\"FOOTWEAR\",\"attributes\":{\"size\":\"M\"}}";
		MvcResult result = mockMvc.perform(post("/vault/items")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andReturn();
		return JSON.readTree(result.getResponse().getContentAsString()).get("id").stringValue();
	}

	private static String bearer(String token) { return "Bearer " + token; }

	@TestConfiguration
	static class TestBeans {
		@Bean @Primary ExternalTokenVerifier verifier() { return InProcessJwtFixture.verifier(); }
		@Bean @Primary FakeVaultObjectStore fakeVaultObjectStore() { return new FakeVaultObjectStore(); }
	}

	static final class FakeVaultObjectStore implements ObjectStore {
		private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
		private final AtomicLong mints = new AtomicLong();
		volatile boolean failPresign;

		void reset() {
			this.failPresign = false;
			this.mints.set(0);
		}

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
			if (this.failPresign) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("image object is gone");
			}
			return new PresignedAccess(object, "GET", URI.create("https://example.invalid/vault/image"),
				Map.of(), BASE.plusSeconds(this.mints.incrementAndGet()));
		}
		@Override public List<StoredObject> list(StorageDomain boundary, String prefix) { return List.of(); }
	}
}
