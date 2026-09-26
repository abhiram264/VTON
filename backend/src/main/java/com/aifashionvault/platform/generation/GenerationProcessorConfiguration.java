package com.aifashionvault.platform.generation;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
	FactoryBean<GenerationMediaStore> generationMediaStore(
		ObjectProvider<ObjectStore> objectStores,
		ObjectProvider<S3ObjectStore> s3ObjectStores
	) {
		return factory(GenerationMediaStore.class, () -> {
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
		ObjectProvider<ObjectStore> objectStores
	) {
		return factory(PrivacyApprovedInputTransfer.class, () -> {
			ObjectStore objectStore = objectStores.getIfAvailable();
			return objectStore == null
				? null
				: new PrivacyApprovedInputTransfer(privacyTransferPolicy, objectStore);
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

	@Bean
	@ConditionalOnMissingBean(GlobalCostCircuitBreaker.class)
	FactoryBean<GlobalCostCircuitBreaker> globalCostCircuitBreaker(
		ObjectProvider<GenerationCostMetrics> costMetrics,
		ObjectProvider<FashnVtonProvider> fashnProviders,
		ObjectProvider<ProviderRegistry> providerRegistries
	) {
		return factory(GlobalCostCircuitBreaker.class, () -> {
			GenerationCostMetrics metrics = costMetrics.getIfAvailable();
			if (metrics == null) {
				return null;
			}
			String providerKey = resolveProvider(fashnProviders, providerRegistries).providerKey().value();
			return new AggregateBackedCostCircuitBreaker(
				metrics,
				new AbuseBudgetPolicy(
					new BigDecimal(MVP_GLOBAL_SPEND_CEILING),
					new BigDecimal(MVP_PER_USER_SPEND_CEILING),
					Map.of(providerKey, new BigDecimal(MVP_PROVIDER_SPEND_CEILING)),
					MVP_BUDGET_CURRENCY,
					MVP_BUDGET_WINDOW));
		});
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
		FashnVtonProvider fashn = fashnProviders.getIfAvailable();
		if (fashn != null) {
			return fashn;
		}
		throw new IllegalStateException("VtonProvider is required");
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
