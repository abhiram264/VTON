package com.aifashionvault.look;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource.class)
public class LookConfiguration {

	@Bean
	JdbcLookStore jdbcLookStore(DataSource dataSource) {
		return new JdbcLookStore(dataSource);
	}
}
