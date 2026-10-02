package com.aifashionvault.platform.offlineecho;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.aifashionvault.platform.environment.AppEnv;

/**
 * Matches only when the offline echo backstop is explicitly enabled AND the application
 * environment is development. Staging and production never match, so the fixture cannot
 * be switched on in a real deployment even if the flag is left behind by mistake.
 */
public final class OfflineEchoProviderCondition implements Condition {

    static final String ENABLED_PROPERTY = "vault.offline.provider.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        String enabled = environment.getProperty(ENABLED_PROPERTY, "false");
        if (!"true".equalsIgnoreCase(enabled.strip())) {
            return false;
        }
        String rawEnv = environment.getProperty("vault.env");
        return rawEnv != null && AppEnv.development.name().equalsIgnoreCase(rawEnv.strip());
    }
}
