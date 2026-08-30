package com.aifashionvault.platform.web;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.collection.CollectionStore;
import com.aifashionvault.collection.JdbcCollectionStore;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class CollectionsControllerTest {
	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18").withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
	@Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;

	@DynamicPropertySource static void database(DynamicPropertyRegistry registry) { POSTGRES.start(); registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername); registry.add("spring.datasource.password", POSTGRES::getPassword); }
	@BeforeEach void reset() { jdbc.update("TRUNCATE collection, vault_item, identity_mapping, users CASCADE"); }

	@Test void allSevenRoutesRequireAuthentication() throws Exception {
		String id = UUID.randomUUID().toString();
		mvc.perform(post("/collections").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"One\"}")).andExpect(status().isUnauthorized());
		mvc.perform(get("/collections")).andExpect(status().isUnauthorized()); mvc.perform(get("/collections/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(patch("/collections/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Two\"}")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/collections/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(post("/collections/" + id + "/items").contentType(MediaType.APPLICATION_JSON).content("{\"vaultItemId\":\"" + id + "\"}")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/collections/" + id + "/items/" + id)).andExpect(status().isUnauthorized());
	}

	@Test void authenticatedCrudHasExactSafeShapeAndMaskedIdentifiers() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		mvc.perform(get("/collections").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk()).andExpect(content().string("[]"));
		var created = mvc.perform(post("/collections").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  Wedding  \"}"))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/collections/"))).andReturn();
		JsonNode resource = JSON.readTree(created.getResponse().getContentAsString()); String id = resource.get("id").stringValue(); assertSafe(resource); assertThat(resource.get("name").stringValue()).isEqualTo("Wedding"); assertThat(resource.get("items").isEmpty()).isTrue(); assertThat(resource.get("createdAt")).isEqualTo(resource.get("updatedAt"));
		mvc.perform(get("/collections/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
		mvc.perform(patch("/collections/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"));
		String foreign = InProcessJwtFixture.bearerTokenForSubject("foreign-collections-owner");
		for (String candidate : List.of(id, UUID.randomUUID().toString(), "bad")) mvc.perform(get("/collections/" + candidate).header(HttpHeaders.AUTHORIZATION, bearer(foreign))).andExpect(status().isNotFound()).andExpect(content().json("{\"code\":\"COLLECTION_NOT_FOUND\"}"));
		mvc.perform(delete("/collections/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isNoContent()).andExpect(content().string(""));
	}

	@Test void invalidBodiesAndMembershipOwnershipAreExactAndIdempotent() throws Exception {
		String token = InProcessJwtFixture.bearerToken(); JsonNode collection = create(token, "One"); String id = collection.get("id").stringValue();
		for (String body : List.of("{}", "{\"name\":null}", "{\"name\":\" \"}", "{\"name\":42}", "{")) mvc.perform(post("/collections").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(content().json("{\"code\":\"INVALID_COLLECTION\"}"));
		UUID owned = insertVaultForSubject(InProcessJwtFixture.subject()); UUID foreign = insertVaultForSubject("foreign-item-owner");
		for (String item : List.of(foreign.toString(), UUID.randomUUID().toString(), "bad")) mvc.perform(post("/collections/" + id + "/items").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{\"vaultItemId\":\"" + item + "\"}"))
			.andExpect(status().isNotFound()).andExpect(content().json("{\"code\":\"VAULT_ITEM_NOT_FOUND\"}"));
		String add = "{\"vaultItemId\":\"" + owned + "\"}";
		mvc.perform(post("/collections/" + id + "/items").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content(add)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
		mvc.perform(post("/collections/" + id + "/items").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content(add)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
		mvc.perform(delete("/collections/" + id + "/items/" + owned).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isNoContent());
		mvc.perform(delete("/collections/" + id + "/items/" + owned).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM vault_item WHERE id=?", Integer.class, owned)).isOne();
	}

	private JsonNode create(String token, String name) throws Exception { return JSON.readTree(mvc.perform(post("/collections").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()); }
	private UUID insertVaultForSubject(String subject) { String token = InProcessJwtFixture.bearerTokenForSubject(subject); try { mvc.perform(get("/collections").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk()); } catch (Exception e) { throw new RuntimeException(e); } UUID user = jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject=?", UUID.class, subject); UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO vault_item(id,user_id,storage_key,created_at) VALUES(?,?,?,?)", id, user, "vault/" + id, Timestamp.from(NOW)); return id; }
	private static void assertSafe(JsonNode node) { assertThat(node.propertyNames()).containsExactly("id", "name", "createdAt", "updatedAt", "items"); assertThat(node.toString()).doesNotContain("userId", "storageKey", "mediaUrl", "image", "firebase", "orgId", "tenantId"); }
	private static String bearer(String token) { return "Bearer " + token; }

	@TestConfiguration static class Beans {
		@Bean @Primary ExternalTokenVerifier verifier() { return InProcessJwtFixture.verifier(); }
		@Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
	}
}
