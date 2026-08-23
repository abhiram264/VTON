package com.aifashionvault.platform.web;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.aifashionvault.platform.objectstore.ImageMediaType;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.objectstore.UploadWorkflow;
import com.aifashionvault.platform.objectstore.VerifiedUpload;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/uploads")
public class UploadsController {

	private final ObjectProvider<UploadWorkflow> workflows;

	public UploadsController(ObjectProvider<UploadWorkflow> workflows) {
		this.workflows = workflows;
	}

	@PostMapping
	public CreateUploadResponse create(
		@AuthenticationPrincipal UserId userId,
		@RequestBody CreateUploadRequest request
	) {
		UploadCapability capability = workflow().create(
			userId,
			new MediaDeclaration(request.contentType(), request.contentLength()));
		return new CreateUploadResponse(
			capability.id().value().toString(),
			capability.method(),
			capability.uri().toString(),
			capability.signedHeaders(),
			capability.expiresAt());
	}

	@PostMapping("/{id}/complete")
	public CompleteUploadResponse complete(
		@AuthenticationPrincipal UserId userId,
		@PathVariable String id
	) {
		VerifiedUpload verified = workflow().complete(userId, uploadId(id));
		return new CompleteUploadResponse(
			verified.id().value().toString(),
			"READY",
			mediaType(verified.mediaType()),
			verified.contentLength(),
			verified.width(),
			verified.height());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void abort(@AuthenticationPrincipal UserId userId, @PathVariable String id) {
		workflow().abort(userId, uploadId(id));
	}

	@ExceptionHandler(ObjectStoreExceptions.InvalidUploadDeclarationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	void invalidDeclaration() {
	}

	@ExceptionHandler(ObjectStoreExceptions.UnsupportedMediaTypeException.class)
	@ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
	void unsupportedMediaType() {
	}

	@ExceptionHandler(ObjectStoreExceptions.StagedObjectMissingException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	void stagedObjectMissing() {
	}

	@ExceptionHandler(ObjectStoreExceptions.ImageValidationException.class)
	@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
	void imageValidation() {
	}

	@ExceptionHandler(ObjectStoreExceptions.ObjectStoreUnavailableException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	void unavailable() {
	}

	@ExceptionHandler(IllegalArgumentException.class)
	void illegalArgument(IllegalArgumentException ex, HttpServletResponse response) {
		response.setStatus(statusForIllegalArgument(ex).value());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	void notReadable(HttpMessageNotReadableException ex, HttpServletResponse response) {
		response.setStatus(statusForCause(ex).value());
	}

	private UploadWorkflow workflow() {
		UploadWorkflow workflow = this.workflows.getIfAvailable();
		if (workflow == null) {
			throw new ObjectStoreExceptions.ObjectStoreUnavailableException("upload workflow unavailable");
		}
		return workflow;
	}

	private static UploadId uploadId(String id) {
		try {
			return new UploadId(UUID.fromString(id));
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
	}

	private static String mediaType(ImageMediaType mediaType) {
		return mediaType == ImageMediaType.JPEG ? "image/jpeg" : "image/png";
	}

	private static HttpStatus statusForCause(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof IllegalArgumentException illegalArgument) {
				return statusForIllegalArgument(illegalArgument);
			}
			current = current.getCause();
		}
		return HttpStatus.BAD_REQUEST;
	}

	private static HttpStatus statusForIllegalArgument(IllegalArgumentException ex) {
		String message = ex.getMessage();
		if (message != null && message.contains("unsupported")) {
			return HttpStatus.UNSUPPORTED_MEDIA_TYPE;
		}
		return HttpStatus.BAD_REQUEST;
	}
}
