import type { GenerationApiConfig, ProductUploader } from './types';
import { rejectDataUri, uploadMedia } from '../my-model/http';
import { productionApiConfig } from '../../config/apiConfig';

// Advisory client-side mirror of the server ImageMediaPolicy. The server
// inspection remains authoritative; these checks only fail spend early.
const MAX_PRODUCT_BYTES = 30 * 1024 * 1024;

export type ProductRejectionCode =
  | 'UNSUPPORTED_IMAGE_TYPE'
  | 'IMAGE_EMPTY'
  | 'IMAGE_TOO_LARGE';

export class ProductRejectedError extends Error {
  readonly code: ProductRejectionCode;

  constructor(code: ProductRejectionCode) {
    super(code);
    this.name = 'ProductRejectedError';
    this.code = code;
  }
}

export function productContentType(
  uri: string,
  declaredType?: string | null,
): 'image/jpeg' | 'image/png' | null {
  const mediaType = (declaredType ?? '').split(';')[0].trim().toLowerCase();
  if (mediaType === 'image/jpeg' || mediaType === 'image/png') {
    return mediaType;
  }
  if (/\.png(?:$|\?)/i.test(uri)) {
    return 'image/png';
  }
  if (/\.jpe?g(?:$|\?)/i.test(uri)) {
    return 'image/jpeg';
  }
  return null;
}

export function validateProduct(
  contentType: 'image/jpeg' | 'image/png' | null,
  contentLength: number,
): ProductRejectionCode | null {
  if (contentType === null) {
    return 'UNSUPPORTED_IMAGE_TYPE';
  }
  if (contentLength < 1) {
    return 'IMAGE_EMPTY';
  }
  if (contentLength > MAX_PRODUCT_BYTES) {
    return 'IMAGE_TOO_LARGE';
  }
  return null;
}

export function createProductUploader(
  config: GenerationApiConfig,
): ProductUploader {
  return async photo => {
    rejectDataUri(photo.uri);
    const response = await fetch(photo.uri);
    if (!response.ok) {
      throw new Error('Could not read product image');
    }
    const body = await response.arrayBuffer();
    const contentType = productContentType(
      photo.uri,
      response.headers.get('content-type'),
    );
    const rejection = validateProduct(contentType, body.byteLength);
    if (contentType === null || rejection !== null) {
      throw new ProductRejectedError(rejection ?? 'UNSUPPORTED_IMAGE_TYPE');
    }
    return uploadMedia(config, {
      body,
      contentType,
      contentLength: body.byteLength,
    });
  };
}

export const productUploader = createProductUploader(productionApiConfig);
