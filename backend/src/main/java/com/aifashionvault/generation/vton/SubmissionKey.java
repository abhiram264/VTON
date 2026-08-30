package com.aifashionvault.generation.vton;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.aifashionvault.shared.GenerationAttemptId;

public record SubmissionKey(UUID value) {
    public SubmissionKey {
        if (value == null) throw new IllegalArgumentException("submission key is required");
    }

    public static SubmissionKey forAttempt(GenerationAttemptId attemptId) {
        if (attemptId == null) throw new IllegalArgumentException("generation attempt id is required");
        return new SubmissionKey(UUID.nameUUIDFromBytes(("vton-attempt:" + attemptId.value()).getBytes(StandardCharsets.UTF_8)));
    }
}
