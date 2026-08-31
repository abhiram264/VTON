package com.aifashionvault.platform.generation;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.admission.AbuseBudgetPolicy;
import com.aifashionvault.admission.AggregateBackedCostCircuitBreaker;
import com.aifashionvault.admission.GlobalCostCircuitBreaker;
import com.aifashionvault.generation.CallbackInboxStore;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.JdbcCallbackInboxStore;
import com.aifashionvault.generation.QualityGate;
import com.aifashionvault.generation.ProviderHealthGate;
import com.aifashionvault.generation.GenerationCostMetrics;
import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.OutputDeliveryMethod;
import com.aifashionvault.generation.vton.PrivacyTransferPolicy;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.VtonProvider;
import com.aifashionvault.look.LookStore;
import com.aifashionvault.notification.NotificationStore;
import com.aifashionvault.notification.NotificationEventStore;
import com.aifashionvault.platform.fashn.FashnVtonProvider;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.S3ObjectStore;
import com.aifashionvault.platform.queue.GenerationWorkQueue;
import com.aifashionvault.platform.queue.WorkOutboxStore;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({ DataSource.class, ObjectStore.class })
public class GenerationProcessorConfiguration {

	static final String MVP_GLOBAL_SPEND_CEILING = "500.00";
	static final String MVP_PER_USER_SPEND_CEILING = "25.00";
	static final String MVP_PROVIDER_SPEND_CEILING = "250.00";
	static final String MVP_BUDGET_CURRENCY = "USD";
	static final Duration MVP_BUDGET_WINDOW = Duration.ofDays(1);

	@Bean
	@ConditionalOnMissingBean(Clock.class)
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	GenerationMediaStore generationMediaStore(ObjectStore objectStore, ObjectProvider<S3ObjectStore> s3ObjectStores) {
		if (objectStore instanceof GenerationMediaStore mediaStore) {
			return mediaStore;
		}
		return s3ObjectStores.getObject();
	}

	@Bean
	QualityGate qualityGate(ImageInspector imageInspector, ObjectProvider<Clock> clocks) {
		return new ApplicationOwnedQualityGate(
			imageInspector,
			new OnnxCpuQualityVision(),
			clocks.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	PrivacyTransferPolicy privacyTransferPolicy() {
		return new PrivacyTransferPolicy(
			Set.of(InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ),
			Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
			1_000_000L,
			Duration.ofMinutes(5),
			false);
	}

	@Bean
	PrivacyApprovedInputTransfer privacyApprovedInputTransfer(
		PrivacyTransferPolicy privacyTransferPolicy,
		ObjectStore objectStore
	) {
		return new PrivacyApprovedInputTransfer(privacyTransferPolicy, objectStore);
	}

	@Bean
	AuthorizedGenerationMediaResolver authorizedGenerationMediaResolver(
		DataSource dataSource,
		ObjectStore objectStore,
		GenerationStore generationStore
	) {
		return new AuthorizedGenerationMediaResolver(dataSource, objectStore, generationStore);
	}

	@Bean
	@ConditionalOnBean(DataSource.class)
	CallbackInboxStore callbackInboxStore(DataSource dataSource, ObjectProvider<Clock> clocks) {
		return new JdbcCallbackInboxStore(dataSource, clocks.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	@ConditionalOnBean(MeterRegistry.class)
	@ConditionalOnMissingBean(GenerationMetrics.class)
	GenerationMetrics generationMetrics(MeterRegistry registry) {
		return new GenerationMetrics(registry);
	}

	@Bean
	@ConditionalOnMissingBean(GenerationCapacityLeaseStore.class)
	JdbcGenerationCapacityLeaseStore generationCapacityLeaseStore(DataSource dataSource) {
		return new JdbcGenerationCapacityLeaseStore(dataSource);
	}

	@Bean
	@ConditionalOnMissingBean(GlobalCostCircuitBreaker.class)
	GlobalCostCircuitBreaker globalCostCircuitBreaker(
		GenerationCostMetrics costMetrics,
		ObjectProvider<FashnVtonProvider> fashnProviders,
		ObjectProvider<ProviderRegistry> providerRegistries
	) {
		String providerKey = resolveProvider(fashnProviders, providerRegistries).providerKey().value();
		return new AggregateBackedCostCircuitBreaker(
			costMetrics,
			new AbuseBudgetPolicy(
				new BigDecimal(MVP_GLOBAL_SPEND_CEILING),
				new BigDecimal(MVP_PER_USER_SPEND_CEILING),
				Map.of(providerKey, new BigDecimal(MVP_PROVIDER_SPEND_CEILING)),
				MVP_BUDGET_CURRENCY,
				MVP_BUDGET_WINDOW));
	}

	/**
	 * Provider-health gate (Q56) defaulting to all-healthy: an empty
	 * eligibility set allows every provider until an operator marks one
	 * unavailable. Consulted for new executions only.
	 */
	@Bean
	@ConditionalOnMissingBean(ProviderHealthGate.class)
	ProviderHealthGate providerHealthGate() {
		return new StaticProviderHealthGate(Set.of());
	}

	@Bean
	GenerationWorkProcessor generationWorkProcessor(
		GenerationStore generationStore,
		LookStore lookStore,
		NotificationStore notificationStore,
		GenerationWorkQueue queue,
		ObjectProvider<FashnVtonProvider> fashnProviders,
		ObjectProvider<ProviderRegistry> providerRegistries,
		AuthorizedGenerationMediaResolver mediaResolver,
		PrivacyApprovedInputTransfer inputTransfer,
		QualityGate qualityGate,
		GenerationMediaStore mediaStore,
		ObjectStore objectStore,
		ObjectProvider<Clock> clocks,
		ObjectProvider<WorkOutboxStore> workOutboxes,
		ObjectProvider<CallbackInboxStore> callbackInboxes,
		ObjectProvider<GenerationMetrics> metrics,
		ObjectProvider<GenerationCapacityLeaseStore> capacityLeases,
		ObjectProvider<NotificationEventStore> notificationEvents,
		GlobalCostCircuitBreaker costCircuitBreaker,
		ObjectProvider<ProviderHealthGate> providerHealthGates
	) {
		return new GenerationWorkProcessor(
			generationStore,
			lookStore,
			notificationStore,
			queue,
			resolveProvider(fashnProviders, providerRegistries),
			mediaResolver,
			inputTransfer,
			qualityGate,
			mediaStore,
			objectStore,
			clocks.getIfAvailable(Clock::systemUTC),
			providerRegistries.getIfAvailable(),
			workOutboxes.getIfAvailable(),
			callbackInboxes.getIfAvailable(),
			metrics.getIfAvailable(),
			capacityLeases.getIfAvailable(),
			notificationEvents.getIfAvailable(),
			null,
			costCircuitBreaker,
			providerHealthGates.getIfAvailable(this::providerHealthGate));
	}

	@Bean
	GenerationWorkWorker generationWorkWorker(GenerationWorkQueue queue, GenerationWorkProcessor processor) {
		return new GenerationWorkWorker(queue, processor);
	}

	@Bean
	@ConditionalOnBean({ GenerationStore.class, ObjectStore.class })
	GenerationAccountMediaPurge generationAccountMediaPurge(
		GenerationStore generationStore,
		ObjectStore objectStore,
		ObjectProvider<Clock> clocks
	) {
		return new GenerationAccountMediaPurge(
			generationStore,
			objectStore,
			clocks.getIfAvailable(Clock::systemUTC));
	}

	private static VtonProvider resolveProvider(
		ObjectProvider<FashnVtonProvider> fashnProviders,
		ObjectProvider<ProviderRegistry> providerRegistries
	) {
		ProviderRegistry registry = providerRegistries.getIfAvailable();
		if (registry != null) {
			return registry.selectedProvider();
		}
		FashnVtonProvider fashn = fashnProviders.getIfAvailable();
		if (fashn != null) {
			return fashn;
		}
		throw new IllegalStateException("VtonProvider is required");
	}
}
