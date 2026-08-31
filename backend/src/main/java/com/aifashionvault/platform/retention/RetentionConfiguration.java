package com.aifashionvault.platform.retention;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.retention.RetentionCleanupJob;
import com.aifashionvault.retention.RetentionOutcome;
import com.aifashionvault.retention.RetentionPolicy;
import com.aifashionvault.retention.RetentionRunReport;

@Configuration(proxyBeanMethods = false)
public class RetentionConfiguration {
    @Bean
    @ConfigurationProperties("vault.retention")
    RetentionProperties retentionProperties() { return new RetentionProperties(); }

    @Bean
    RetentionPolicy retentionPolicy(RetentionProperties properties) { return new RetentionPolicy(properties.temporaryMediaMaxAge(), Clock.systemUTC()); }

    @Bean
    PlatformRetentionInventory platformRetentionInventory(ObjectStore store) { return new PlatformRetentionInventory(store); }

    @Bean
    RetentionScheduler retentionScheduler(PlatformRetentionInventory inventory, RetentionPolicy policy) {
        return new RetentionScheduler(new RetentionCleanupJob(inventory, policy));
    }

    public static final class RetentionProperties {
        private Duration temporaryMediaMaxAge = Duration.ofHours(24);
        public Duration temporaryMediaMaxAge() { return temporaryMediaMaxAge; }
        public void setTemporaryMediaMaxAge(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException("temporaryMediaMaxAge must be positive");
            temporaryMediaMaxAge = value;
        }
    }

    static final class RetentionScheduler {
        private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);
        private final RetentionCleanupJob job;
        RetentionScheduler(RetentionCleanupJob job) { this.job = job; }
        @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
        void cleanup() {
            RetentionRunReport report = job.run();
            long listFailed = report.counts().values().stream()
                    .mapToLong(counts -> counts.get(RetentionOutcome.LIST_FAILED)).sum();
            long deleteFailed = report.counts().values().stream()
                    .mapToLong(counts -> counts.get(RetentionOutcome.DELETE_FAILED)).sum();
            if (listFailed > 0 || deleteFailed > 0) {
                log.warn("retention cleanup failures listFailed={} deleteFailed={}", listFailed, deleteFailed);
            }
        }
    }
}
