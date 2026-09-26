package com.aifashionvault.platform.inferhub;

import tools.jackson.databind.JsonNode;

/**
 * One synchronous InferHub chat-completions response, normalized to the same
 * outcome taxonomy as the FASHN adapter: HTTP (status known), UNSENT (request
 * never left), INDETERMINATE (request may or may not have been processed).
 */
record InferHubHttpResponse(Outcome outcome, int statusCode, JsonNode body, boolean bodyOversized) {
    enum Outcome {
        HTTP,
        UNSENT,
        INDETERMINATE
    }

    InferHubHttpResponse {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
    }

    static InferHubHttpResponse http(int statusCode, JsonNode body, boolean bodyOversized) {
        return new InferHubHttpResponse(Outcome.HTTP, statusCode, body, bodyOversized);
    }

    static InferHubHttpResponse unsent() {
        return new InferHubHttpResponse(Outcome.UNSENT, 0, null, false);
    }

    static InferHubHttpResponse indeterminate() {
        return new InferHubHttpResponse(Outcome.INDETERMINATE, 0, null, false);
    }
}
