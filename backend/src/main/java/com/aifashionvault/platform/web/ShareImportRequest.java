package com.aifashionvault.platform.web;

/**
 * Frozen discriminated-union request for POST /share/imports. The HTTP shape is
 * {@code {kind, sourceUrl?, uploadId?}} where kind is URL, IMAGE, or IMAGE_URL;
 * fields forbidden by the discriminant must be absent and are rejected with
 * INVALID_SHARE_IMPORT before any import is created.
 */
public record ShareImportRequest(String kind, String sourceUrl, String uploadId) {
}
