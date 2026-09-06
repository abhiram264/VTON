package com.aifashionvault.notification;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

@Configuration(proxyBeanMethods = false)
public class NotificationConfiguration {

	private static final Logger log = LoggerFactory.getLogger(NotificationConfiguration.class);

	@Bean
	FactoryBean<JdbcNotificationStore> jdbcNotificationStore(ObjectProvider<DataSource> dataSources) {
		return factory(dataSources, JdbcNotificationStore.class, JdbcNotificationStore::new);
	}

	@Bean
	FactoryBean<JdbcNotificationEventStore> jdbcNotificationEventStore(ObjectProvider<DataSource> dataSources) {
		return factory(dataSources, JdbcNotificationEventStore.class, JdbcNotificationEventStore::new);
	}

	/**
	 * Default production {@link FcmClient} built from the frozen delivery properties.
	 * The delivery configuration supplies the default FCM URL when no
	 * {@code vault.notification.fcm-base-url} override is set; the client resolves to
	 * nothing only when the delivery configuration itself is absent or carries no
	 * project id, so slim contexts and unconfigured deployments stay valid.
	 */
	@Bean
	FactoryBean<FcmClient> fcmClient(ObjectProvider<NotificationDeliveryConfiguration> configs) {
		return new FactoryBean<>() {

			@Override
			public FcmClient getObject() {
				NotificationDeliveryConfiguration config = configs.getIfAvailable();
				if (config == null || config.getProjectId() == null || config.getProjectId().isBlank()) {
					return null;
				}
				return new FcmClient(
					config.getFcmBaseUrl(),
					config.getProjectId(),
					config::getAccessToken,
					config.ttl(),
					HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
			}

			@Override
			public Class<?> getObjectType() {
				return FcmClient.class;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}

	/**
	 * Registry-aware production worker. Resolves to no bean when the {@link FcmClient},
	 * notification event store, or delivery configuration are absent; otherwise constructs
	 * the worker through the six-argument constructor with the actual meter and observation
	 * registries, so the worker owns its {@link NotificationMetrics} wrapper.
	 */
	@Bean
	FactoryBean<FcmDeliveryWorker> fcmDeliveryWorker(
		ObjectProvider<FcmClient> clients,
		ObjectProvider<NotificationEventStore> stores,
		ObjectProvider<NotificationDeliveryConfiguration> configs,
		ObjectProvider<MeterRegistry> meterRegistries,
		ObjectProvider<ObservationRegistry> observationRegistries
	) {
		return new FactoryBean<>() {

			@Override
			public FcmDeliveryWorker getObject() {
				FcmClient client = clients.getIfAvailable();
				NotificationEventStore store = stores.getIfAvailable();
				NotificationDeliveryConfiguration config = configs.getIfAvailable();
				if (client == null || store == null || config == null) {
					return null;
				}
				return new FcmDeliveryWorker(
					store,
					client,
					config,
					ThreadLocalRandom.current(),
					meterRegistries.getIfAvailable(),
					observationRegistries.getIfAvailable());
			}

			@Override
			public Class<?> getObjectType() {
				return FcmDeliveryWorker.class;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}

	/**
	 * Autonomous delivery polling seam. Uses the application clock provider so tests can
	 * pin delivery time, and stays inert when no production worker exists.
	 */
	@Bean
	FcmDeliveryScheduler fcmDeliveryScheduler(
		ObjectProvider<FcmDeliveryWorker> workers,
		ObjectProvider<Clock> clocks
	) {
		return new FcmDeliveryScheduler(workers, clocks.getIfAvailable(Clock::systemUTC));
	}

	private static <T> FactoryBean<T> factory(
			ObjectProvider<DataSource> dataSources,
			Class<T> objectType,
			java.util.function.Function<DataSource, T> constructor) {
		return new FactoryBean<>() {
			@Override
			public T getObject() {
				DataSource dataSource = dataSources.getIfAvailable();
				return dataSource == null ? null : constructor.apply(dataSource);
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

	/**
	 * Scheduled delivery runner: one bounded due-delivery attempt per tick. Business and
	 * observability failures are logged and swallowed so a single bad turn never stops
	 * the scheduler.
	 */
	static final class FcmDeliveryScheduler {

		private final ObjectProvider<FcmDeliveryWorker> workers;
		private final Clock clock;

		FcmDeliveryScheduler(ObjectProvider<FcmDeliveryWorker> workers, Clock clock) {
			this.workers = workers;
			this.clock = clock;
		}

		@Scheduled(fixedDelayString = "${vault.notification.poll-delay:PT1S}")
		void poll() {
			FcmDeliveryWorker worker = workers.getIfAvailable();
			if (worker == null) {
				return;
			}
			try {
				worker.processNext(Instant.now(clock));
			}
			catch (RuntimeException | Error failure) {
				log.warn("notification delivery tick failed", failure);
			}
		}
	}
}
