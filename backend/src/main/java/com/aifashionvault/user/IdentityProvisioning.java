package com.aifashionvault.user;

import java.time.Instant;

import com.aifashionvault.shared.UserId;

public interface IdentityProvisioning {

	IdentityResolution resolve(String issuer, String subject);

	User beginDeletion(UserId userId, Instant deletedAt);
}
