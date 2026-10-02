package com.aifashionvault.platform.web;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aifashionvault.integrity.AppIntegrityOperation;
import com.aifashionvault.integrity.AppIntegrityVerifier;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.JdbcGenerationStore;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.fashn.FashnConfig;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.admission.JdbcGenerationRateLimiter;
import com.aifashionvault.platform.generation.GenerationMetrics;
import com.aifashionvault.shared.UserId;

@RestController
@RequestMapping("/generations")
public class GenerationsController {

	private final GenerationCreationService service;
	private final AppIntegrityBoundary integrity;

	public GenerationsController(
		ObjectProvider<GenerationStore> stores,
		ObjectProvider<DataSource> dataSources,
		ObjectProvider<GenerationMediaStore> mediaStores,
		ObjectProvider<ObjectStore> objectStores,
		ObjectProvider<GenerationMetrics> generationMetrics,
		GenerationApiProperties properties,
		VaultEnvironmentProperties vaultProperties,
		AppIntegrityVerifier verifier
	) {
		this.integrity = new AppIntegrityBoundary(verifier, properties.isAttestationRequired());
		DataSource dataSource = first(dataSources);
		if (dataSource == null) {
			this.service = null;
			return;
		}
		GenerationStore store = first(stores);
		if (store == null) {
			store = new JdbcGenerationStore(dataSource);
		}
		GenerationMediaStore mediaStore = resolveMediaStore(mediaStores, objectStores);
		this.service = new GenerationCreationService(
			store, new JdbcTemplate(dataSource), properties, Clock.systemUTC(),
			mediaStore, new JdbcGenerationRateLimiter(dataSource, Clock.systemUTC()),
			generationMetrics.getIfAvailable(), FashnConfig.anyProviderConfigured(vaultProperties));
	}

	@PostMapping
    public ResponseEntity<GenerationResponse> create(
        @AuthenticationPrincipal UserId userId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody(required = false) GenerationRequest request,
        HttpServletRequest httpRequest
    ) {
        this.integrity.verify(httpRequest, AppIntegrityOperation.GENERATION_CREATION);
        GenerationResponse body = service().create(userId, idempotencyKey, request,
            httpRequest.getHeader("X-Device-Id"), httpRequest.getRemoteAddr());
		return ResponseEntity.accepted()
			.location(URI.create("/generations/" + body.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.body(body);
	}

	@GetMapping
	public List<GenerationResponse> list(@AuthenticationPrincipal UserId userId) {
		return service().list(userId);
	}

	@GetMapping("/{generationId}")
	public GenerationResponse get(
		@AuthenticationPrincipal UserId userId,
		@PathVariable String generationId
	) {
		return service().get(userId, generationId);
	}

	@PostMapping("/{generationId}/cancel")
	public GenerationResponse cancel(
		@AuthenticationPrincipal UserId userId,
		@PathVariable String generationId
	) {
		return service().cancel(userId, generationId);
	}

	@ExceptionHandler(AppIntegrityBoundary.Problem.class)
	ResponseEntity<GenerationProblemResponse> integrityProblem(AppIntegrityBoundary.Problem ex) {
		return ResponseEntity.status(ex.status()).contentType(MediaType.APPLICATION_JSON)
			.body(new GenerationProblemResponse(ex.code()));
	}

	@ExceptionHandler(GenerationCreationService.Problem.class)
	ResponseEntity<GenerationProblemResponse> problem(GenerationCreationService.Problem ex) {
		return ResponseEntity.status(ex.status())
			.contentType(MediaType.APPLICATION_JSON)
			.body(new GenerationProblemResponse(ex.code()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<GenerationProblemResponse> notReadable() {
		return problem(new GenerationCreationService.Problem(
			org.springframework.http.HttpStatus.BAD_REQUEST,
			GenerationCreationService.MALFORMED_REQUEST));
	}

	private GenerationCreationService service() {
		if (this.service == null) {
			throw new GenerationCreationService.Problem(
				org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
				GenerationCreationService.GENERATION_PROVIDER_UNAVAILABLE);
		}
		return this.service;
	}

	private static GenerationMediaStore resolveMediaStore(
		ObjectProvider<GenerationMediaStore> mediaStores,
		ObjectProvider<ObjectStore> objectStores
	) {
		GenerationMediaStore mediaStore = first(mediaStores);
		if (mediaStore != null) return mediaStore;
		ObjectStore objectStore = first(objectStores);
		return objectStore instanceof GenerationMediaStore supported ? supported : null;
	}

	private static <T> T first(ObjectProvider<T> provider) {
		try {
			return provider.getIfAvailable();
		}
		catch (RuntimeException ex) {
			return provider.orderedStream().findFirst().orElse(null);
		}
	}
}
