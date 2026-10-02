package com.aifashionvault.platform.web;

/**
 * Optional owner corrections applied when a resolved share import is confirmed:
 * a corrected display name, a corrected product category (an existing
 * {@code ProductCategory} name), and the collection the saved item joins. All
 * fields are optional; an absent body confirms with the resolved metadata only.
 */
public record ConfirmShareImportRequest(String name, String category, String collectionId) {
}
