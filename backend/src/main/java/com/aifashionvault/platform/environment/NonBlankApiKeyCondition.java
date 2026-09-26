package com.aifashionvault.platform.environment;

import java.util.Map;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Matches only when the property named by {@link ConditionalOnNonBlankApiKey} is present
 * AND non-blank. Unlike {@code @ConditionalOnProperty} (which matches a present-but-empty
 * value), this rejects the empty string that the {@code ${ENV_VAR:}} placeholder defaults
 * in application.yaml produce when the environment variable is unset: an empty key must
 * mean no provider bean exists at all, not a provider whose client builder throws at
 * startup.
 */
class NonBlankApiKeyCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, Object> attributes =
			metadata.getAnnotationAttributes(ConditionalOnNonBlankApiKey.class.getName());
		if (attributes == null || attributes.get("value") == null) {
			return false;
		}
		return StringUtils.hasText(context.getEnvironment().getProperty((String) attributes.get("value")));
	}
}
