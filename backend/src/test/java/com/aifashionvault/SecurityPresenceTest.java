package com.aifashionvault;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityPresenceTest {

	@Test
	void springSecurityIsOnClasspath() {
		assertNotNull(Authentication.class);
	}

	@Test
	void firebaseTypesAreAbsent() {
		assertThrows(ClassNotFoundException.class, () -> Class.forName("com.google.firebase.FirebaseApp"));
		assertThrows(ClassNotFoundException.class, () -> Class.forName("com.google.firebase.auth.FirebaseAuth"));
	}
}
