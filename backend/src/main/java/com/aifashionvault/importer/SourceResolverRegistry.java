package com.aifashionvault.importer;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SourceResolverRegistry {

	private static final String SHARED_IMAGE = "SHARED_IMAGE";
	private static final String GENERIC_OPEN_GRAPH = "GENERIC_OPEN_GRAPH";
	private static final String GENERIC_URL = "GENERIC_URL";
	private static final Set<String> BUILT_IN_IDS = Set.of(SHARED_IMAGE, GENERIC_OPEN_GRAPH, GENERIC_URL);

	private final List<SourceResolverRegistration> registrations;

	public SourceResolverRegistry(List<SourceResolverRegistration> registrations) {
		Objects.requireNonNull(registrations, "registrations");
		this.registrations = registrations.stream()
			.map(registration -> Objects.requireNonNull(registration, "registration"))
			.sorted(Comparator.comparingInt(SourceResolverRegistration::precedence))
			.toList();
		validate();
	}

	public List<SourceResolver> createResolvers(ResolutionContext context) {
		Objects.requireNonNull(context, "context");
		return registrations.stream()
			.map(registration -> Objects.requireNonNull(registration.factory().create(context), "resolver"))
			.toList();
	}

	private void validate() {
		if (registrations.isEmpty()) {
			throw new IllegalArgumentException("at least one resolver registration is required");
		}
		SourceResolverRegistration first = registrations.getFirst();
		if (!SHARED_IMAGE.equals(first.id()) || first.precedence() != SourceResolverPrecedence.SHARED_IMAGE) {
			throw new IllegalArgumentException("SHARED_IMAGE must be first at precedence 100");
		}
		SourceResolverRegistration last = registrations.getLast();
		if (!GENERIC_URL.equals(last.id()) || last.precedence() != SourceResolverPrecedence.GENERIC_URL) {
			throw new IllegalArgumentException("GENERIC_URL must be last at precedence 1000");
		}

		Set<String> ids = new HashSet<>();
		Set<Integer> precedences = new HashSet<>();
		for (SourceResolverRegistration registration : registrations) {
			if (!ids.add(registration.id())) {
				throw new IllegalArgumentException("duplicate resolver registration id");
			}
			if (!precedences.add(registration.precedence())) {
				throw new IllegalArgumentException("duplicate resolver registration precedence");
			}
			validatePrecedence(registration);
		}
	}

	private static void validatePrecedence(SourceResolverRegistration registration) {
		int expected = switch (registration.id()) {
			case SHARED_IMAGE -> SourceResolverPrecedence.SHARED_IMAGE;
			case GENERIC_OPEN_GRAPH -> SourceResolverPrecedence.GENERIC_OPEN_GRAPH;
			case GENERIC_URL -> SourceResolverPrecedence.GENERIC_URL;
			default -> -1;
		};
		if (BUILT_IN_IDS.contains(registration.id())) {
			if (registration.precedence() != expected) {
				throw new IllegalArgumentException("built-in resolver registration has wrong precedence");
			}
		} else if (registration.precedence() < SourceResolverPrecedence.OPTIONAL_MIN
				|| registration.precedence() > SourceResolverPrecedence.OPTIONAL_MAX) {
			throw new IllegalArgumentException("optional resolver precedence must be between 200 and 800");
		}
	}
}
