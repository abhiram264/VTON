package com.aifashionvault.platform.web;

import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.aifashionvault.shared.UserId;
import com.aifashionvault.user.IdentityProvisioning;

@RestController
@RequestMapping("/me")
public class MeController {

	private final ObjectProvider<IdentityProvisioning> identityProvisioning;

	public MeController(ObjectProvider<IdentityProvisioning> identityProvisioning) {
		this.identityProvisioning = identityProvisioning;
	}

	@GetMapping
	public MeResponse me(@AuthenticationPrincipal UserId userId) {
		return new MeResponse(userId.value().toString());
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserId userId) {
		IdentityProvisioning provisioning = this.identityProvisioning.getIfAvailable();
		if (provisioning == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		provisioning.beginDeletion(userId, Instant.now());
	}
}
