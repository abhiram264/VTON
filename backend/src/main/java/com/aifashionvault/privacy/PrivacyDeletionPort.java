package com.aifashionvault.privacy;

import com.aifashionvault.shared.UserId;

public interface PrivacyDeletionPort {

	AccountDeletionStep step();

	void execute(UserId userId);
}
