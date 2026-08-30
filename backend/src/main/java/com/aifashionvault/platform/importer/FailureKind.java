package com.aifashionvault.platform.importer;

/** Classification of import fetch failures without carrying any URL detail. */
enum FailureKind {
	/** URL rejected by policy before any network attempt. */
	UNSAFE_URL,
	/** DNS lookup failure or NXDOMAIN. */
	DNS_FAILURE,
	/** Connection or read timeout, or total budget exhaustion. */
	TIMEOUT,

	/** TLS or connection failure that is neither timeout nor DNS. */
	TRANSPORT_UNUSABLE,
	/** Remote 5xx or protocol-level server error. */
	REMOTE_SERVER_ERROR,
	/** Object store unavailable while persisting media. */
	OBJECT_STORE_UNAVAILABLE,
	/** Content unusable: wrong type, malformed, oversized, unparseable. */
	UNUSABLE_CONTENT,
	/** Too many redirects or a malformed redirect target. */
	BAD_REDIRECT
}
