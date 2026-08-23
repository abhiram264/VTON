package com.aifashionvault.platform.objectstore;

public sealed interface ClaimResult {

	record Copied() implements ClaimResult {
	}

	record Replay() implements ClaimResult {
	}

	record Conflict() implements ClaimResult {
	}
}
