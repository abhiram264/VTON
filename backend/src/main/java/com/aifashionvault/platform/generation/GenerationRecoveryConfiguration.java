package com.aifashionvault.platform.generation;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.platform.queue.WorkOutboxStore;

/**
 * Wires the bounded due-job recovery scanner (Q42).
 *
 * <p>The scanner only repairs lost wake-ups; publication still flows through
 * the durable outbox relay. It therefore follows the same opt-in property
 * convention as {@code GenerationWorkOutboxRelay}
 * ({@code vault.generation.queue.relay-enabled}): recovery is disabled unless
 * explicitly enabled via {@link #RECOVERY_ENABLED_PROPERTY}, and operators
 * enable it alongside the relay it depends on for SQS publication.</p>
 */
@Configuration(proxyBeanMethods = false)
public class GenerationRecoveryConfiguration {

	/** Opt-in property for the lost-wakeup recovery scanner; defaults to off. */
	static final String RECOVERY_ENABLED_PROPERTY = "vault.generation.recovery.enabled";

	@Bean
	@ConditionalOnProperty(name = RECOVERY_ENABLED_PROPERTY, havingValue = "true")
	GenerationDueJobScanner generationDueJobScanner(
		ObjectProvider<GenerationStore> generationStores,
		ObjectProvider<WorkOutboxStore> workOutboxStores,
		ObjectProvider<Clock> clocks
	) {
		GenerationStore generationStore = generationStores.getIfAvailable();
		WorkOutboxStore workOutboxStore = workOutboxStores.getIfAvailable();
		return generationStore == null || workOutboxStore == null
			? null
			: new GenerationDueJobScanner(
				generationStore,
				workOutboxStore,
				clocks.getIfAvailable(Clock::systemUTC));
	}
}
