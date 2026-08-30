package com.aifashionvault.generation;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource.class)
public class GenerationConfiguration {

	@Bean
	JdbcGenerationStore jdbcGenerationStore(DataSource dataSource) {
		return new JdbcGenerationStore(dataSource);
	}
}
