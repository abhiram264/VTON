package com.aifashionvault.generation.vton;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRouterContractsTest {

    private static final ProviderKey KEY = new ProviderKey("provider-a");

    @Test
    void routingRequestComponentShapeMatchesFrozenSeam() {
        assertThat(RoutingRequest.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("mode", "categories", "objective");
        assertThat(RoutingRequest.class.getRecordComponents())
            .extracting(RecordComponent::getType)
            .containsExactly(VtonMode.class, List.class, RoutingObjective.class);
    }

    @Test
    void providerRouteComponentShapeMatchesFrozenSeam() {
        assertThat(ProviderRoute.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly(
                "providerKey", "modelOrMode", "routingPolicyVersion", "objective", "outcome",
                "candidateCount", "axisScores");
        assertThat(ProviderRoute.class.getRecordComponents())
            .extracting(RecordComponent::getType)
            .containsExactly(
                ProviderKey.class, String.class, String.class, RoutingObjective.class,
                RouteOutcome.class, int.class, Map.class);
    }

    @Test
    void enumsExposeExactlyTheFrozenConstantsInOrder() {
        assertThat(RoutingObjective.values()).containsExactly(
            RoutingObjective.QUALITY, RoutingObjective.COST, RoutingObjective.LATENCY);
        assertThat(RouteOutcome.values()).containsExactly(
            RouteOutcome.SELECTED,
            RouteOutcome.PASSTHROUGH_SINGLE_PROVIDER,
            RouteOutcome.PASSTHROUGH_DISABLED,
            RouteOutcome.DEFAULTED_FORCED,
            RouteOutcome.FORCED_IGNORED_UNREGISTERED,
            RouteOutcome.DEFAULTED_INSUFFICIENT_EVIDENCE,
            RouteOutcome.DEFAULTED_NO_ELIGIBLE,
            RouteOutcome.DEFAULTED_EVIDENCE_UNAVAILABLE);
    }

    @Test
    void routeIsTheSinglePublicAbstractDecisionSeam() {
        assertThat(ProviderRouter.class.getDeclaredMethods()).hasSize(1);
        Method route = ProviderRouter.class.getDeclaredMethods()[0];
        assertThat(route.getName()).isEqualTo("route");
        assertThat(Modifier.isPublic(route.getModifiers())).isTrue();
        assertThat(Modifier.isAbstract(route.getModifiers())).isTrue();
        assertThat(route.getParameterTypes()).containsExactly(RoutingRequest.class);
        assertThat(route.getReturnType()).isEqualTo(ProviderRoute.class);
    }

    @Test
    void collectionsAreDefensivelyCopiedAndUnmodifiable() {
        List<ItemCategory> categories = new ArrayList<>();
        categories.add(new ItemCategory("tops"));
        RoutingRequest request = new RoutingRequest(null, categories, RoutingObjective.QUALITY);
        categories.add(new ItemCategory("shoes"));
        assertThat(request.categories()).containsExactly(new ItemCategory("tops"));
        assertThatThrownBy(() -> request.categories().add(new ItemCategory("shoes")))
            .isInstanceOf(UnsupportedOperationException.class);

        Map<String, BigDecimal> scores = new HashMap<>();
        scores.put("quality", new BigDecimal("0.9"));
        ProviderRoute route = new ProviderRoute(
            KEY, "model-x", "routing-1", RoutingObjective.QUALITY, RouteOutcome.SELECTED, 2, scores);
        scores.put("cost", BigDecimal.ONE);
        assertThat(route.axisScores()).containsExactly(Map.entry("quality", new BigDecimal("0.9")));
        assertThatThrownBy(() -> route.axisScores().put("latency", BigDecimal.ZERO))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiredFieldsFailClosed() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new RoutingRequest(null, null, RoutingObjective.QUALITY))
            .withMessageContaining("categories is required");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new RoutingRequest(null, List.of(), null))
            .withMessageContaining("objective is required");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProviderRoute(null, "model-x", "routing-1", RoutingObjective.QUALITY, RouteOutcome.SELECTED, 1, Map.of()))
            .withMessageContaining("providerKey");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProviderRoute(KEY, "model-x", null, RoutingObjective.QUALITY, RouteOutcome.SELECTED, 1, Map.of()))
            .withMessageContaining("routingPolicyVersion");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProviderRoute(KEY, "model-x", "   ", RoutingObjective.QUALITY, RouteOutcome.SELECTED, 1, Map.of()))
            .withMessageContaining("routingPolicyVersion");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProviderRoute(KEY, "model-x", "routing-1", null, RouteOutcome.SELECTED, 1, Map.of()))
            .withMessageContaining("objective");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProviderRoute(KEY, "model-x", "routing-1", RoutingObjective.QUALITY, null, 1, Map.of()))
            .withMessageContaining("outcome");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProviderRoute(KEY, "model-x", "routing-1", RoutingObjective.QUALITY, RouteOutcome.SELECTED, -1, Map.of()))
            .withMessageContaining("candidateCount");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProviderRoute(KEY, "model-x", "routing-1", RoutingObjective.QUALITY, RouteOutcome.SELECTED, 1, null))
            .withMessageContaining("axisScores is required");
    }

    @Test
    void providerRouteCarriesNoMediaOrIdentityTypes() {
        // ADR-015: routing audits carry only provider keys, model identifiers, enums, versions,
        // counts, and numbers — no component of any routing record carries media, URLs, user
        // identifiers, or credentials.
        for (RecordComponent component : ProviderRoute.class.getRecordComponents()) {
            Class<?> type = component.getType();
            assertThat(type.getSimpleName())
                .doesNotContain("Media", "Url", "Uri", "User", "Credential", "Secret", "Token", "Ref");
            assertThat(type.isPrimitive()
                || type == String.class
                || type == ProviderKey.class
                || type == RoutingObjective.class
                || type == RouteOutcome.class
                || type == Map.class)
                .as("ProviderRoute.%s must be provider-key/enum/version/count-shaped, was %s",
                    component.getName(), type)
                .isTrue();
        }
    }

    @Test
    void routingRequestAllowsGlobalContextAndRejectsNullElements() {
        RoutingRequest global = new RoutingRequest(null, List.of(), RoutingObjective.COST);
        assertThat(global.mode()).isNull();
        assertThat(global.categories()).isEmpty();

        assertThatNullPointerException()
            .isThrownBy(() -> new RoutingRequest(null, Arrays.asList(new ItemCategory("tops"), null), RoutingObjective.QUALITY));
    }

    @Test
    void weightVectorsArePinnedToTheRoutingPolicyVersion() throws Exception {
        // Spec test decision 21: changing routing weights or thresholds without bumping
        // routingPolicyVersion must fail a contract test, preserving the §52 benchmark
        // regression trigger. ProviderRouterPropertiesTest.defaultsMatchTheFrozenPolicy pins
        // the thresholds; this test pins the engine weight vectors jointly with the default
        // policy version, so any weight edit forces a coordinated test and version edit.
        assertThat(new ProviderRoutingProperties().policyVersion()).isEqualTo("routing-v1");

        String[] weightFields = { "QUALITY_WEIGHTS", "COST_WEIGHTS", "LATENCY_WEIGHTS" };
        BigDecimal[][] frozenVectors = {
            { new BigDecimal("0.6"), new BigDecimal("0.2"), new BigDecimal("0.2") },
            { new BigDecimal("0.2"), new BigDecimal("0.6"), new BigDecimal("0.2") },
            { new BigDecimal("0.2"), new BigDecimal("0.2"), new BigDecimal("0.6") } };
        for (int axis = 0; axis < weightFields.length; axis++) {
            Field field = CategoryAwareProviderRouter.class.getDeclaredField(weightFields[axis]);
            field.setAccessible(true);
            BigDecimal[] weights = (BigDecimal[]) field.get(null);
            assertThat(weights)
                .as("frozen %s in quality/cost/latency axis order", weightFields[axis])
                .hasSize(3);
            for (int i = 0; i < weights.length; i++) {
                assertThat(weights[i].compareTo(frozenVectors[axis][i]))
                    .as("%s[%d] must stay %s until routingPolicyVersion bumps",
                        weightFields[axis], i, frozenVectors[axis][i])
                    .isZero();
            }
        }
    }
}
