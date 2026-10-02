package com.aifashionvault.platform.generation;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
import com.aifashionvault.privacy.ConsentService;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Generation processing graph: work processor/worker, authorized media
 * resolver, callback inbox, media store, quality gate, privacy input transfer,
 * capacity/cost admission, account media purge. Registered through late-binding
 * FactoryBeans (vton-test-datasource-beans convention; GenerationQueueConfiguration,
 * NotificationConfiguration, VtonPerformanceConfiguration precedents): the DataSource,
 * the MeterRegistry, and the FactoryBean-backed stores arrive only after this scanned
 * configuration is parsed, so a parse-time {@code @ConditionalOnBean} on
 * DataSource/ObjectStore here skipped the whole graph in every context. Every product
 * whose prerequisites are absent resolves to no bean (null), which keeps
 * DataSource/ObjectStore-free slim contexts loadable; the full graph constructs when
 * the prerequisites exist. Bean names and product types are unchanged.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GenerationSpendProperties.class)
public class GenerationProcessorConfiguration {

	@Bean
	@ConditionalOnMissingBean(Clock.class)
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	ThreadPoolTaskScheduler generationWorkTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("generation-worker-");
		return scheduler;
	}

	@Bean
	ThreadPoolTaskScheduler taskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(2);
		scheduler.setThreadNamePrefix("scheduled-");
		return scheduler;
	}

	@Bean
	@Primary
	FactoryBean<GenerationMediaStore> generationMediaStore(
		ObjectProvider<ObjectStore> objectStores,
		ObjectProvider<S3ObjectStore> s3ObjectStores,
		@Qualifier("stubGenerationMediaStore") ObjectProvider<GenerationMediaStore> externalMediaStores
	) {
		return factory(GenerationMediaStore.class, () -> {
			GenerationMediaStore external = externalMediaStores.getIfAvailable();
			if (external != null) {
				return external;
			}
			ObjectStore objectStore = objectStores.getIfAvailable();
			if (objectStore instanceof GenerationMediaStore mediaStore) {
				return mediaStore;
			}
			return objectStore == null ? null : s3ObjectStores.getIfAvailable();
		});
	}

	@Bean
	FactoryBean<QualityGate> qualityGate(
		ObjectProvider<ImageInspector> imageInspectors,
		ObjectProvider<Clock> clocks
	) {
		return factory(QualityGate.class, () -> {
			ImageInspector imageInspector = imageInspectors.getIfAvailable();
			return imageInspector == null
				? null
				: new ApplicationOwnedQualityGate(
					imageInspector,
					new OnnxCpuQualityVision(),
					clocks.getIfAvailable(Clock::systemUTC));
		});
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
	FactoryBean<PrivacyApprovedInputTransfer> privacyApprovedInputTransfer(
		PrivacyTransferPolicy privacyTransferPolicy,
		ObjectProvider<ObjectStore> objectStores,
		ObjectProvider<ConsentService> consentServices
	) {
		return factory(PrivacyApprovedInputTransfer.class, () -> {
			ObjectStore objectStore = objectStores.getIfAvailable();
			return objectStore == null
				? null
				: new PrivacyApprovedInputTransfer(privacyTransferPolicy, objectStore,
					consentServices.getIfAvailable());
		});
	}

	@Bean
	FactoryBean<AuthorizedGenerationMediaResolver> authorizedGenerationMediaResolver(
		ObjectProvider<DataSource> dataSources,
		ObjectProvider<ObjectStore> objectStores,
		ObjectProvider<GenerationStore> generationStores
	) {
		return factory(AuthorizedGenerationMediaResolver.class, () -> {
			DataSource dataSource = dataSources.getIfAvailable();
			ObjectStore objectStore = objectStores.getIfAvailable();
			GenerationStore generationStore = generationStores.getIfAvailable();
			if (dataSource == null || objectStore == null || generationStore == null) {
				return null;
			}
			return new AuthorizedGenerationMediaResolver(dataSource, objectStore, generationStore);
		});
	}

	@Bean
	FactoryBean<CallbackInboxStore> callbackInboxStore(
		ObjectProvider<DataSource> dataSources,
		ObjectProvider<Clock> clocks
	) {
		return factory(CallbackInboxStore.class, () -> {
			DataSource dataSource = dataSources.getIfAvailable();
			return dataSource == null
				? null
				: new JdbcCallbackInboxStore(dataSource, clocks.getIfAvailable(Clock::systemUTC));
		});
	}

	@Bean
	@ConditionalOnMissingBean(GenerationMetrics.class)
	FactoryBean<GenerationMetrics> generationMetrics(ObjectProvider<MeterRegistry> registries) {
		return factory(GenerationMetrics.class, () -> {
			MeterRegistry registry = registries.getIfAvailable();
			return registry == null ? null : new GenerationMetrics(registry);
		});
	}

	@Bean
	@ConditionalOnMissingBean(GenerationCapacityLeaseStore.class)
	FactoryBean<JdbcGenerationCapacityLeaseStore> generationCapacityLeaseStore(
		ObjectProvider<DataSource> dataSources
	) {
		return factory(JdbcGenerationCapacityLeaseStore.class, () -> {
			DataSource dataSource = dataSources.getIfAvailable();
			return dataSource == null ? null : new JdbcGenerationCapacityLeaseStore(dataSource);
		});
	}

	/**
	 * Product-lifecycle analytics sink (C22). Late-binding like the metrics bean: it
	 * exists only once a MeterRegistry is present, so slim contexts stay loadable and
	 * the processor's optional dependency simply resolves to null.
	 */
	@Bean
	@ConditionalOnMissingBean(com.aifashionvault.analytics.ProductMetrics.class)
	FactoryBean<com.aifashionvault.analytics.ProductMetrics> productMetrics(ObjectProvider<MeterRegistry> registries) {
		return factory(com.aifashionvault.analytics.ProductMetrics.class, () -> {
			MeterRegistry registry = registries.getIfAvailable();
			return registry == null ? null : new com.aifashionvault.analytics.MicrometerProductMetrics(registry);
		});
	}

	@Bean
	@ConditionalOnMissingBean(GlobalCostCircuitBreaker.class)
	FactoryBean<GlobalCostCircuitBreaker> globalCostCircuitBreaker(
		ObjectProvider<GenerationCostMetrics> costMetrics,
		ObjectProvider<FashnVtonProvider> fashnProviders,
		ObjectProvider<ProviderRegistry> providerRegistries,
		GenerationSpendProperties spendProperties
	) {
		return factory(GlobalCostCircuitBreaker.class, () -> {
			GenerationCostMetrics metrics = costMetrics.getIfAvailable();
			if (metrics == null) {
				return null;
			}
			VtonProvider provider = resolveProvider(fashnProviders, providerRegistries);
			if (provider == null) {
				return null;
			}
			String providerKey = provider.providerKey().value();
			return new AggregateBackedCostCircuitBreaker(
				metrics,
				abuseBudgetPolicy(spendProperties, providerKey));
		});
	}

	/**
	 * Approved spend caps (C19) come from {@code vault.generation.spend} rather
	 * than compiled constants; the per-provider ceiling applies to the provider
	 * the registry selects at runtime.
	 */
	static AbuseBudgetPolicy abuseBudgetPolicy(GenerationSpendProperties properties, String providerKey) {
		return new AbuseBudgetPolicy(
			properties.getGlobalCeiling(),
			properties.getPerUserCeiling(),
			Map.of(providerKey, properties.getProviderCeiling()),
			properties.getCurrency(),
			properties.getWindow());
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
	FactoryBean<GenerationWorkProcessor> generationWorkProcessor(
		ObjectProvider<GenerationStore> generationStores,
		ObjectProvider<LookStore> lookStores,
		ObjectProvider<NotificationStore> notificationStores,
		ObjectProvider<GenerationWorkQueue> queues,
		ObjectProvider<FashnVtonProvider> fashnProviders,
		ObjectProvider<ProviderRegistry> providerRegistries,
		ObjectProvider<AuthorizedGenerationMediaResolver> mediaResolvers,
		ObjectProvider<PrivacyApprovedInputTransfer> inputTransfers,
		ObjectProvider<QualityGate> qualityGates,
		ObjectProvider<GenerationMediaStore> mediaStores,
		ObjectProvider<ObjectStore> objectStores,
		ObjectProvider<Clock> clocks,
		ObjectProvider<WorkOutboxStore> workOutboxes,
		ObjectProvider<CallbackInboxStore> callbackInboxes,
		ObjectProvider<GenerationMetrics> metrics,
		ObjectProvider<com.aifashionvault.analytics.ProductMetrics> productMetrics,
		ObjectProvider<GenerationCapacityLeaseStore> capacityLeases,
		ObjectProvider<NotificationEventStore> notificationEvents,
		ObjectProvider<GlobalCostCircuitBreaker> costCircuitBreakers,
		ObjectProvider<ProviderHealthGate> providerHealthGates
	) {
		return factory(GenerationWorkProcessor.class, () -> {
			GenerationStore generationStore = generationStores.getIfAvailable();
			LookStore lookStore = lookStores.getIfAvailable();
			NotificationStore notificationStore = notificationStores.getIfAvailable();
			GenerationWorkQueue queue = queues.getIfAvailable();
			AuthorizedGenerationMediaResolver mediaResolver = mediaResolvers.getIfAvailable();
			PrivacyApprovedInputTransfer inputTransfer = inputTransfers.getIfAvailable();
			QualityGate qualityGate = qualityGates.getIfAvailable();
			GenerationMediaStore mediaStore = mediaStores.getIfAvailable();
			ObjectStore objectStore = objectStores.getIfAvailable();
			GlobalCostCircuitBreaker costCircuitBreaker = costCircuitBreakers.getIfAvailable();
			if (generationStore == null || lookStore == null || notificationStore == null
				|| queue == null || mediaResolver == null || inputTransfer == null
				|| qualityGate == null || mediaStore == null || objectStore == null
				|| costCircuitBreaker == null) {
				return null;
			}
			VtonProvider provider = resolveProvider(fashnProviders, providerRegistries);
			if (provider == null) {
				return null;
			}
			return new GenerationWorkProcessor(
				generationStore,
				lookStore,
				notificationStore,
				queue,
				provider,
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
				providerHealthGates.getIfAvailable(this::providerHealthGate),
				productMetrics.getIfAvailable());
		});
	}

	@Bean
	GenerationWorkWorker generationWorkWorker(
		ObjectProvider<GenerationWorkQueue> queues,
		ObjectProvider<GenerationWorkProcessor> processors,
		@Qualifier("generationWorkTaskScheduler") TaskScheduler scheduler
	) {
		GenerationWorkQueue queue = queues.getIfAvailable();
		GenerationWorkProcessor processor = processors.getIfAvailable();
		return queue == null || processor == null
			? null
			: new GenerationWorkWorker(queue, processor, scheduler);
	}

	@Bean
	FactoryBean<GenerationAccountMediaPurge> generationAccountMediaPurge(
		ObjectProvider<GenerationStore> generationStores,
		ObjectProvider<ObjectStore> objectStores,
		ObjectProvider<Clock> clocks
	) {
		return factory(GenerationAccountMediaPurge.class, () -> {
			GenerationStore generationStore = generationStores.getIfAvailable();
			ObjectStore objectStore = objectStores.getIfAvailable();
			return generationStore == null || objectStore == null
				? null
				: new GenerationAccountMediaPurge(
					generationStore,
					objectStore,
					clocks.getIfAvailable(Clock::systemUTC));
		});
	}

	private static VtonProvider resolveProvider(
		ObjectProvider<FashnVtonProvider> fashnProviders,
		ObjectProvider<ProviderRegistry> providerRegistries
	) {
		ProviderRegistry registry = providerRegistries.getIfAvailable();
		if (registry != null) {
			return registry.selectedProvider();
		}
		// No silent provider selection: without a ProviderRegistry there is no provider,
		// and the create path refuses admission with GENERATION_PROVIDER_UNAVAILABLE.
		// Auto-selecting a bare provider bean here would defeat the honest-unavailable
		// posture that the conditional provider beans establish.
		FashnVtonProvider fashn = fashnProviders.getIfAvailable();
		if (fashn != null) {
			throw new IllegalStateException(
				"a VtonProvider bean exists without a ProviderRegistry; refusing to select a default provider");
		}
		// Keyless deployment: no registry and no provider bean at all is the legitimate
		// honest-unavailable posture (boots, 503 on create), so the worker prerequisites
		// are simply absent — callers yield a NullBean, not a startup failure.
		return null;
	}

	// Shared late-binding FactoryBean shape (NotificationConfiguration#factory
	// precedent): explicit product type, singleton, null product when the
	// ObjectProvider-resolved prerequisites are absent.
	private static <T> FactoryBean<T> factory(Class<T> objectType, Supplier<T> constructor) {
		return new FactoryBean<>() {
			@Override
			public T getObject() {
				return constructor.get();
			}

			@Override
			public Class<?> getObjectType() {
				return objectType;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}
}
