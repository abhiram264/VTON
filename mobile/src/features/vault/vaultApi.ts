import { apiRequest, rejectDataUri, requireOk, uploadMedia } from '../my-model/http';
import { productionApiConfig } from '../../config/apiConfig';
import { productContentType, validateProduct, ProductRejectedError } from '../generation/productUpload';
import type { LocalPhoto } from '../my-model/types';

// Mirrors the mounted VaultController representation. The list route already
// carries brand/subcategory/color/sourceReferenceId, so they are optional here:
// the list parse keeps them when present and the detail route is authoritative.
export type VaultItemSummary = {
  id: string;
  category: string;
  name: string;
  brand?: string | null;
  subcategory?: string | null;
  color?: string | null;
  sourceReferenceId?: string | null;
  createdAt?: string | null;
};

// GET /vault/items/{id}/image — the same signed read capability shape as
// LooksController's ReadCapability: a short-lived presigned URL plus its expiry,
// so an expired preview is refreshed by calling the route again.
export type VaultItemImage = { url: string; expiresAt: string };

export type VaultItemPatch = {
  name?: string;
  category?: string;
  brand?: string;
  subcategory?: string;
  color?: string;
};

function optionalText(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

function parseItem(value: unknown): VaultItemSummary {
  if (value === null || typeof value !== 'object') throw new Error('Invalid Vault response');
  const item = value as Record<string, unknown>;
  if (typeof item.id !== 'string') throw new Error('Invalid Vault response');
  return {
    id: item.id,
    category: typeof item.category === 'string' ? item.category : 'UNKNOWN',
    name: typeof item.name === 'string' ? item.name : 'Vault item',
    brand: optionalText(item.brand),
    subcategory: optionalText(item.subcategory),
    color: optionalText(item.color),
    sourceReferenceId: optionalText(item.sourceReferenceId),
    createdAt: optionalText(item.createdAt),
  };
}

function parseItems(value: unknown): VaultItemSummary[] {
  if (!Array.isArray(value)) throw new Error('Invalid Vault response');
  return value.map(row => parseItem(row));
}

function parseImage(value: unknown): VaultItemImage {
  if (value === null || typeof value !== 'object') throw new Error('Invalid Vault image response');
  const row = value as Record<string, unknown>;
  if (
    typeof row.url !== 'string' ||
    row.url.length === 0 ||
    typeof row.expiresAt !== 'string' ||
    row.expiresAt.length === 0
  ) {
    throw new Error('Invalid Vault image response');
  }
  return { url: row.url, expiresAt: row.expiresAt };
}

function itemPath(itemId: string): string {
  const trimmed = itemId?.trim() ?? '';
  if (trimmed.length === 0) {
    throw new Error('Vault item id is required');
  }
  return `/vault/items/${encodeURIComponent(trimmed)}`;
}

const config = productionApiConfig;

export const vaultApi = {
  async list(category?: string): Promise<VaultItemSummary[]> {
    const query = category ? `?category=${encodeURIComponent(category)}` : '';
    return parseItems(await requireOk(await apiRequest(config, `/vault/items${query}`, { method: 'GET' })));
  },
  async get(itemId: string): Promise<VaultItemSummary> {
    return parseItem(await requireOk(await apiRequest(config, itemPath(itemId), { method: 'GET' })));
  },
  async update(itemId: string, patch: VaultItemPatch): Promise<VaultItemSummary> {
    return parseItem(
      await requireOk(
        await apiRequest(config, itemPath(itemId), { method: 'PATCH', body: JSON.stringify(patch) }),
      ),
    );
  },
  // DELETE returns 204 with no body; requireOk resolves null for that status, so
  // a resolved promise here means the server confirmed the delete, not merely
  // that the request left the device.
  async remove(itemId: string): Promise<void> {
    await requireOk(await apiRequest(config, itemPath(itemId), { method: 'DELETE' }));
  },
  async image(itemId: string): Promise<VaultItemImage> {
    return parseImage(
      await requireOk(await apiRequest(config, `${itemPath(itemId)}/image`, { method: 'GET' })),
    );
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
