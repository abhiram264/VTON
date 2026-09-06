package com.aifashionvault.admission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.aifashionvault.generation.CostAggregate;
import com.aifashionvault.generation.CostQuery;
import com.aifashionvault.generation.GenerationCostMetrics;

public final class AggregateBackedCostSpikeDetector implements CostSpikeDetector {
	private static final List<SoftBreachDimension> PRECEDENCE = List.of(SoftBreachDimension.GLOBAL,
		SoftBreachDimension.PROVIDER);

	private final GenerationCostMetrics costMetrics;
	private final CostSpikePolicy policy;

	public AggregateBackedCostSpikeDetector(GenerationCostMetrics costMetrics, CostSpikePolicy policy) {
		this.costMetrics = Objects.requireNonNull(costMetrics, "costMetrics");
		this.policy = Objects.requireNonNull(policy, "policy");
	}

	@Override
	public CostSpikeAssessment assess(CostSpikeQuery query) {
		Objects.requireNonNull(query, "query");
		var end = query.now();
		var start = end.minus(policy.observationWindow());
		var baselineStart = start.minus(policy.observationWindow().multipliedBy(policy.baselineWindowCount()));
		CostAggregate globalBaseline = null;
		CostAggregate globalCurrent = null;
		for (var dimension : PRECEDENCE) {
			if (!policy.spikeDimensions().contains(dimension)) {
				continue;
			}
			var providerKey = dimension == SoftBreachDimension.PROVIDER ? query.providerKey() : null;
			var baseline = aggregate(providerKey, query.currency(), baselineStart, start);
			var current = aggregate(providerKey, query.currency(), start, end);
			if (dimension == SoftBreachDimension.GLOBAL) {
				globalBaseline = baseline;
				globalCurrent = current;
			}
			if (suspected(baseline, current, query.currency())) {
				return new CostSpikeAssessment(true, dimension, current.totalCost(), mean(baseline.totalCost()),
					current.requestCount(), start, end);
			}
		}
		if (known(globalCurrent, query.currency())) {
			return new CostSpikeAssessment(false, null, globalCurrent.totalCost(),
				mean(known(globalBaseline, query.currency()) ? globalBaseline.totalCost() : null),
				globalCurrent.requestCount(), start, end);
		}
		return new CostSpikeAssessment(false, null, BigDecimal.ZERO, BigDecimal.ZERO, 0, start, end);
	}

	private CostAggregate aggregate(String providerKey, String currency, Instant start, Instant end) {
		return costMetrics.aggregate(new CostQuery(null, providerKey, null, currency, start, end));
	}

	private boolean suspected(CostAggregate baseline, CostAggregate current, String currency) {
		if (!known(baseline, currency) || !known(current, currency)) {
			return false;
		}
		if (baseline.totalCost().signum() == 0) {
			return false;
		}
		if (current.requestCount() < policy.minimumWindowExecutions()) {
			return false;
		}
		return current.totalCost().multiply(BigDecimal.valueOf(policy.baselineWindowCount()))
			.compareTo(baseline.totalCost().multiply(policy.spikeFactor())) > 0;
	}

	private boolean known(CostAggregate aggregate, String currency) {
		return aggregate != null && aggregate.totalCost() != null && aggregate.totalCost().signum() >= 0
			&& currency.equals(aggregate.currency());
	}

	private BigDecimal mean(BigDecimal baselineTotal) {
		if (baselineTotal == null) {
			return BigDecimal.ZERO;
		}
		return baselineTotal.divide(BigDecimal.valueOf(policy.baselineWindowCount()), 6, RoundingMode.HALF_UP);
	}
}
