package com.aifashionvault.platform.web;


import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import com.aifashionvault.shared.UserId;

/**
 * Authenticated share-import API: create/replay (POST), owner-scoped read
 * (GET), recovery attach (PUT .../image), and idempotent logical delete
 * (DELETE). Problem responses carry an opaque code only (ADR-015).
 */
@RestController
@RequestMapping("/share/imports")
public class SharesController {

	private final ObjectProvider<ShareImportService> services;

	public SharesController(ObjectProvider<ShareImportService> services) {
		this.services = services;
	}

	@PostMapping
	public ResponseEntity<ShareImportResponse> create(
		@AuthenticationPrincipal UserId userId,
		@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
		@RequestBody(required = false) ShareImportRequest request
	) {
		ShareImportService.CreateOutcome outcome = service().create(userId, idempotencyKey, request);
		return ResponseEntity.status(outcome.status())
			.contentType(MediaType.APPLICATION_JSON)
			.body(outcome.body());
	}

	@GetMapping("/{importId}")
	public ShareImportResponse get(
		@AuthenticationPrincipal UserId userId,
		@PathVariable String importId
	) {
		return service().get(userId, importId);
	}

	@PutMapping("/{importId}/image")
	public ResponseEntity<ShareImportResponse> attach(
		@AuthenticationPrincipal UserId userId,
		@PathVariable String importId,
		@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
		@RequestBody(required = false) AttachShareImageRequest request
	) {
		ShareImportService.AttachOutcome outcome = service().attach(userId, importId, idempotencyKey, request);
		return ResponseEntity.status(outcome.status())
			.contentType(MediaType.APPLICATION_JSON)
			.body(outcome.body());
	}

	@DeleteMapping("/{importId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserId userId, @PathVariable String importId) {
		if (!service().delete(userId, importId)) {
			throw ShareImportProblem.notFound();
		}
	}

	@PostMapping("/{importId}/confirm")
	public ResponseEntity<VaultItemResponse> confirm(
		@AuthenticationPrincipal UserId userId,
		@PathVariable String importId
	) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.contentType(MediaType.APPLICATION_JSON)
			.body(service().confirm(userId, importId));
	}


	@ExceptionHandler(ShareImportProblem.class)
	ResponseEntity<ShareImportProblemResponse> problem(ShareImportProblem ex) {
		return ResponseEntity.status(ex.status())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ShareImportProblemResponse(ex.code().name()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ShareImportProblemResponse> notReadable() {
		return problem(ShareImportProblem.invalid());
	}

	private ShareImportService service() {
		ShareImportService service = this.services.getIfAvailable();
		if (service == null) {
			throw ShareImportProblem.dependencyUnavailable();
		}
		return service;
	}

}
