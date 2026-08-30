package com.aifashionvault.avatar;

import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource.class)
public class AvatarCleanupConfiguration {

	private static final System.Logger LOG = System.getLogger(AvatarCleanupConfiguration.class.getName());

	@Bean
	AvatarMediaCleanup avatarMediaCleanup(JdbcAvatarStore store, AvatarMediaAccess mediaAccess) {
		return new AvatarMediaCleanup(store, mediaAccess);
	}

	@Bean
	ScheduledCleanup scheduledCleanup(AvatarMediaCleanup cleanup) {
		return new ScheduledCleanup(cleanup);
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
				LOG.log(System.Logger.Level.WARNING, "avatar media cleanup failed");
			}
		}
	}
}
