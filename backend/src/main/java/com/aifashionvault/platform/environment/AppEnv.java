package com.aifashionvault.platform.environment;

public enum AppEnv {
	development,
	staging,
	production;

	static AppEnv parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("APP_ENV is required");
		}
		String value = raw.strip();
		for (AppEnv env : values()) {
			if (env.name().equals(value)) {
				return env;
			}
		}
		throw new IllegalArgumentException("APP_ENV must be one of development, staging, production");
	}
}
