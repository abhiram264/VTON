package com.aifashionvault.featureflag;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.user.IdentityProvisioning;
import com.aifashionvault.user.IdentityResolution;
import com.aifashionvault.user.User;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FeatureFlagHttpContractTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FeatureFlags featureFlags;

	@AfterEach
	void tearDown() {
		for (FeatureFlagName name : FeatureFlagName.values()) {
			this.featureFlags.clearOverride(name);
		}
	}

	@Test
	void unauthenticatedGetReturns401() throws Exception {
		this.mockMvc.perform(get("/feature-flags"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void invalidBearerReturns401() throws Exception {
		this.mockMvc.perform(get("/feature-flags")
				.header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void expiredBearerReturns401() throws Exception {
		this.mockMvc.perform(get("/feature-flags")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + InProcessJwtFixture.expiredBearerToken()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedGetReturnsExactS3ObjectWithDefaultsAndEmptyOverrides() throws Exception {
		this.mockMvc.perform(get("/feature-flags")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + InProcessJwtFixture.bearerToken()))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().json("""
				{
				  "flags": {
				    "MULTI_ITEM": true,
				    "NATURAL_LANGUAGE_STUDIO": true,
				    "TRYON_MAX": true,
				    "HD_GENERATION": true,
				    "SHARE_IMPORT_ANDROID": true,
				    "SHARE_IMPORT_IOS": true,
				    "NEW_QUALITY_GATE": false
				  },
				  "overrides": {}
				}
				""", true));
	}

	@Test
	void authenticatedGetWithActiveOverrideReturnsExactS3ObjectAndActiveOverrideOnly() throws Exception {
		this.featureFlags.override(FeatureFlagName.HD_GENERATION, false);

		this.mockMvc.perform(get("/feature-flags")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + InProcessJwtFixture.bearerToken()))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().json("""
				{
				  "flags": {
				    "MULTI_ITEM": true,
				    "NATURAL_LANGUAGE_STUDIO": true,
				    "TRYON_MAX": true,
				    "HD_GENERATION": false,
				    "SHARE_IMPORT_ANDROID": true,
				    "SHARE_IMPORT_IOS": true,
				    "NEW_QUALITY_GATE": false
				  },
				  "overrides": {
				    "HD_GENERATION": false
				  }
				}
				""", true));
	}

	@TestConfiguration
	static class Configuration {

		@Bean
		@Primary
		ExternalTokenVerifier verifier() {
			return InProcessJwtFixture.verifier();
		}

		@Bean
		@Primary
		IdentityProvisioning testIdentityProvisioning() {
			return new IdentityProvisioning() {
				@Override
				public IdentityResolution resolve(String issuer, String subject) {
					return new IdentityResolution.Active(
						new UserId(UUID.nameUUIDFromBytes((issuer + ":" + subject).getBytes(StandardCharsets.UTF_8)))
					);
				}

				@Override
				public User beginDeletion(UserId userId, Instant deletedAt) {
					return null;
				}
			};
		}
	}
}
