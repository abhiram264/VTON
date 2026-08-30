package com.aifashionvault.collection;

public record MembershipResult(MembershipOutcome outcome, Collection collection) {
	public MembershipResult {
		if (outcome == null) throw new IllegalArgumentException("outcome is required");
		if ((outcome == MembershipOutcome.CHANGED || outcome == MembershipOutcome.UNCHANGED) != (collection != null)) {
			throw new IllegalArgumentException("collection must match outcome");
		}
	}
}
