package com.aifashionvault.notification;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource.class)
public class NotificationConfiguration {

	@Bean
	JdbcNotificationStore jdbcNotificationStore(DataSource dataSource) {
		return new JdbcNotificationStore(dataSource);
	}

	@Bean
	JdbcNotificationEventStore jdbcNotificationEventStore(DataSource dataSource) {
		return new JdbcNotificationEventStore(dataSource);
	}
}
