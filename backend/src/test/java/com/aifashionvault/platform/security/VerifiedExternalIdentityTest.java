package com.aifashionvault.platform.security;

import java.lang.reflect.RecordComponent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedExternalIdentityTest {

	@Test
	void hasExactlyIssuerAndSubjectComponents() {
		assertThat(VerifiedExternalIdentity.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("issuer", "subject")
			.doesNotContain("email", "name", "firebaseUid", "claims");
		assertThat(VerifiedExternalIdentity.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(String.class, String.class);
	}

	@Test
	void rejectsNullOrBlankIssuerAndSubject() {
		assertThatThrownBy(() -> new VerifiedExternalIdentity(null, "subject-1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedExternalIdentity("", "subject-1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedExternalIdentity("   ", "subject-1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedExternalIdentity("https://securetoken.google.com/app", null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedExternalIdentity("https://securetoken.google.com/app", ""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VerifiedExternalIdentity("https://securetoken.google.com/app", "   "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void storesFirebaseUidAsSubjectNotDomainIdentity() {
		VerifiedExternalIdentity identity = new VerifiedExternalIdentity(
			"https://securetoken.google.com/app",
			"firebase-uid-abc");

		assertThat(identity.issuer()).isEqualTo("https://securetoken.google.com/app");
		assertThat(identity.subject()).isEqualTo("firebase-uid-abc");
		assertThatThrownBy(() -> VerifiedExternalIdentity.class.getMethod("firebaseUid"))
			.isInstanceOf(NoSuchMethodException.class);
		assertThatThrownBy(() -> VerifiedExternalIdentity.class.getMethod("email"))
			.isInstanceOf(NoSuchMethodException.class);
		assertThatThrownBy(() -> VerifiedExternalIdentity.class.getMethod("name"))
			.isInstanceOf(NoSuchMethodException.class);
		assertThatThrownBy(() -> VerifiedExternalIdentity.class.getMethod("claims"))
			.isInstanceOf(NoSuchMethodException.class);
	}
}
