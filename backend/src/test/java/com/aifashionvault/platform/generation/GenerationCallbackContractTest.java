package com.aifashionvault.platform.generation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.generation.CallbackInbox;
import com.aifashionvault.generation.GenerationAttempt;
import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.JdbcCallbackInboxStore;
import com.aifashionvault.generation.JdbcGenerationStore;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.platform.web.GenerationCallbackController;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class GenerationCallbackContractTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final String TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String OTHER_TOKEN = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	private JdbcGenerationStore generationStore;
	private JdbcCallbackInboxStore inboxStore;
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void registerDatasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void setUp() {
		jdbc.update(
			"TRUNCATE TABLE quality_result, generation_callback_inbox, generation_work_outbox, "
				+ "generation_quota_reservation, generation_notification_delivery, "
				+ "generation_notification_event, generation_provider_capacity_slot, "
				+ "generation_attempt, generation_render_step_item, generation_render_step, "
				+ "generation_job_product, generation_job, vault_item, studio_item, studio_session, "
				+ "avatar_image, avatar_profile, users CASCADE");
		generationStore = new JdbcGenerationStore(dataSource, CLOCK);
		inboxStore = new JdbcCallbackInboxStore(dataSource, CLOCK);
		mockMvc = MockMvcBuilders.standaloneSetup(new GenerationCallbackController(inboxStore, CLOCK)).build();
	}

	@Test
	void validCallbackReturns204InsertsInboxAndOutboxAndIsIdempotent() throws Exception {
		Seeded seeded = seedAttempt();
		String hash = GenerationCallbackController.sha256Hex(TOKEN);
		inboxStore.register(seeded.attemptId(), hash, NOW);

		String body = "{\"providerJobId\":\"cb-job-1\",\"terminalHint\":\"SUCCEEDED\","
			+ "\"outputUrl\":\"https://cdn.provider.test/out.jpg\",\"error\":\"provider-body\"}";
		mockMvc.perform(post("/generation-callbacks/" + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		CallbackInbox inbox = inboxStore.findByDedupeHash(hash).orElseThrow();
		assertThat(inbox.generationAttemptId()).isEqualTo(seeded.attemptId());
		assertThat(inbox.candidateProviderJobId()).isEqualTo("cb-job-1");
		assertThat(inbox.safeTerminalHint()).isEqualTo("SUCCEEDED");
		assertThat(count("generation_callback_inbox")).isEqualTo(1);
		assertThat(count("generation_work_outbox")).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT generation_job_id FROM generation_work_outbox WHERE id = ?",
			UUID.class,
			inbox.id())).isEqualTo(seeded.jobId().value());

		mockMvc.perform(post("/generation-callbacks/" + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isNoContent());
		assertThat(count("generation_callback_inbox")).isEqualTo(1);
		assertThat(count("generation_work_outbox")).isEqualTo(1);
		assertThat(generationStore.findAttempt(seeded.attemptId()).orElseThrow().providerJobId()).isNull();
	}

	@Test
	void invalidAndUnknownTokensAreIndistinguishableNotFound() throws Exception {
		Seeded seeded = seedAttempt();
		inboxStore.register(seeded.attemptId(), GenerationCallbackController.sha256Hex(TOKEN), NOW);

		String unknown = mockMvc.perform(post("/generation-callbacks/" + OTHER_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerJobId\":\"x\"}"))
			.andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String shortToken = mockMvc.perform(post("/generation-callbacks/abcd")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerJobId\":\"x\"}"))
			.andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(unknown).isEqualTo(shortToken);
		assertThat(count("generation_callback_inbox")).isEqualTo(1);
		assertThat(count("generation_work_outbox")).isZero();
	}

	@Test
	void callbackDoesNotPersistRawBodyOrLogIt() throws Exception {
		Seeded seeded = seedAttempt();
		String hash = GenerationCallbackController.sha256Hex(TOKEN);
		inboxStore.register(seeded.attemptId(), hash, NOW);
		Logger logger = (Logger) LoggerFactory.getLogger(GenerationCallbackController.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		String secret = "https://cdn.provider.test/secret.jpg";
		try {
			mockMvc.perform(post("/generation-callbacks/" + TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"providerJobId\":\"cb-job-2\",\"outputUrl\":\"" + secret + "\"}"))
				.andExpect(status().isNoContent());
			String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
			assertThat(logs).doesNotContain(secret, TOKEN, "provider-body");
		}
		finally {
			logger.detachAppender(appender);
		}
		for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM generation_callback_inbox")) {
			for (Object value : row.values()) {
				if (value == null) {
					continue;
				}
				assertThat(String.valueOf(value))
					.doesNotContain("https://", secret, "outputUrl", "provider-body");
			}
		}
		assertThat(inboxStore.findByDedupeHash(hash).orElseThrow().candidateProviderJobId()).isEqualTo("cb-job-2");
	}

	@Test
	void lateCallbackAfterCompletedDoesNotResurrect() throws Exception {
		Seeded seeded = seedAttempt();
		GenerationAttempt pending = generationStore.findAttempt(seeded.attemptId()).orElseThrow();
		assertThat(generationStore.completeTerminalIfActive(finishedSuccess(pending, NOW.plusSeconds(2)), NOW.plusSeconds(2)))
			.isTrue();
		assertThat(generationStore.findJob(seeded.jobId()).orElseThrow().status()).isEqualTo(GenerationJob.COMPLETED);
		String hash = GenerationCallbackController.sha256Hex(TOKEN);
		inboxStore.register(seeded.attemptId(), hash, NOW);
		mockMvc.perform(post("/generation-callbacks/" + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"providerJobId\":\"late-job\",\"terminalHint\":\"SUCCEEDED\"}"))
			.andExpect(status().isNoContent());
		GenerationJob loaded = generationStore.findJob(seeded.jobId()).orElseThrow();
		assertThat(loaded.status()).isEqualTo(GenerationJob.COMPLETED);
		assertThat(loaded.completedAt()).isEqualTo(NOW.plusSeconds(2));
		assertThat(generationStore.findAttempt(seeded.attemptId()).orElseThrow().providerJobId()).isNull();
	}

	private int count(String table) {
		Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		return value == null ? 0 : value;
	}

	private Seeded seedAttempt() {
		UserId userId = new UserId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			userId.value(),
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		GenerationJob job = new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			GenerationJob.QUEUED,
			"try-on",
			new ClientRequestId("req-" + UUID.randomUUID()),
			NOW,
			null,
			null,
			List.of(),
			null,
			null);
		generationStore.insertJob(job);
		GenerationAttemptId attemptId = new GenerationAttemptId(UUID.randomUUID());
		generationStore.insertAttempt(new GenerationAttempt(
			attemptId,
			job.id(),
			"example-provider",
			null,
			null,
			1,
			NOW,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			SubmissionKey.forAttempt(attemptId),
			null,
			false,
			null,
			null));
		return new Seeded(job.id(), attemptId);
	}

	private static GenerationAttempt finishedSuccess(GenerationAttempt pending, Instant completedAt) {
		return new GenerationAttempt(
			pending.id(),
			pending.generationJobId(),
			pending.provider(),
			"example-model",
			null,
			pending.attemptNumber(),
			pending.startedAt(),
			completedAt,
			100,
			new BigDecimal("1.0"),
			new BigDecimal("0.01"),
			"USD",
			null,
			"SUCCESS",
			pending.submissionKey(),
			NOW,
			false,
			new StorageKey("temp/user/generation/" + pending.id().value() + ".jpg"),
			new ProviderOutputRef("provider-output-1"));
	}

	private record Seeded(GenerationJobId jobId, GenerationAttemptId attemptId) {
	}
}
