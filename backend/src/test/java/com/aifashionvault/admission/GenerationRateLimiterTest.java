package com.aifashionvault.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class GenerationRateLimiterTest {
    @Test
    void ordinaryAndRiskLimitsAreExplicitAndStrict() {
        assertThat(GenerationRiskTier.ORDINARY.dailyLimit()).isEqualTo(5);
        assertThat(GenerationRiskTier.ANONYMOUS.dailyLimit()).isLessThan(5);
        assertThat(GenerationRiskTier.NEW_DEVICE.dailyLimit()).isLessThan(5);
        assertThat(GenerationRiskTier.SUSPICIOUS_IP.dailyLimit()).isLessThan(5);
        assertThat(GenerationRiskTier.FAILED_ATTESTATION.dailyLimit()).isLessThan(5);
    }

    @Test
    void decisionUsesFrozenAccessorShape() {
        var day = Instant.parse("2026-08-31T00:00:00Z").atZone(ZoneOffset.UTC).toLocalDate();
        var decision = GenerationAdmissionDecision.accepted(2, 5, day);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.used()).isEqualTo(2);
        assertThat(decision.limit()).isEqualTo(5);
        assertThat(decision.utcDay()).isEqualTo(day);
    }
}
