package com.aifashionvault.studio.agent;

import com.aifashionvault.shared.GenerationJobId;

/** An accepted generation job. */
public record GenerationValue(GenerationJobId generationJobId, String status) implements ToolValue {
}
