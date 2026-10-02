package com.aifashionvault.platform.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifashionvault.privacy.Consent;
import com.aifashionvault.privacy.ConsentPurpose;
import com.aifashionvault.privacy.ConsentService;
import com.aifashionvault.privacy.ConsentStore;
import com.aifashionvault.shared.UserId;

/**
 * C06 consent surface: the owner's recorded consent for the two purposes that
 * gate body-image processing and the third-party AI transfer.
 *
 * <p>Every read and write is scoped to the authenticated principal — the owner
 * never appears in a request body or path. {@code active} is answered by
 * {@link ConsentService#isActive}, the same primitive the server-side
 * enforcement gate uses, so this surface can never report a purpose as granted
 * while the gate would refuse it. The reported {@code version} is the policy
 * version the grant was actually recorded under, never the server's current
 * version substituted for it.</p>
 *
 * <p>When no consent store is configured the surface answers
 * {@code 503 CONSENT_UNAVAILABLE} rather than reporting a purpose as granted:
 * an unanswerable question must never be answered "yes".</p>
 */
@RestController
@RequestMapping("/consents")
public class ConsentsController {

	static final String CONSENT_UNAVAILABLE = "CONSENT_UNAVAILABLE";
	static final String CONSENT_PURPOSE_UNKNOWN = "CONSENT_PURPOSE_UNKNOWN";

	private final ConsentService service;
	private final ConsentStore store;

	public ConsentsController(ObjectProvider<ConsentService> services, ObjectProvider<ConsentStore> stores) {
		this.service = services.getIfAvailable();
		this.store = stores.getIfAvailable();
	}

	@GetMapping
	public ConsentsResponse list(@AuthenticationPrincipal UserId owner) {
		ConsentService service = required();
		List<ConsentState> purposes = new ArrayList<>();
		for (ConsentPurpose purpose : ConsentPurpose.values()) {
			purposes.add(state(owner, purpose, service));
		}
		return new ConsentsResponse(service.policyVersion(), List.copyOf(purposes));
	}

	@PostMapping("/{purpose}")
	public ConsentState grant(@AuthenticationPrincipal UserId owner, @PathVariable String purpose) {
		ConsentService service = required();
		ConsentPurpose resolved = resolve(purpose);
		service.grant(owner, resolved);
		return state(owner, resolved, service);
	}

	@DeleteMapping("/{purpose}")
	public ConsentState withdraw(@AuthenticationPrincipal UserId owner, @PathVariable String purpose) {
		ConsentService service = required();
		ConsentPurpose resolved = resolve(purpose);
		service.withdraw(owner, resolved);
		return state(owner, resolved, service);
	}

	private ConsentState state(UserId owner, ConsentPurpose purpose, ConsentService service) {
		boolean active = service.isActive(owner, purpose);
		Optional<Consent> recorded = active && store != null
			? store.active(owner, purpose.wireName())
			: Optional.empty();
		return new ConsentState(
			purpose.wireName(),
			active,
			recorded.map(Consent::version).orElse(null),
			recorded.map(Consent::grantedAt).orElse(null),
			service.policyVersion());
	}

	private static ConsentPurpose resolve(String purpose) {
		for (ConsentPurpose candidate : ConsentPurpose.values()) {
			if (candidate.wireName().equals(purpose)) {
				return candidate;
			}
		}
		throw new UnknownPurpose();
	}

	private ConsentService required() {
		if (service == null || store == null) {
			throw new Unavailable();
		}
		return service;
	}

	@ExceptionHandler(UnknownPurpose.class)
	ResponseEntity<Problem> unknownPurpose() {
		return problem(HttpStatus.BAD_REQUEST, CONSENT_PURPOSE_UNKNOWN);
	}

	@ExceptionHandler(Unavailable.class)
	ResponseEntity<Problem> unavailable() {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, CONSENT_UNAVAILABLE);
	}

	private static ResponseEntity<Problem> problem(HttpStatus status, String code) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new Problem(code));
	}

	public record ConsentsResponse(String policyVersion, List<ConsentState> purposes) {
	}

	/**
	 * {@code version}/{@code grantedAt} describe the consent recorded as active
	 * right now (null when none is); {@code policyVersion} is the version the
	 * server records for a NEW grant, so a client can tell that an active grant
	 * predates the current policy.
	 */
	public record ConsentState(
		String purpose,
		boolean active,
		String version,
		Instant grantedAt,
		String policyVersion) {
	}

	record Problem(String code) {
	}

	private static final class UnknownPurpose extends RuntimeException {
	}

	private static final class Unavailable extends RuntimeException {
	}
}
