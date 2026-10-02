package com.aifashionvault.platform.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aifashionvault.privacy.Consent;
import com.aifashionvault.privacy.ConsentPurpose;
import com.aifashionvault.privacy.ConsentService;
import com.aifashionvault.shared.UserId;

/**
 * Authenticated owner-scoped consent API. Purposes are the bounded
 * {@link ConsentPurpose} set; a recorded grant carries the policy version the user
 * actually agreed to, and a withdrawal revokes that grant without deleting its
 * history. Problem responses carry an opaque code only (ADR-015).
 */
@RestController
@RequestMapping("/me/consents")
public class ConsentsController {

	private final ObjectProvider<ConsentService> services;

	public ConsentsController(ObjectProvider<ConsentService> services) {
		this.services = services;
	}

	@GetMapping
	public ConsentsResponse list(@AuthenticationPrincipal UserId userId) {
		List<Consent> history = service().list(userId);
		// A purpose can carry several non-revoked records when the policy version was
		// revised. Only the newest non-revoked record is the grant in force, so the
		// surface reports `active` for that row alone — otherwise two rows would both
		// claim to be active for the same purpose.
		Map<String, Consent> effective = new LinkedHashMap<>();
		for (Consent consent : history) {
			if (consent.revokedAt() == null) {
				effective.put(consent.purpose(), consent);
			}
		}
		List<ConsentResource> consents = history.stream()
			.map(consent -> resource(consent, consent.equals(effective.get(consent.purpose()))))
			.toList();
		return new ConsentsResponse(consents);
	}

	@PutMapping("/{purpose}")
	public ResponseEntity<ConsentResource> grant(
		@AuthenticationPrincipal UserId userId,
		@PathVariable String purpose,
		@RequestBody(required = false) ConsentGrantRequest request
	) {
		ConsentPurpose parsed = purpose(purpose);
		if (request == null || request.version() == null) {
			throw ConsentProblem.versionInvalid();
		}
		try {
			Consent granted = service().grant(userId, parsed, request.version());
			return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(resource(granted, true));
		} catch (IllegalArgumentException ex) {
			throw ConsentProblem.versionInvalid();
		}
	}

	@DeleteMapping("/{purpose}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void withdraw(@AuthenticationPrincipal UserId userId, @PathVariable String purpose) {
		if (!service().withdraw(userId, purpose(purpose))) {
			throw ConsentProblem.notGranted();
		}
	}

	@ExceptionHandler(ConsentProblem.class)
	ResponseEntity<ConsentProblemResponse> problem(ConsentProblem ex) {
		return ResponseEntity.status(ex.status())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ConsentProblemResponse(ex.code().name()));
	}

	private ConsentService service() {
		ConsentService service = this.services.getIfAvailable();
		if (service == null) {
			throw ConsentProblem.unavailable();
		}
		return service;
	}

	private static ConsentPurpose purpose(String value) {
		return ConsentPurpose.fromWire(value).orElseThrow(ConsentProblem::purposeInvalid);
	}

	private static ConsentResource resource(Consent consent, boolean active) {
		return new ConsentResource(consent.purpose(), consent.version(), consent.grantedAt(), consent.revokedAt(), active);
	}

	public record ConsentResource(String purpose, String version, Instant grantedAt, Instant revokedAt, boolean active) {
	}

	public record ConsentsResponse(List<ConsentResource> consents) {
	}

	public record ConsentGrantRequest(String version) {
	}

	public record ConsentProblemResponse(String code) {
	}

	enum Code {
		CONSENT_PURPOSE_INVALID,
		CONSENT_VERSION_INVALID,
		CONSENT_NOT_GRANTED,
		CONSENT_UNAVAILABLE
	}

	static final class ConsentProblem extends RuntimeException {
		private final HttpStatus status;
		private final Code code;

		private ConsentProblem(HttpStatus status, Code code) {
			super(code.name());
			this.status = status;
			this.code = code;
		}

		HttpStatus status() {
			return status;
		}

		Code code() {
			return code;
		}

		static ConsentProblem purposeInvalid() {
			return new ConsentProblem(HttpStatus.BAD_REQUEST, Code.CONSENT_PURPOSE_INVALID);
		}

		static ConsentProblem versionInvalid() {
			return new ConsentProblem(HttpStatus.BAD_REQUEST, Code.CONSENT_VERSION_INVALID);
		}

		static ConsentProblem notGranted() {
			return new ConsentProblem(HttpStatus.NOT_FOUND, Code.CONSENT_NOT_GRANTED);
		}

		static ConsentProblem unavailable() {
			return new ConsentProblem(HttpStatus.SERVICE_UNAVAILABLE, Code.CONSENT_UNAVAILABLE);
		}
	}
}
