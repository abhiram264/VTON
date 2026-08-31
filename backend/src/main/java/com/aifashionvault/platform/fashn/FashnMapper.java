package com.aifashionvault.platform.fashn;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.InputSizeLimit;
import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.ModeCapabilities;
import com.aifashionvault.generation.vton.OutputDeliveryMethod;
import com.aifashionvault.generation.vton.PrivacyTransferPolicy;
import com.aifashionvault.generation.vton.ProviderCapabilities;
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.ProviderUsage;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.platform.objectstore.ImageMediaPolicy;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;

import tools.jackson.databind.JsonNode;

public final class FashnMapper {
    static final ProviderKey PROVIDER_KEY = new ProviderKey("fashn");
    static final VtonMode FAST_PREVIEW = new VtonMode("fast_preview");
    static final VtonMode STANDARD = new VtonMode("standard");
    static final VtonMode HD = new VtonMode("hd");
    static final long MAX_INLINE_BYTES = 4L * 1024 * 1024;
    static final long MAX_ASSET_BYTES = 8L * 1024 * 1024;
    static final long MAX_OUTPUT_BYTES = 4L * 1024 * 1024;
    static final Duration SIGNED_READ_TTL = Duration.ofMinutes(5);
    static final String CREDITS_HEADER = "x-fashn-credits-used";
    static final String OUTPUT_INDEX = "0";

    /**
     * Q48: adapter error-mapping contract version stamped on every
     * normalized {@link ProviderFailure} this adapter produces so failures
     * are never silently reclassified by later mapper revisions.
     */
    public static final String ERROR_MAPPING_CONTRACT_VERSION = "fashn-mapping-v1";

    /**
     * Documented FASHN error names that are themselves transient; a
     * nested {@code cause} carrying one of these counts as transport-
     * level transient evidence for ThirdPartyError (Q49).
     */
    private static final Set<String> TRANSIENT_ERROR_NAMES = Set.of(
        "TimeoutError",
        "UnavailableError",
        "PipelineError");

    private static final Set<ItemCategory> PREVIEW_CATEGORIES = Set.of(
        new ItemCategory("clothing"),
        new ItemCategory("tops"),
        new ItemCategory("bottoms"),
        new ItemCategory("one-pieces"));

    private static final Set<ItemCategory> MAX_CATEGORIES = Set.of(
        new ItemCategory("clothing"),
        new ItemCategory("tops"),
        new ItemCategory("bottoms"),
        new ItemCategory("one-pieces"),
        new ItemCategory("footwear"),
        new ItemCategory("headwear"),
        new ItemCategory("jewellery"),
        new ItemCategory("jewelry"),
        new ItemCategory("bag"),
        new ItemCategory("accessory"));

    ProviderKey providerKey() {
        return PROVIDER_KEY;
    }

    ProviderCapabilities capabilities() {
        ModeCapabilities preview = new ModeCapabilities(PREVIEW_CATEGORIES, 1, false, new InputSizeLimit.Known(MAX_ASSET_BYTES));
        ModeCapabilities max = new ModeCapabilities(MAX_CATEGORIES, 1, true, new InputSizeLimit.Known(MAX_ASSET_BYTES));
        return new ProviderCapabilities(
            Map.of(FAST_PREVIEW, preview, STANDARD, max, HD, max),
            Set.of(InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ),
            Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
            SubmissionReplaySafety.UNSAFE);
    }

    boolean callbackRecovery() {
        return false;
    }

    PrivacyTransferPolicy privacyPolicy() {
        return new PrivacyTransferPolicy(
            Set.of(InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ),
            Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
            MAX_INLINE_BYTES,
            SIGNED_READ_TTL,
            true);
    }

    Map<String, Object> runRequest(
        VtonMode mode,
        String modelImage,
        String productImage,
        ItemCategory category,
        Optional<String> instructions
    ) {
        if (FAST_PREVIEW.equals(mode)) {
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("model_image", modelImage);
            inputs.put("garment_image", productImage);
            inputs.put("category", previewCategory(category));
            inputs.put("mode", "performance");
            inputs.put("num_samples", 1);
            inputs.put("return_base64", true);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("model_name", "tryon-v1.6");
            root.put("inputs", inputs);
            return root;
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("model_image", modelImage);
        inputs.put("product_image", productImage);
        if (HD.equals(mode)) {
            inputs.put("generation_mode", "balanced");
            inputs.put("resolution", "2k");
        } else {
            inputs.put("generation_mode", "fast");
            inputs.put("resolution", "1k");
        }
        inputs.put("num_images", 1);
        inputs.put("return_base64", true);
        instructions.ifPresent(value -> inputs.put("prompt", value));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model_name", "tryon-max");
        root.put("inputs", inputs);
        return root;
    }

    GenerationSubmission mapRun(FashnHttpResponse response) {
        if (response.outcome() == FashnHttpResponse.Outcome.UNSENT) {
            return new GenerationSubmission.Rejected(failure(ProviderFailureCategory.TIMEOUT_NETWORK, true, "CONNECTION_FAILED"));
        }
        if (response.outcome() == FashnHttpResponse.Outcome.INDETERMINATE
            || (response.statusCode() == 200 && response.bodyOversized())) {
            return new GenerationSubmission.Indeterminate(failure(ProviderFailureCategory.TIMEOUT_NETWORK, true, "INDETERMINATE_IO"));
        }
        if (response.statusCode() == 200) {
            String id = text(response.body(), "id");
            if (id == null || id.isBlank()) {
                return new GenerationSubmission.Indeterminate(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, true, "MISSING_JOB_ID"));
            }
            try {
                return new GenerationSubmission.Accepted(new ProviderJobId(id), usage(response.creditsUsed()));
            } catch (IllegalArgumentException ex) {
                return new GenerationSubmission.Indeterminate(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, true, "INVALID_JOB_ID"));
            }
        }
        return new GenerationSubmission.Rejected(mapApiError(
            response.statusCode(), response.body(), response.retryAfterAt(), response.dateHeaderAt()));
    }

    StatusQueryResult mapStatus(String predictionId, FashnHttpResponse response) {
        if (response.outcome() != FashnHttpResponse.Outcome.HTTP) {
            return new StatusQueryResult.Unavailable(failure(ProviderFailureCategory.TIMEOUT_NETWORK, true,
                response.outcome() == FashnHttpResponse.Outcome.UNSENT ? "CONNECTION_FAILED" : "INDETERMINATE_IO"));
        }
        if (response.statusCode() != 200) {
            return new StatusQueryResult.Unavailable(mapApiError(
                response.statusCode(), response.body(), response.retryAfterAt(), response.dateHeaderAt()));
        }
        if (response.bodyOversized()) {
            return new StatusQueryResult.Observed(new GenerationStatus.Failed(
                failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_TOO_LARGE"),
                usage(response.creditsUsed())));
        }
        String status = text(response.body(), "status");
        if (status == null || status.isBlank()) {
            return new StatusQueryResult.Unavailable(failure(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, false, "MISSING_STATUS"));
        }
        String normalized = status.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "starting", "in_queue" -> new StatusQueryResult.Observed(new GenerationStatus.Queued());
            case "processing" -> new StatusQueryResult.Observed(new GenerationStatus.Running());
            case "completed" -> new StatusQueryResult.Observed(mapCompleted(predictionId, response));
            case "failed" -> new StatusQueryResult.Observed(new GenerationStatus.Failed(
                mapRuntimeError(response.body() == null ? null : response.body().get("error"), response.statusCode()),
                usage(response.creditsUsed())));
            default -> new StatusQueryResult.Unavailable(failure(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, false, "UNKNOWN_STATUS"));
        };
    }

    DecodedImage openOutputImage(String predictionId, FashnHttpResponse response) {
        if (response.outcome() != FashnHttpResponse.Outcome.HTTP) {
            throw new FashnProviderException(failure(ProviderFailureCategory.TIMEOUT_NETWORK, true,
                response.outcome() == FashnHttpResponse.Outcome.UNSENT ? "CONNECTION_FAILED" : "INDETERMINATE_IO"));
        }
        if (response.statusCode() != 200) {
            throw new FashnProviderException(mapApiError(
                response.statusCode(), response.body(), response.retryAfterAt(), response.dateHeaderAt()));
        }
        if (response.bodyOversized()) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_TOO_LARGE"));
        }
        String status = text(response.body(), "status");
        if (status == null || !"completed".equalsIgnoreCase(status)) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_NOT_READY"));
        }
        return decodeRequiredOutput(singleOutput(response.body()));
    }

    ProviderOutputRef outputRef(String predictionId) {
        return new ProviderOutputRef(predictionId + ":" + OUTPUT_INDEX);
    }

    String predictionId(ProviderJobId jobId) {
        return jobId.value();
    }

    String predictionId(ProviderOutputRef outputRef) {
        String value = outputRef.value();
        int split = value.lastIndexOf(':');
        if (split <= 0 || split == value.length() - 1 || !OUTPUT_INDEX.equals(value.substring(split + 1))) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_REF"));
        }
        return value.substring(0, split);
    }

    Optional<ProviderUsage> usage(String creditsHeader) {
        return Optional.empty();
    }

    Optional<ObjectRef> objectRef(StorageKey storageKey) {
        String value = storageKey.value();
        if (value.startsWith("temp/")) {
            return Optional.of(new ObjectRef(StorageDomain.TEMP, value));
        }
        if (value.contains("/avatars/")) {
            return Optional.of(new ObjectRef(StorageDomain.AVATAR, value));
        }
        if (value.contains("/vault/")) {
            return Optional.of(new ObjectRef(StorageDomain.VAULT, value));
        }
        if (value.contains("/generation-inputs/")) {
            return Optional.of(new ObjectRef(StorageDomain.TEMP, value));
        }
        if (value.contains("/looks/")) {
            return Optional.of(new ObjectRef(StorageDomain.LOOK, value));
        }
        return Optional.empty();
    }

    String normalizeImageMediaType(String contentType) {
        try {
            return ImageMediaPolicy.requireDeclaration(contentType, 1);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    String inlineDataUri(String mediaType, byte[] bytes) {
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    ProviderFailure mapApiError(int status, JsonNode body) {
        return mapApiError(status, body, null, null);
    }

    private ProviderFailure mapApiError(int status, JsonNode body, Instant retryAfterAt, Instant dateHeaderAt) {
        String name = apiErrorName(body);
        if (status == 401 || "UnauthorizedAccess".equals(name)) {
            return failure(ProviderFailureCategory.AUTH_CONFIGURATION, false, "AUTH_CONFIGURATION");
        }
        if (status == 429 || "RateLimitExceeded".equals(name) || "ConcurrencyLimitExceeded".equals(name) || "OutOfCredits".equals(name)) {
            if ("OutOfCredits".equals(name)) {
                return failure(ProviderFailureCategory.QUOTA_BILLING, false, "QUOTA_BILLING");
            }
            if ("ConcurrencyLimitExceeded".equals(name)) {
                return failure(ProviderFailureCategory.RATE_CAPACITY, true, "CONCURRENCY_LIMIT", retryAfterAt);
            }
            return failure(ProviderFailureCategory.RATE_CAPACITY, true, "RATE_LIMIT", retryAfterAt);
        }
        if (status == 500 || "InternalServerError".equals(name)) {
            return failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "PROVIDER_INTERNAL");
        }
        if ("ThirdPartyError".equals(name)) {
            return thirdPartyFailure(status);
        }
        if (status == 404 || "NotFound".equals(name)) {
            return failure(ProviderFailureCategory.REMOTE_EXECUTION_NOT_FOUND, false, "NOT_FOUND");
        }
        if (status == 400 || "BadRequest".equals(name)) {
            return failure(ProviderFailureCategory.INVALID_REQUEST, false, "INVALID_REQUEST");
        }
        return failure(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, false, "UNKNOWN_PROVIDER_FAILURE");
    }

    ProviderFailure mapRuntimeError(JsonNode error) {
        return mapRuntimeError(error, 200);
    }

    private ProviderFailure mapRuntimeError(JsonNode error, int status) {
        String name = errorName(error);
        return switch (name) {
            case "ImageLoadError" -> failure(ProviderFailureCategory.INVALID_REQUEST, false, "IMAGE_LOAD");
            case "InputValidationError" -> failure(ProviderFailureCategory.INVALID_REQUEST, false, "INPUT_VALIDATION");
            case "PoseError" -> failure(ProviderFailureCategory.INVALID_REQUEST, false, "POSE");
            case "ContentModerationError" -> failure(ProviderFailureCategory.CONTENT_POLICY_REJECTION, false, "CONTENT_POLICY");
            case "UnavailableError" -> failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "PROVIDER_UNAVAILABLE");
            case "ThirdPartyError" -> thirdPartyFailure(status, error);
            case "PipelineError" -> failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "PIPELINE");
            case "TimeoutError" -> failure(ProviderFailureCategory.TIMEOUT_NETWORK, true, "PROVIDER_TIMEOUT");
            default -> failure(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, false, "UNKNOWN_PROVIDER_FAILURE");
        };
    }

    static ProviderFailure failure(ProviderFailureCategory category, boolean retryable, String code) {
        return new ProviderFailure(category, retryable, code, null, ERROR_MAPPING_CONTRACT_VERSION);
    }

    private static ProviderFailure failure(
        ProviderFailureCategory category,
        boolean retryable,
        String code,
        Instant retryAfterAt
    ) {
        return new ProviderFailure(category, retryable, code, retryable ? retryAfterAt : null, ERROR_MAPPING_CONTRACT_VERSION);
    }

    /**
     * Q49: ThirdPartyError is transient only with concrete transient
     * evidence - a 5xx delivery context or a nested transient error cause.
     * Without evidence it fails closed to an unknown, non-retryable
     * failure so spend decisions never bet on undocumented transience.
     */
    private static ProviderFailure thirdPartyFailure(int status, JsonNode error) {
        if (hasTransientEvidence(status, error)) {
            return failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "THIRD_PARTY");
        }
        return failure(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, false, "UNKNOWN_PROVIDER_FAILURE");
    }

    private static ProviderFailure thirdPartyFailure(int status) {
        return thirdPartyFailure(status, null);
    }

    private static boolean hasTransientEvidence(int status, JsonNode error) {
        if (status >= 500 && status <= 599) {
            return true;
        }
        if (error == null || !error.isObject()) {
            return false;
        }
        JsonNode cause = error.get("cause");
        if (cause == null) {
            return false;
        }
        return TRANSIENT_ERROR_NAMES.contains(errorName(cause));
    }

    private GenerationStatus mapCompleted(String predictionId, FashnHttpResponse response) {
        OutputInspection inspection = inspectOutputs(response.body());
        return switch (inspection.kind()) {
            case MISSING -> new GenerationStatus.Failed(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MISSING"), usage(response.creditsUsed()));
            case COUNT -> new GenerationStatus.Failed(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_COUNT"), usage(response.creditsUsed()));
            case EXPIRED -> new GenerationStatus.Failed(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_EXPIRED"), usage(response.creditsUsed()));
            case URL -> new GenerationStatus.Failed(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_URL"), usage(response.creditsUsed()));
            case IMAGE -> new GenerationStatus.Succeeded(List.of(outputRef(predictionId)), usage(response.creditsUsed()));
            case MALFORMED -> new GenerationStatus.Failed(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"), usage(response.creditsUsed()));
        };
    }

    private DecodedImage decodeRequiredOutput(String value) {
        if (value == null) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MISSING"));
        }
        if ("_expired".equals(value)) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_EXPIRED"));
        }
        String trimmed = value.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_URL"));
        }
        return decodeDataUri(trimmed);
    }

    DecodedImage decodeDataUri(String value) {
        if (value == null || !value.regionMatches(true, 0, "data:", 0, 5)) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        int comma = value.indexOf(',');
        if (comma < 5) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        String metadata = value.substring(5, comma);
        String payload = value.substring(comma + 1);
        if (!metadata.toLowerCase(Locale.ROOT).endsWith(";base64")) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        int separator = metadata.indexOf(';');
        String mediaType = (separator < 0 ? metadata : metadata.substring(0, separator)).strip().toLowerCase(Locale.ROOT);
        mediaType = normalizeImageMediaType(mediaType);
        if (mediaType == null) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_NOT_IMAGE"));
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        if (decoded.length < 1) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        if (decoded.length > MAX_OUTPUT_BYTES) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_TOO_LARGE"));
        }
        return new DecodedImage(mediaType, decoded);
    }

    private String singleOutput(JsonNode body) {
        OutputInspection inspection = inspectOutputs(body);
        if (inspection.kind() != OutputKind.IMAGE && inspection.kind() != OutputKind.EXPIRED && inspection.kind() != OutputKind.URL && inspection.kind() != OutputKind.MALFORMED) {
            throw new FashnProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false,
                inspection.kind() == OutputKind.COUNT ? "OUTPUT_COUNT" : "OUTPUT_MISSING"));
        }
        return inspection.value();
    }

    private OutputInspection inspectOutputs(JsonNode body) {
        JsonNode output = body == null ? null : body.get("output");
        if (output == null || output.isNull()) {
            return new OutputInspection(OutputKind.MISSING, null);
        }
        if (!output.isArray()) {
            return new OutputInspection(OutputKind.COUNT, null);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : output) {
            if (node == null || node.isNull()) {
                values.add(null);
            } else if (node.isString()) {
                values.add(node.stringValue());
            } else {
                return new OutputInspection(OutputKind.COUNT, null);
            }
        }
        if (values.isEmpty()) {
            return new OutputInspection(OutputKind.MISSING, null);
        }
        if (values.size() != 1) {
            return new OutputInspection(OutputKind.COUNT, null);
        }
        String value = values.get(0);
        if (value == null || value.isBlank()) {
            return new OutputInspection(OutputKind.MISSING, null);
        }
        if ("_expired".equals(value)) {
            return new OutputInspection(OutputKind.EXPIRED, value);
        }
        String lower = value.strip().toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return new OutputInspection(OutputKind.URL, value);
        }
        if (lower.startsWith("data:image/") && lower.contains(";base64,")) {
            return new OutputInspection(OutputKind.IMAGE, value);
        }
        return new OutputInspection(OutputKind.MALFORMED, value);
    }

    private String previewCategory(ItemCategory category) {
        return switch (category.value()) {
            case "clothing" -> "auto";
            case "tops" -> "tops";
            case "bottoms" -> "bottoms";
            case "one-pieces" -> "one-pieces";
            default -> throw new IllegalStateException("unsupported preview category");
        };
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.isObject()) {
            return null;
        }
        JsonNode node = body.get(field);
        if (node == null || node.isNull() || !node.isString()) {
            return null;
        }
        String value = node.stringValue();
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String apiErrorName(JsonNode body) {
        if (body == null || !body.isObject()) {
            return "";
        }
        return errorName(body.get("error"));
    }

    private static String errorName(JsonNode error) {
        if (error == null || error.isNull()) {
            return "";
        }
        if (error.isString()) {
            String value = error.stringValue();
            return value == null ? "" : value.strip();
        }
        if (error.isObject()) {
            JsonNode name = error.get("name");
            if (name != null && name.isString()) {
                String value = name.stringValue();
                return value == null ? "" : value.strip();
            }
        }
        return "";
    }

    private enum OutputKind {
        MISSING,
        COUNT,
        EXPIRED,
        URL,
        IMAGE,
        MALFORMED
    }

    private record OutputInspection(OutputKind kind, String value) {
    }

    record DecodedImage(String mediaType, byte[] bytes) {
        DecodedImage {
            if (mediaType == null || bytes == null) {
                throw new IllegalArgumentException("decoded image is required");
            }
        }

        @Override
        public String toString() {
            return "DecodedImage[mediaType=" + mediaType + ", bytes=" + bytes.length + "]";
        }
    }
}
