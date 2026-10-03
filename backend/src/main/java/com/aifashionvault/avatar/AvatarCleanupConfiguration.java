package com.aifashionvault.avatar;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
public class AvatarCleanupConfiguration {

	private static final Logger log = LoggerFactory.getLogger(AvatarCleanupConfiguration.class);

	@Bean
	AvatarMediaCleanup avatarMediaCleanup(
			ObjectProvider<JdbcAvatarStore> stores,
			ObjectProvider<AvatarMediaAccess> mediaAccesses) {
		JdbcAvatarStore store = stores.getIfAvailable();
		AvatarMediaAccess mediaAccess = mediaAccesses.getIfAvailable();
		return store == null || mediaAccess == null ? null : new AvatarMediaCleanup(store, mediaAccess);
	}

	@Bean
	FactoryBean<ScheduledCleanup> scheduledCleanup(ObjectProvider<AvatarMediaCleanup> cleanups) {
		return supplied(ScheduledCleanup.class, () -> {
			AvatarMediaCleanup cleanup = cleanups.getIfAvailable();
			return cleanup == null ? null : new ScheduledCleanup(cleanup);
		});
	}

	static final class ScheduledCleanup {

		private final AvatarMediaCleanup cleanup;

		ScheduledCleanup(AvatarMediaCleanup cleanup) {
			this.cleanup = cleanup;
		}

		@Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
		void reconcile() {
			try {
				cleanup.reconcile();
			}
			catch (RuntimeException ex) {
				log.warn("avatar media cleanup failed");
			}
		}
	}
	private static <T> FactoryBean<T> supplied(Class<T> type, Supplier<T> supplier) {
		return new FactoryBean<>() {
			@Override
			public T getObject() {
				return supplier.get();
			}

			@Override
			public Class<?> getObjectType() {
				return type;
			}

			@Override
			public boolean isSingleton() {
				return true;
			}
		};
	}
}
