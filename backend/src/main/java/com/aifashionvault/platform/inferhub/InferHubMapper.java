package com.aifashionvault.platform.inferhub;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.platform.objectstore.ImageMediaPolicy;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;

import tools.jackson.databind.JsonNode;

public final class InferHubMapper {
    static final ProviderKey PROVIDER_KEY = new ProviderKey("inferhub");
    static final String MODEL = "cb/gemini-3.1-flash-image";
    static final long MAX_INLINE_BYTES = 4L * 1024 * 1024;
    static final long MAX_ASSET_BYTES = 8L * 1024 * 1024;
    static final long MAX_OUTPUT_BYTES = 4L * 1024 * 1024;
    static final Duration SIGNED_READ_TTL = Duration.ofMinutes(5);
    static final String OUTPUT_INDEX = "0";

    /**
     * Adapter error-mapping contract version stamped on every normalized
     * {@link ProviderFailure} this adapter produces, matching the FASHN
     * convention so failures are never silently reclassified by later
     * mapper revisions.
     */
    public static final String ERROR_MAPPING_CONTRACT_VERSION = "inferhub-mapping-v1";

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

    private static final VtonMode FAST_PREVIEW = new VtonMode("fast_preview");
    private static final VtonMode STANDARD = new VtonMode("standard");
    private static final VtonMode HD = new VtonMode("hd");

    ProviderKey providerKey() {
        return PROVIDER_KEY;
    }

    ProviderCapabilities capabilities() {
        // Single synchronous image model: the same size limit and category set per mode;
        // inputs are inline data-URIs only (the wire contract has no signed-read surface).
        ModeCapabilities preview = new ModeCapabilities(PREVIEW_CATEGORIES, 1, false, new InputSizeLimit.Known(MAX_ASSET_BYTES));
        ModeCapabilities max = new ModeCapabilities(MAX_CATEGORIES, 1, true, new InputSizeLimit.Known(MAX_ASSET_BYTES));
        return new ProviderCapabilities(
            Map.of(FAST_PREVIEW, preview, STANDARD, max, HD, max),
            Set.of(InputTransferMethod.BOUNDED_INLINE_DATA),
            Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
            SubmissionReplaySafety.UNSAFE);
    }

    PrivacyTransferPolicy privacyPolicy() {
        return new PrivacyTransferPolicy(
            Set.of(InputTransferMethod.BOUNDED_INLINE_DATA),
            Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
            MAX_INLINE_BYTES,
            SIGNED_READ_TTL,
            true);
    }

    /**
     * Owner-verified chat-completions request shape: model id plus one user message whose
     * content is a typed array of a text part followed by image_url parts carrying inline
     * data-URIs. The avatar is image 1, the garment image 2 (single-asset by design).
     */
    Map<String, Object> runRequest(
        VtonMode mode,
        String avatarDataUri,
        String garmentDataUri,
        ItemCategory category,
        Optional<String> instructions
    ) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", prompt(mode, category, instructions));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(text, imagePart(avatarDataUri), imagePart(garmentDataUri)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", MODEL);
        root.put("messages", List.of(message));
        return root;
    }

    private static Map<String, Object> imagePart(String dataUri) {
        Map<String, Object> url = new LinkedHashMap<>();
        url.put("url", dataUri);
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "image_url");
        part.put("image_url", url);
        return part;
    }

    private static String prompt(VtonMode mode, ItemCategory category, Optional<String> instructions) {
        if (instructions.isPresent() && !instructions.get().isBlank()) {
            return instructions.get();
        }
        return "Dress the person from the first image in the garment from the second image. "
            + "Garment category: " + category.value() + ". Quality: " + mode.value() + ".";
    }

    /**
     * Maps one synchronous chat-completions response onto the provider seam. The
     * generation is complete when submit returns, so the accepted submission carries a
     * synthetic job id and the extracted inline data-URI (persisted durably at submit,
     * streamed back in openOutput).
     */
    Mapped mapRun(InferHubHttpResponse response) {
        if (response.outcome() == InferHubHttpResponse.Outcome.UNSENT) {
            return new Mapped(new GenerationSubmission.Rejected(
                failure(ProviderFailureCategory.TIMEOUT_NETWORK, true, "CONNECTION_FAILED")), null);
        }
        if (response.outcome() == InferHubHttpResponse.Outcome.INDETERMINATE) {
            return new Mapped(new GenerationSubmission.Indeterminate(
                failure(ProviderFailureCategory.TIMEOUT_NETWORK, true, "INDETERMINATE_IO")), null);
        }
        if (response.statusCode() != 200) {
            return new Mapped(new GenerationSubmission.Rejected(mapApiError(response.statusCode())), null);
        }
        if (response.bodyOversized()) {
            return new Mapped(new GenerationSubmission.Indeterminate(
                failure(ProviderFailureCategory.PROTOCOL_VIOLATION, true, "OUTPUT_TOO_LARGE")), null);
        }
        String dataUri = imageDataUri(response.body());
        if (dataUri == null) {
            return new Mapped(new GenerationSubmission.Rejected(
                failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MISSING")), null);
        }
        if (!dataUri.strip().toLowerCase(Locale.ROOT).startsWith("data:")) {
            return new Mapped(new GenerationSubmission.Rejected(
                failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_URL")), null);
        }
        String jobId = newJobId();
        return new Mapped(new GenerationSubmission.Accepted(new ProviderJobId(jobId), Optional.empty()), dataUri);
    }

    StatusQueryResult mapStatus(ProviderJobId jobId, boolean outputCached) {
        if (outputCached) {
            return new StatusQueryResult.Observed(new GenerationStatus.Succeeded(
                List.of(outputRef(jobId)), Optional.empty()));
        }
        // The synchronous result is only ever held by this adapter instance; a miss means
        // the output is gone (restart between submit and fetch) and cannot be re-fetched.
        return new StatusQueryResult.Observed(new GenerationStatus.Failed(
            failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MISSING"), Optional.empty()));
    }

    ProviderFailure mapApiError(int status) {
        if (status == 401 || status == 403) {
            return failure(ProviderFailureCategory.AUTH_CONFIGURATION, false, "AUTH_CONFIGURATION");
        }
        if (status == 429) {
            return failure(ProviderFailureCategory.RATE_CAPACITY, true, "RATE_LIMIT");
        }
        // 502/503 are transient upstream (owner-verified); 500/504 likewise never permanent.
        if (status >= 500 && status <= 599) {
            return failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "PROVIDER_INTERNAL");
        }
        if (status == 400 || status == 404 || status == 422) {
            return failure(ProviderFailureCategory.INVALID_REQUEST, false, "INVALID_REQUEST");
        }
        return failure(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, false, "UNKNOWN_PROVIDER_FAILURE");
    }

    /**
     * Extracts the inline image data-URI from the owner-verified response shape:
     * {@code choices[0].message.content} is an array whose image part is
     * {@code {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}}.
     * A remote URL is a protocol violation, never dereferenced.
     */
    String imageDataUri(JsonNode body) {
        JsonNode content = contentArray(body);
        if (content == null) {
            return null;
        }
        for (JsonNode part : content) {
            if (part == null || !part.isObject() || !"image_url".equals(text(part, "type"))) {
                continue;
            }
            JsonNode imageUrl = part.get("image_url");
            if (imageUrl == null || !imageUrl.isObject()) {
                return null;
            }
            String url = text(imageUrl, "url");
            if (url == null || url.isBlank()) {
                return null;
            }
            // A non-data scheme is a URL, not the inline image the wire contract promises.
            // It is returned rather than thrown so mapRun classifies it as a permanent
            // OUTPUT_URL rejection instead of escaping into submission-indeterminate.
            return url;
        }
        return null;
    }

    private static JsonNode contentArray(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        JsonNode choices = body.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode first = choices.get(0);
        if (!first.isObject()) {
            return null;
        }
        JsonNode message = first.get("message");
        if (message == null || !message.isObject()) {
            return null;
        }
        JsonNode content = message.get("content");
        return content != null && content.isArray() ? content : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * Decodes the cached inline data-URI into image bytes: base64 payload, normalized
     * image media type, non-empty and within the output cap. Every malformed shape is a
     * classified {@link ProviderFailure}, never an uncaught exception.
     */
    DecodedImage decodeDataUri(String value) {
        if (value == null || !value.regionMatches(true, 0, "data:", 0, 5)) {
            throw new InferHubProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        int comma = value.indexOf(',');
        if (comma < 5) {
            throw new InferHubProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        String metadata = value.substring(5, comma);
        String payload = value.substring(comma + 1);
        if (!metadata.toLowerCase(Locale.ROOT).endsWith(";base64")) {
            throw new InferHubProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        int separator = metadata.indexOf(';');
        String mediaType = (separator < 0 ? metadata : metadata.substring(0, separator)).strip().toLowerCase(Locale.ROOT);
        mediaType = normalizeImageMediaType(mediaType);
        if (mediaType == null) {
            throw new InferHubProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_NOT_IMAGE"));
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new InferHubProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        if (decoded.length < 1) {
            throw new InferHubProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        if (decoded.length > MAX_OUTPUT_BYTES) {
            throw new InferHubProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_TOO_LARGE"));
        }
        return new DecodedImage(mediaType, decoded);
    }

    String newJobId() {
        return PROVIDER_KEY.value() + "-" + UUID.randomUUID();
    }

    /**
     * Re-stamps an adapter-minted job id with the owner scope the durable output is filed
     * under, so {@code getStatus}/{@code openOutput} can rebuild the object key from the id
     * alone — which is all the worker has after a restart. The scope is the owner UUID already
     * embedded in the resolved avatar storage key, so the persisted output stays inside the
     * {@code temp/<owner>/} prefix that account erasure purges.
     */
    ProviderJobId scopedJobId(ProviderJobId jobId, String ownerScope) {
        String prefix = PROVIDER_KEY.value() + "-";
        String value = jobId.value();
        if (!value.startsWith(prefix) || value.length() == prefix.length()) {
            throw new InferHubProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_REF"));
        }
        return new ProviderJobId(prefix + ownerScope + "-" + value.substring(prefix.length()));
    }

    /**
     * The owner scope embedded in a job id minted by {@link #scopedJobId}, empty for any id
     * this adapter did not mint (e.g. a probe): an unknown id must resolve to OUTPUT_MISSING,
     * never to another owner's object.
     */
    Optional<String> jobOwnerScope(ProviderJobId jobId) {
        String value = jobId.value();
        String prefix = PROVIDER_KEY.value() + "-";
        if (!value.startsWith(prefix)) {
            return Optional.empty();
        }
        String rest = value.substring(prefix.length());
        if (rest.length() < 38 || rest.charAt(36) != '-') {
            return Optional.empty();
        }
        String scope = rest.substring(0, 36);
        return isUuid(scope) ? Optional.of(scope) : Optional.empty();
    }

    /**
     * The owner scope of an owner-scoped storage key ({@code temp/<owner>/...} or
     * {@code users/<owner>/...}). Empty when the key carries no owner segment, so the caller
     * fails closed instead of filing a durable object outside the erasure prefix.
     */
    Optional<String> ownerScope(StorageKey storageKey) {
        String value = storageKey.value();
        String prefix;
        if (value.startsWith("temp/")) {
            prefix = "temp/";
        } else if (value.startsWith("users/")) {
            prefix = "users/";
        } else {
            return Optional.empty();
        }
        String rest = value.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return Optional.empty();
        }
        String scope = rest.substring(0, slash);
        return isUuid(scope) ? Optional.of(scope) : Optional.empty();
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    ProviderOutputRef outputRef(ProviderJobId jobId) {
        return new ProviderOutputRef(jobId.value() + ":" + OUTPUT_INDEX);
    }

    String outputRefValue(ProviderJobId jobId) {
        return jobId.value() + ":" + OUTPUT_INDEX;
    }

    String outputRefValue(ProviderOutputRef outputRef) {
        String value = outputRef.value();
        int split = value.lastIndexOf(':');
        if (split <= 0 || split == value.length() - 1 || !OUTPUT_INDEX.equals(value.substring(split + 1))) {
            throw new InferHubProviderException(failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_REF"));
        }
        return value;
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

    static ProviderFailure failure(ProviderFailureCategory category, boolean retryable, String code) {
        return new ProviderFailure(category, retryable, code, null, ERROR_MAPPING_CONTRACT_VERSION);
    }

    /**
     * One mapped submit outcome: the seam submission plus the inline image data-URI the
     * provider must retain for the later openOutput fetch (null unless accepted).
     */
    record Mapped(GenerationSubmission submission, String dataUri) {
        public Mapped {
            if (submission == null) {
                throw new IllegalArgumentException("submission is required");
            }
        }
    }

    record DecodedImage(String mediaType, byte[] bytes) {
        public DecodedImage {
            if (mediaType == null || mediaType.isBlank()) {
                throw new IllegalArgumentException("media type is required");
            }
            if (bytes == null || bytes.length < 1) {
                throw new IllegalArgumentException("image bytes are required");
            }
        }
    }
}
