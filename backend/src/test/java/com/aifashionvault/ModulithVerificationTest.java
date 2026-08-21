package com.aifashionvault;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithVerificationTest {

	@Test
	void applicationModulesVerify() {
		ApplicationModules.of(BackendApplication.class).verify();
	}
}
