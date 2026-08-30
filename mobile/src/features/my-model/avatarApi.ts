import type {
  AvatarApi,
  AvatarApiConfig,
  AvatarResource,
  LocalPhoto,
} from './types';
import { apiRequest, rejectDataUri, requireOk, uploadMedia } from './http';

function matchHeader(revision: number): string {
  return `"${revision}"`;
}

function normalizeContentType(value: string | null, uri: string): string {
  const raw = value ?? '';
  const mediaType = raw.split(';')[0].trim().toLowerCase();
  if (mediaType === 'image/jpeg' || mediaType === 'image/png') {
    return mediaType;
  }
  if (/\.png(?:$|\?)/i.test(uri)) {
    return 'image/png';
  }
  return 'image/jpeg';
}

function parseAvatar(value: unknown): AvatarResource {
  if (value === null || typeof value !== 'object') {
    throw new Error('Invalid avatar response');
  }
  const row = value as Record<string, unknown>;
  return {
    id: String(row.id),
    imageId: String(row.imageId),
    revision: Number(row.revision),
    width: Number(row.width),
    height: Number(row.height),
    mediaType: String(row.mediaType),
    displayUrl: String(row.displayUrl),
    displayExpiresAt: String(row.displayExpiresAt),
    warnings: Array.isArray(row.warnings) ? row.warnings.map(String) : [],
  };
}

async function readPhoto(
  photo: LocalPhoto,
): Promise<{ body: ArrayBuffer; contentType: string; contentLength: number }> {
  rejectDataUri(photo.uri);
  const response = await fetch(photo.uri);
  if (!response.ok) {
    throw new Error('Could not read photo');
  }
  const body = await response.arrayBuffer();
  return {
    body,
    contentType: normalizeContentType(
      response.headers.get('content-type'),
      photo.uri,
    ),
    contentLength: body.byteLength,
  };
}

export function createAvatarApi(config: AvatarApiConfig): AvatarApi {
  return {
    async list() {
      const payload = await requireOk(
        await apiRequest(config, '/avatars', { method: 'GET' }),
      );
      if (!Array.isArray(payload)) {
        throw new Error('Invalid avatar list');
      }
      return payload.map(parseAvatar);
    },

    async create(photo) {
      const uploadId = await uploadMedia(
        config,
        await readPhoto(photo),
        'avatar',
      );
      const payload = await requireOk(
        await apiRequest(config, '/avatars', {
          method: 'POST',
          body: JSON.stringify({ uploadId }),
        }),
      );
      return parseAvatar(payload);
    },

    async replace(id, revision, photo) {
      const uploadId = await uploadMedia(
        config,
        await readPhoto(photo),
        'avatar',
      );
      const payload = await requireOk(
        await apiRequest(config, `/avatars/${id}`, {
          method: 'PATCH',
          body: JSON.stringify({ uploadId }),
          extraHeaders: { 'If-Match': matchHeader(revision) },
        }),
      );
      return parseAvatar(payload);
    },

    async remove(id, revision) {
      await requireOk(
        await apiRequest(config, `/avatars/${id}`, {
          method: 'DELETE',
          extraHeaders: { 'If-Match': matchHeader(revision) },
        }),
      );
    },
  };
}

export const avatarApi = createAvatarApi({
  baseUrl: '',
  getAccessToken: () => null,
});
