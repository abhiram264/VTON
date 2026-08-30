import { apiRequest, requireOk } from '../my-model/http';

export type StudioState = { sessionId: string; avatarProfileId?: string; itemIds: string[] };

function parseState(value: unknown): StudioState {
  if (value === null || typeof value !== 'object') throw new Error('Invalid Studio response');
  const row = value as Record<string, unknown>;
  if (typeof row.sessionId !== 'string' || !Array.isArray(row.itemIds) || !row.itemIds.every(id => typeof id === 'string')) throw new Error('Invalid Studio response');
  return { sessionId: row.sessionId, itemIds: row.itemIds as string[], ...(typeof row.avatarProfileId === 'string' ? { avatarProfileId: row.avatarProfileId } : {}) };
}

const config = { baseUrl: '', getAccessToken: () => null };

export const studioApi = {
  async get(sessionId: string): Promise<StudioState> {
    return parseState(await requireOk(await apiRequest(config, `/studio/sessions/${encodeURIComponent(sessionId)}`, { method: 'GET' })));
  },
  async addItem(sessionId: string, vaultItemId: string): Promise<StudioState> {
    return parseState(await requireOk(await apiRequest(config, `/studio/sessions/${encodeURIComponent(sessionId)}/commands`, { method: 'POST', body: JSON.stringify({ command: 'addItem', vaultItemId }) })));
  },
  async command(sessionId: string, text: string): Promise<StudioState> {
    return parseState(await requireOk(await apiRequest(config, `/studio/sessions/${encodeURIComponent(sessionId)}/natural-language-command`, { method: 'POST', body: JSON.stringify({ text }) })));
  },
};
