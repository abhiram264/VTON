package com.aifashionvault.privacy;

import com.aifashionvault.shared.UserId;

public interface AccountDeletionWorkflow {

	AccountDeletionStatus request(UserId userId);

	AccountDeletionStatus resume(UserId userId);
}
