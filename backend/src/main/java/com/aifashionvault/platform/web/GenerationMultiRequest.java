package com.aifashionvault.platform.web;

import java.util.List;
import java.util.UUID;

/**
 * Studio-based multi-item create body (C12). Deliberately a SEPARATE record from
 * {@link GenerationRequest}: the guide forbids overloading {@code productUploadId}
 * to mean "a Vault item or session", so the manual single-item path keeps its
 * exact contract and the Studio path carries owned Vault items in submitted order.
 *
 * <p>{@code studioSessionId} is optional lineage only: when present it is
 * validated as owned and recorded on the accepted job; the frozen inputs are the
 * ordered {@code productVaultItemIds}, not a mutable session read.</p>
 */
public record GenerationMultiRequest(UUID studioSessionId, List<UUID> productVaultItemIds, String mode) {
}
