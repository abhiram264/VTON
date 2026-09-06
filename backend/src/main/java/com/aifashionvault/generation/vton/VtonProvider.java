package com.aifashionvault.generation.vton;

import com.aifashionvault.generation.ProviderTurnOutcome;

public interface VtonProvider {
    ProviderKey providerKey();
    ProviderCapabilities capabilities();
    GenerationSubmission submit(VtonRequest request, SubmissionKey submissionKey);

    default ProviderTurnOutcome submitTurn(VtonRequest request, SubmissionKey submissionKey) {
        return switch (submit(request, submissionKey)) {
            case GenerationSubmission.Accepted accepted -> new ProviderTurnOutcome.Accepted(accepted.providerJobId(), accepted.usage());
            case GenerationSubmission.Rejected rejected -> new ProviderTurnOutcome.Rejected(rejected.failure());
            case GenerationSubmission.Indeterminate indeterminate -> new ProviderTurnOutcome.Indeterminate(indeterminate.failure());
        };
    }

    StatusQueryResult getStatus(ProviderJobId providerJobId);
    ProviderMediaSource openOutput(ProviderOutputRef outputRef);
}
