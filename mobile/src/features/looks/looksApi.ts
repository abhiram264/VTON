import { apiRequest, requireOk } from '../my-model/http';
import { createIdempotencyKey } from '../generation/generationApi';
import type { GenerationResource, LookResource } from '../generation/types';

function parseLook(value: unknown): LookResource {
  if (value === null || typeof value !== 'object') throw new Error('Invalid Look response');
  const row = value as Record<string, unknown>;
  const image = row.image as Record<string, unknown> | undefined;
  if (typeof row.id !== 'string' || typeof row.generationId !== 'string' || typeof row.createdAt !== 'string' || !image || typeof image.url !== 'string' || typeof image.expiresAt !== 'string') throw new Error('Invalid Look response');
  return { id: row.id, generationId: row.generationId, createdAt: row.createdAt, image: { url: image.url, expiresAt: image.expiresAt } };
}

const config = { baseUrl: '', getAccessToken: () => null };

export const looksApi = {
  async list(): Promise<LookResource[]> {
    const value = await requireOk(await apiRequest(config, '/looks', { method: 'GET' }));
    if (!Array.isArray(value)) throw new Error('Invalid Looks response');
    return value.map(parseLook);
  },
  async regenerate(lookId: string): Promise<GenerationResource> {
    return await requireOk(await apiRequest(config, `/looks/${encodeURIComponent(lookId)}/regenerate`, { method: 'POST', extraHeaders: { 'Idempotency-Key': createIdempotencyKey() } })) as GenerationResource;
  },
  async share(lookId: string): Promise<string> {
    const value = await requireOk(await apiRequest(config, `/looks/${encodeURIComponent(lookId)}/share-link`, { method: 'POST' }));
    if (value === null || typeof value !== 'object' || typeof (value as Record<string, unknown>).url !== 'string') throw new Error('Invalid share response');
    return (value as { url: string }).url;
  },
};
