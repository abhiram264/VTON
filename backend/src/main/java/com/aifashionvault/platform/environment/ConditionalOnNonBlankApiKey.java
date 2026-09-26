package com.aifashionvault.platform.environment;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Gates a bean on a provider API key that is present AND genuinely non-blank. The
 * property is read from the {@link org.springframework.core.env.Environment}, so a
 * resolved {@code ${ENV_VAR:}} default (empty when the variable is unset) correctly
 * means the bean is NOT created.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(NonBlankApiKeyCondition.class)
public @interface ConditionalOnNonBlankApiKey {

	/** Fully-qualified property name, e.g. {@code vault.fashn.api-key}. */
	String value();
}
