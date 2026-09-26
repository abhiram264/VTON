import { apiRequest, rejectDataUri, requireOk, uploadMedia } from '../my-model/http';
import { productionApiConfig } from '../../config/apiConfig';
import { productContentType, validateProduct, ProductRejectedError } from '../generation/productUpload';
import type { LocalPhoto } from '../my-model/types';

export type VaultItemSummary = { id: string; category: string; name: string };

function parseItem(value: unknown): VaultItemSummary {
  if (value === null || typeof value !== 'object') throw new Error('Invalid Vault response');
  const item = value as Record<string, unknown>;
  if (typeof item.id !== 'string') throw new Error('Invalid Vault response');
  return {
    id: item.id,
    category: typeof item.category === 'string' ? item.category : 'UNKNOWN',
    name: typeof item.name === 'string' ? item.name : 'Vault item',
  };
}

function parseItems(value: unknown): VaultItemSummary[] {
  if (!Array.isArray(value)) throw new Error('Invalid Vault response');
  return value.map(row => parseItem(row));
}

const config = productionApiConfig;

export const vaultApi = {
  async list(category?: string): Promise<VaultItemSummary[]> {
    const query = category ? `?category=${encodeURIComponent(category)}` : '';
    return parseItems(await requireOk(await apiRequest(config, `/vault/items${query}`, { method: 'GET' })));
  },
  async createFromPhoto(photo: LocalPhoto): Promise<VaultItemSummary> {
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
    const uploadId = await uploadMedia(config, {
      body,
      contentType,
      contentLength: body.byteLength,
    });
    const payload = await requireOk(
      await apiRequest(config, '/vault/items', {
        method: 'POST',
        body: JSON.stringify({ uploadId }),
      }),
    );
    return parseItem(payload);
  },
};
