package com.aifashionvault.generation.vton;

import java.util.Optional;

public final class VtonRequestValidator {
    private VtonRequestValidator() { }

    public static Optional<ProviderFailure> validate(VtonRequest request, ProviderCapabilities capabilities) {
        if (request == null) return failure(ProviderFailureCategory.INVALID_REQUEST, "REQUEST_REQUIRED");
        if (capabilities == null) return failure(ProviderFailureCategory.UNSUPPORTED_CAPABILITY, "CAPABILITIES_REQUIRED");
        ModeCapabilities mode = capabilities.modes().get(request.mode());
        if (mode == null) return failure(ProviderFailureCategory.UNSUPPORTED_CAPABILITY, "MODE_UNSUPPORTED");
        if (request.productAssets().size() > mode.maxProductAssets()) return failure(ProviderFailureCategory.UNSUPPORTED_CAPABILITY, "PRODUCT_COUNT_UNSUPPORTED");
        if (request.instructions().isPresent() && !mode.instructionsSupported()) return failure(ProviderFailureCategory.UNSUPPORTED_CAPABILITY, "INSTRUCTIONS_UNSUPPORTED");
        if (request.productAssets().stream().anyMatch(asset -> !mode.supportedCategories().contains(asset.category()))) return failure(ProviderFailureCategory.UNSUPPORTED_CAPABILITY, "CATEGORY_UNSUPPORTED");
        if (mode.inputSizeLimit() instanceof InputSizeLimit.Known known) {
            if (exceeds(request.avatar(), known.maxAssetBytes()) || request.productAssets().stream().anyMatch(asset -> exceeds(asset.media(), known.maxAssetBytes()))) {
                return failure(ProviderFailureCategory.INVALID_REQUEST, "INPUT_SIZE_EXCEEDED");
            }
        }
        return Optional.empty();
    }

    private static boolean exceeds(PrivateMediaRef media, long maxBytes) {
        return media.sizeBytes().isPresent() && media.sizeBytes().getAsLong() > maxBytes;
    }

    private static Optional<ProviderFailure> failure(ProviderFailureCategory category, String code) {
        return Optional.of(new ProviderFailure(category, false, code));
    }
}
