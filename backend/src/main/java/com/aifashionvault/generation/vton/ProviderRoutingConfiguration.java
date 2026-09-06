package com.aifashionvault.generation.vton;

import java.time.Clock;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.generation.ProviderHealthGate;

/**
 * Routing wiring (slice-74 U3): wraps every {@link ProviderRegistry} bean in place so the frozen
 * platform selection seam gets routed selection, degrading byte-for-byte to today's
 * single-provider behavior with only one provider registered in AUTO mode.
 *
 * <p><b>Deviation from spec decision 16 (orchestrator-approved).</b> The spec proposed a
 * {@code @Primary} decorator bean injected via {@code @Qualifier("providerRegistry")}. That
 * breaks two frozen tests that may not be edited:
 * {@code platform/web/GenerationHttpContractTest} defines its own {@code @Primary}
 * {@code ProviderRegistry} (two {@code @Primary} beans of one type would raise
 * {@code NoUniqueBeanDefinitionException}), and {@code platform/fashn/FashnConfigTest} asserts
 * {@code hasSingleBean(ProviderRegistry.class)}. Instead, the static {@link BeanPostProcessor}
 * below wraps every {@code ProviderRegistry} instance in place in
 * {@code postProcessAfterInitialization} (beans already {@link RoutingProviderRegistry} are
 * skipped): one bean definition per registry, zero primary/qualifier conflicts, decorator
 * transparent to all existing consumers.
 *
 * <p><b>Fail-open contract.</b> The {@link CategoryAwareProviderRouter} is constructed over the
 * inner registry — never the decorator — so the decorator's catch-and-fail-open can never
 * recurse through the engine's own defaults. Any {@link RuntimeException} from engine or
 * evidence store is caught by {@link RoutingProviderRegistry}, which degrades to the base
 * registry's bootstrap provider: never null, never throws.
 *
 * <p><b>Lazy resolution.</b> All {@link ObjectProvider} lookups happen inside
 * {@code postProcessAfterInitialization}, never at post-processor construction: an absent
 * {@link ProviderPerformanceStore} behaves as empty evidence, an absent {@link ProviderHealthGate}
 * allows every provider, an absent {@link Clock} falls back to {@link Clock#systemUTC()}, and no
 * new required bean is introduced. The {@link ProviderRoutingMetrics} no-op default is the
 * §53/§68 observability seam: register a real adapter bean and the
 * {@code @ConditionalOnMissingBean} default steps aside (same for
 * {@link ProviderRoutingConstraints}, the slice-75 admission seam).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderRoutingProperties.class)
public class ProviderRoutingConfiguration {

    @Bean
    @ConditionalOnMissingBean(ProviderRoutingConstraints.class)
    ProviderRoutingConstraints providerRoutingConstraints() {
        return new NoopProviderRoutingConstraints();
    }

    @Bean
    @ConditionalOnMissingBean(ProviderRoutingMetrics.class)
    ProviderRoutingMetrics providerRoutingMetrics() {
        return new NoopProviderRoutingMetrics();
    }

    @Bean
    static BeanPostProcessor routingProviderRegistryPostProcessor(
        ObjectProvider<ProviderRoutingProperties> properties,
        ObjectProvider<ProviderPerformanceStore> stores,
        ObjectProvider<ProviderHealthGate> healthGates,
        ObjectProvider<ProviderRoutingConstraints> constraints,
        ObjectProvider<ProviderRoutingMetrics> metrics,
        ObjectProvider<Clock> clocks) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof ProviderRegistry registry && !(bean instanceof RoutingProviderRegistry)) {
                    return new RoutingProviderRegistry(registry,
                        new CategoryAwareProviderRouter(registry,
                            stores.getIfAvailable(),
                            healthGates.getIfAvailable(),
                            constraints.getIfAvailable(NoopProviderRoutingConstraints::new),
                            properties.getObject(),
                            clocks.getIfAvailable(Clock::systemUTC)),
                        metrics.getIfAvailable(NoopProviderRoutingMetrics::new),
                        properties.getObject());
                }
                return bean;
            }
        };
    }
}
