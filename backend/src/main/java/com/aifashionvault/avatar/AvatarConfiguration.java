package com.aifashionvault.avatar;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource.class)
public class AvatarConfiguration {

	@Bean
	JdbcAvatarStore jdbcAvatarStore(DataSource dataSource) {
		return new JdbcAvatarStore(dataSource);
	}

	@Bean
	SemanticInspector semanticInspector() {
		return new OnnxCpuSemanticInspector();
	}

	@Bean
	AvatarPreflight avatarPreflight(AvatarBytesInspector inspector, SemanticInspector semanticInspector) {
		return new AvatarPreflight(inspector, semanticInspector);
	}

	@Bean
	AvatarWorkflow avatarWorkflow(JdbcAvatarStore store, AvatarMediaAccess mediaAccess, AvatarPreflight preflight) {
		return new AvatarWorkflow(store, mediaAccess, preflight);
	}

	@Bean
	AvatarAccountMediaPurge avatarAccountMediaPurge(JdbcAvatarStore store, AvatarMediaAccess mediaAccess) {
		return new AvatarAccountMediaPurge(store, mediaAccess);
	}
}
