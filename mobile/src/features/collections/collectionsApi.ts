import { apiRequest, requireOk } from '../my-model/http';
import type { AvatarApiConfig } from '../my-model/types';
import { productionApiConfig } from '../../config/apiConfig';

/**
 * Client for the mounted CollectionsController (/collections).
 *
 * Collections are a user-authored grouping of Vault garments. Membership is
 * many-to-many: one garment may belong to several collections, and neither
 * removing membership nor deleting a collection deletes the garment. This
 * module is deliberately transport-only (no local name validation): the
 * backend is the authority on name rules and answers INVALID_COLLECTION.
 */
export type CollectionItem = { vaultItemId: string; createdAt: string };

export type Collection = {
  id: string;
  name: string;
  createdAt: string;
  updatedAt: string;
  items: CollectionItem[];
};

export type CollectionsApi = {
  list(): Promise<Collection[]>;
  create(name: string): Promise<Collection>;
  rename(id: string, name: string): Promise<Collection>;
  remove(id: string): Promise<void>;
  addItem(id: string, vaultItemId: string): Promise<Collection>;
  removeItem(id: string, vaultItemId: string): Promise<void>;
};

function parseCollectionItem(value: unknown): CollectionItem {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid Collection response');
  }
  const item = value as Record<string, unknown>;
  if (
    typeof item.vaultItemId !== 'string' ||
    item.vaultItemId.trim().length === 0 ||
    typeof item.createdAt !== 'string' ||
    Number.isNaN(Date.parse(item.createdAt))
  ) {
    throw new Error('Invalid Collection response');
  }
  return { vaultItemId: item.vaultItemId, createdAt: item.createdAt };
}

function parseCollection(value: unknown): Collection {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Invalid Collection response');
  }
  const row = value as Record<string, unknown>;
  if (
    typeof row.id !== 'string' ||
    row.id.trim().length === 0 ||
    typeof row.name !== 'string' ||
    typeof row.createdAt !== 'string' ||
    Number.isNaN(Date.parse(row.createdAt)) ||
    typeof row.updatedAt !== 'string' ||
    Number.isNaN(Date.parse(row.updatedAt)) ||
    !Array.isArray(row.items)
  ) {
    throw new Error('Invalid Collection response');
  }
  return {
    id: row.id,
    name: row.name,
    createdAt: row.createdAt,
    updatedAt: row.updatedAt,
    items: row.items.map(parseCollectionItem),
  };
}

export function createCollectionsApi(
  config: AvatarApiConfig = productionApiConfig,
): CollectionsApi {
  return {
    async list() {
      const payload = await requireOk(
        await apiRequest(config, '/collections', { method: 'GET' }),
      );
      if (!Array.isArray(payload)) {
        throw new Error('Invalid Collections response');
      }
      return payload.map(parseCollection);
    },

    async create(name) {
      const payload = await requireOk(
        await apiRequest(config, '/collections', {
          method: 'POST',
          body: JSON.stringify({ name }),
        }),
      );
      return parseCollection(payload);
    },

    async rename(id, name) {
      const payload = await requireOk(
        await apiRequest(config, `/collections/${id}`, {
          method: 'PATCH',
          body: JSON.stringify({ name }),
        }),
      );
      return parseCollection(payload);
    },

    async remove(id) {
      await requireOk(
        await apiRequest(config, `/collections/${id}`, { method: 'DELETE' }),
      );
    },

    async addItem(id, vaultItemId) {
      const payload = await requireOk(
        await apiRequest(config, `/collections/${id}/items`, {
          method: 'POST',
          body: JSON.stringify({ vaultItemId }),
        }),
      );
      return parseCollection(payload);
    },

    async removeItem(id, vaultItemId) {
      await requireOk(
        await apiRequest(config, `/collections/${id}/items/${vaultItemId}`, {
          method: 'DELETE',
        }),
      );
    },
  };
}

export const collectionsApi = createCollectionsApi();
