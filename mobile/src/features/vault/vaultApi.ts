import { apiRequest, requireOk } from '../my-model/http';

export type VaultItemSummary = { id: string; category: string; name: string };

function parseItems(value: unknown): VaultItemSummary[] {
  if (!Array.isArray(value)) throw new Error('Invalid Vault response');
  return value.map(row => {
    if (row === null || typeof row !== 'object') throw new Error('Invalid Vault response');
    const item = row as Record<string, unknown>;
    if (typeof item.id !== 'string' || typeof item.category !== 'string') throw new Error('Invalid Vault response');
    return { id: item.id, category: item.category, name: typeof item.name === 'string' ? item.name : 'Vault item' };
  });
}

export const vaultApi = {
  async list(category?: string): Promise<VaultItemSummary[]> {
    const query = category ? `?category=${encodeURIComponent(category)}` : '';
    return parseItems(await requireOk(await apiRequest({ baseUrl: '', getAccessToken: () => null }, `/vault/items${query}`, { method: 'GET' })));
  },
};
