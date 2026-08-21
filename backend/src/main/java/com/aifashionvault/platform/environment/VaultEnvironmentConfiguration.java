package com.aifashionvault.platform.environment;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VaultEnvironmentProperties.class)
public class VaultEnvironmentConfiguration {

	VaultEnvironmentConfiguration(VaultEnvironmentProperties properties, Environment environment) {
		VaultEnvironmentValidator.validate(properties, environment);
	}

	@Bean
	@ConfigurationPropertiesBinding
	static Converter<String, AppEnv> appEnvConverter() {
		return AppEnv::parse;
	}
}
