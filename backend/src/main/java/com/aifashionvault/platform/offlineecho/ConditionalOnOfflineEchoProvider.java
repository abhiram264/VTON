package com.aifashionvault.platform.offlineecho;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Gates the offline echo backstop bean on BOTH an explicit opt-in
 * ({@code vault.offline.provider.enabled=true}) and a development environment
 * ({@code vault.env=development}). The two-part gate is the fail-closed posture: a
 * forgotten flag in staging or production still cannot register the echo provider, and
 * with no real provider key the deployment keeps the honest-unavailable posture.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OfflineEchoProviderCondition.class)
public @interface ConditionalOnOfflineEchoProvider {
}
