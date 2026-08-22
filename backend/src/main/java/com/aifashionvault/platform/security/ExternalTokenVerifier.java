package com.aifashionvault.platform.security;

public interface ExternalTokenVerifier {

	VerifiedExternalIdentity verify(String token);
}
