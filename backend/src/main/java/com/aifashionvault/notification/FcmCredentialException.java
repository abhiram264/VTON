package com.aifashionvault.notification;

/**
 * Raised when the refreshable FCM credential cannot produce a usable access token.
 *
 * <p>Deliberately a {@link RuntimeException}: the delivery path must fail closed. The
 * message never contains the private key, the assertion or the minted token.
 */
public class FcmCredentialException extends RuntimeException {

	public FcmCredentialException(String message) {
		super(message);
	}
}
