package com.aifashionvault.user;

import java.time.Instant;

import com.aifashionvault.shared.UserId;

public record User(UserId id, Instant createdAt, Instant updatedAt, Instant deletedAt) {

	public User {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (updatedAt == null) {
			throw new IllegalArgumentException("updatedAt is required");
		}
	}

	public User markDeleted(Instant deletedAt) {
		if (deletedAt == null) {
			throw new IllegalArgumentException("deletedAt is required");
		}
		if (this.deletedAt != null) {
			throw new IllegalArgumentException("user is already deleted");
		}
		return new User(id, createdAt, deletedAt, deletedAt);
	}
}
