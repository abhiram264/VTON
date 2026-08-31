package com.aifashionvault.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.UUID;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.UserId;

class JdbcGenerationRateLimiterTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
        .withDatabaseName("postgres")
        .withStartupTimeout(Duration.ofMinutes(5));
    private static final TimeZone ORIGINAL_TIME_ZONE = TimeZone.getDefault();
    private static final UserId USER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-31T12:00:00Z");
    private JdbcTemplate jdbc;

    @BeforeAll
    static void database() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @AfterAll
    static void restoreDefaultTimeZone() {
        TimeZone.setDefault(ORIGINAL_TIME_ZONE);
    }

    @BeforeEach
    void reset() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.update("TRUNCATE generation_admission_counter");
    }

    @Test
    void acceptedRequestsIncrementDurableCounters() {
        var limiter = limiter(Clock.fixed(REQUESTED_AT, ZoneOffset.UTC));

        var first = limiter.admit(request("device-1", "198.51.100.1"));
        var second = limiter.admit(request("device-1", "198.51.100.1"));

        assertThat(first.allowed()).isTrue();
        assertThat(first.used()).isEqualTo(1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.used()).isEqualTo(2);
        assertThat(counter("user:" + USER.value())).isEqualTo(2);
        assertThat(counter("device:device-1")).isEqualTo(2);
        assertThat(counter("ip:198.51.100.1")).isEqualTo(2);
    }

    @Test
    void repeatedRequestsRejectAtConfiguredDailyLimit() {
        var limiter = limiter(Clock.fixed(REQUESTED_AT, ZoneOffset.UTC));

        for (int i = 0; i < GenerationRiskTier.ORDINARY.dailyLimit(); i++) {
            assertThat(limiter.admit(request("device-1", "198.51.100.1")).allowed()).isTrue();
        }
        var rejected = limiter.admit(request("device-1", "198.51.100.1"));

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.reason()).isEqualTo("USER_DAILY_LIMIT");
        assertThat(rejected.used()).isEqualTo(GenerationRiskTier.ORDINARY.dailyLimit());
        assertThat(rejected.limit()).isEqualTo(GenerationRiskTier.ORDINARY.dailyLimit());
        assertThat(counter("user:" + USER.value())).isEqualTo(GenerationRiskTier.ORDINARY.dailyLimit());
    }

    @Test
    void bucketsByUtcDayAcrossTimezoneBoundary() {
        var beforeMidnight = limiter(Clock.fixed(Instant.parse("2026-08-31T23:59:59Z"), ZoneId.of("Pacific/Auckland")));
        var afterMidnight = limiter(Clock.fixed(Instant.parse("2026-09-01T00:00:01Z"), ZoneId.of("America/Los_Angeles")));

        var first = beforeMidnight.admit(request("device-1", "198.51.100.1"));
        var second = afterMidnight.admit(request("device-1", "198.51.100.1"));

        assertThat(first.utcDay()).isEqualTo(java.time.LocalDate.parse("2026-08-31"));
        assertThat(second.utcDay()).isEqualTo(java.time.LocalDate.parse("2026-09-01"));
        assertThat(first.used()).isEqualTo(1);
        assertThat(second.used()).isEqualTo(1);
        assertThat(counter("user:" + USER.value(), "2026-08-31")).isEqualTo(1);
        assertThat(counter("user:" + USER.value(), "2026-09-01")).isEqualTo(1);
    }

    @Test
    void blankOrMissingDeviceAndIpAreSafelyRejectedWithoutDatabaseAccess() {
        DataSource source = mock(DataSource.class);
        var limiter = new JdbcGenerationRateLimiter(source, Clock.fixed(REQUESTED_AT, ZoneOffset.UTC));

        var missingDevice = limiter.admit(request(null, "198.51.100.1"));
        var blankIp = limiter.admit(request("device-1", "  "));

        assertThat(missingDevice.allowed()).isFalse();
        assertThat(missingDevice.reason()).isEqualTo("DEVICE_DAILY_LIMIT");
        assertThat(blankIp.allowed()).isFalse();
        assertThat(blankIp.reason()).isEqualTo("IP_DAILY_LIMIT");
        verifyNoInteractions(source);
    }

    private GenerationAdmissionRequest request(String device, String ip) {
        return new GenerationAdmissionRequest(USER, device, ip, GenerationRiskTier.ORDINARY, REQUESTED_AT);
    }

    private JdbcGenerationRateLimiter limiter(Clock clock) {
        return new JdbcGenerationRateLimiter(jdbc.getDataSource(), clock);
    }

    private long counter(String key) {
        return counter(key, "2026-08-31");
    }

    private long counter(String key, String day) {
        return jdbc.queryForObject(
            "SELECT count FROM generation_admission_counter WHERE bucket_key = ? AND bucket_day = ?",
            Long.class, key, java.sql.Date.valueOf(day));
    }
}
