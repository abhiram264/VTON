import { apiRequest, requireOk } from '../my-model/http';
import { productionApiConfig } from '../../config/apiConfig';

export type CollectionSummary = { id: string; name: string; vaultItemIds: string[] };

function parseCollections(value: unknown): CollectionSummary[] {
  if (!Array.isArray(value)) throw new Error('Invalid Collections response');
  return value.map(row => {
    if (row === null || typeof row !== 'object') throw new Error('Invalid Collections response');
    const collection = row as Record<string, unknown>;
    if (typeof collection.id !== 'string' || typeof collection.name !== 'string' || !Array.isArray(collection.items)) throw new Error('Invalid Collections response');
    const vaultItemIds = collection.items.map(item => {
      if (item === null || typeof item !== 'object' || !('vaultItemId' in item) || typeof item.vaultItemId !== 'string') throw new Error('Invalid Collections response');
      return item.vaultItemId;
    });
    return { id: collection.id, name: collection.name, vaultItemIds };
  });
}

export const collectionApi = {
  async list(): Promise<CollectionSummary[]> {
    return parseCollections(await requireOk(await apiRequest(productionApiConfig, '/collections', { method: 'GET' })));
  },
};
