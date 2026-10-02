package com.aifashionvault.platform.generation;

/**
 * Raised when the owner's consent for sending their body image to a third-party
 * AI provider is not active.
 *
 * <p>Thrown before any bytes are read or any signed URL is minted, so a
 * withdrawal takes effect for work that has not yet transferred rather than
 * only for newly uploaded photos.</p>
 */
public final class ConsentRequiredForTransferException extends IllegalStateException {

	public ConsentRequiredForTransferException(String message) {
		super(message);
	}
}
