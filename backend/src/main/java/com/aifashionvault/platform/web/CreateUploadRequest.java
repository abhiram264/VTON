package com.aifashionvault.platform.web;

import com.aifashionvault.platform.objectstore.ImageMediaPolicy;

public record CreateUploadRequest(String contentType, long contentLength) {

	public CreateUploadRequest {
		contentType = ImageMediaPolicy.requireDeclaration(contentType, contentLength);
	}
}
