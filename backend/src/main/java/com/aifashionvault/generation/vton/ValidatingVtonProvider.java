package com.aifashionvault.generation.vton;

import java.util.Optional;

public abstract class ValidatingVtonProvider implements VtonProvider {
    @Override
    public final GenerationSubmission submit(VtonRequest request, SubmissionKey submissionKey) {
        if (submissionKey == null) throw new IllegalArgumentException("submission key is required");
        Optional<ProviderFailure> failure = VtonRequestValidator.validate(request, capabilities());
        return failure.<GenerationSubmission>map(GenerationSubmission.Rejected::new)
            .orElseGet(() -> submitValid(request, submissionKey));
    }

    protected abstract GenerationSubmission submitValid(VtonRequest request, SubmissionKey submissionKey);
}
