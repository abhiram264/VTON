package com.aifashionvault.generation.vton;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Fail-fast validation and typed-accessor behavior of the routing properties bean: frozen
 * defaults, invalid setter rejection, and category-objective fallback.
 */
class ProviderRouterPropertiesTest {

    @Test
    void defaultsMatchTheFrozenPolicy() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        assertThat(properties.getMode()).isEqualTo("auto");
        assertThat(properties.mode()).isEqualTo(ProviderRoutingProperties.RoutingMode.AUTO);
        assertThat(properties.getDefaultObjective()).isEqualTo("quality");
        assertThat(properties.defaultObjective()).isEqualTo(RoutingObjective.QUALITY);
        assertThat(properties.getWindowDays()).isEqualTo(7L);
        assertThat(properties.window()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.getMinSampleCount()).isEqualTo(30L);
        assertThat(properties.minSampleCount()).isEqualTo(30L);
        assertThat(properties.getMaxFailRate()).isEqualByComparingTo("0.50");
        assertThat(properties.maxFailRate()).isEqualByComparingTo("0.50");
        assertThat(properties.getCheapestTierTolerance()).isEqualByComparingTo("1.25");
        assertThat(properties.cheapestTierTolerance()).isEqualByComparingTo("1.25");
        assertThat(properties.getCurrency()).isEqualTo("USD");
        assertThat(properties.getCategoryObjectives()).isEmpty();
        assertThat(properties.getForcedProvider()).isNull();
        assertThat(properties.forcedProvider()).isEmpty();
        assertThat(properties.getPolicyVersion()).isEqualTo("routing-v1");
        assertThat(properties.policyVersion()).isEqualTo("routing-v1");
    }

    @Test
    void invalidModeRejected() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMode("maybe"));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMode(""));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMode(null));
    }

    @Test
    void invalidDefaultObjectiveRejected() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setDefaultObjective("popularity"));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setDefaultObjective(""));
    }

    @Test
    void invalidCategoryObjectiveRejected() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCategoryObjectives(Map.of("ethnic", "popularity")));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCategoryObjectives(Map.of("", "cost")));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCategoryObjectives(null));
    }

    @Test
    void blankForcedProviderMeansNoPin() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        properties.setForcedProvider(null);
        assertThat(properties.forcedProvider()).isEmpty();
        properties.setForcedProvider("   ");
        assertThat(properties.forcedProvider()).isEmpty();
        properties.setForcedProvider("fashn");
        assertThat(properties.forcedProvider()).contains(new ProviderKey("fashn"));
    }

    @Test
    void malformedForcedProviderRejected() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setForcedProvider("Not Valid"));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setForcedProvider("UPPER"));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setForcedProvider("-leading-dash"));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setForcedProvider("a".repeat(65)));
    }

    @Test
    void windowAndSampleFloorsEnforced() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setWindowDays(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMinSampleCount(0));
        properties.setWindowDays(1);
        assertThat(properties.window()).isEqualTo(Duration.ofDays(1));
        properties.setMinSampleCount(1);
        assertThat(properties.minSampleCount()).isEqualTo(1L);
    }

    @Test
    void maxFailRateBoundsEnforced() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxFailRate(new BigDecimal("1.5")));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxFailRate(new BigDecimal("-0.1")));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxFailRate(null));
        properties.setMaxFailRate(BigDecimal.ONE);
        assertThat(properties.maxFailRate()).isEqualByComparingTo("1");
    }

    @Test
    void toleranceFloorEnforced() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCheapestTierTolerance(new BigDecimal("0.9")));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCheapestTierTolerance(null));
        properties.setCheapestTierTolerance(BigDecimal.ONE);
        assertThat(properties.cheapestTierTolerance()).isEqualByComparingTo("1");
    }

    @Test
    void blankPolicyVersionRejected() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setPolicyVersion(""));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setPolicyVersion(null));
    }

    @Test
    void currencyNormalizedToUppercaseAndThreeLettersRequired() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();

        properties.setCurrency("eur");
        assertThat(properties.getCurrency()).isEqualTo("EUR");
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCurrency("EURO"));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCurrency(""));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setCurrency(null));
    }

    @Test
    void categoryObjectiveOverridesFallBackToDefault() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("Ethnic", "cost");
        overrides.put("shoes", "latency");
        properties.setCategoryObjectives(overrides);

        assertThat(properties.objectiveFor(new ItemCategory("ethnic"))).isEqualTo(RoutingObjective.COST);
        assertThat(properties.objectiveFor(new ItemCategory("shoes"))).isEqualTo(RoutingObjective.LATENCY);
        assertThat(properties.objectiveFor(new ItemCategory("tops"))).isEqualTo(RoutingObjective.QUALITY);
        properties.setDefaultObjective("COST");
        assertThat(properties.defaultObjective()).isEqualTo(RoutingObjective.COST);
        assertThat(properties.objectiveFor(new ItemCategory("tops"))).isEqualTo(RoutingObjective.COST);
    }
}
