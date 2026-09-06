package com.aifashionvault.admission;

public interface CostSpikeDetector {
	CostSpikeAssessment assess(CostSpikeQuery query);
}
