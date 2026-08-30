import type { AvatarApiConfig } from './types';

export type UploadTicket = {
  id: string;
  method: string;
  url: string;
  headers: Record<string, string>;
};

export type MediaUpload = {
  body: ArrayBuffer;
  contentType: string;
  contentLength: number;
};

export class ApiProblemError extends Error {
  readonly code: string;

  constructor(code: string) {
    super(code);
    this.name = 'ApiProblemError';
    this.code = code;
  }
}

export function rejectDataUri(uri: string): void {
  if (/^data:/i.test(uri)) {
    throw new Error('data URIs are not supported');
  }
}

export async function apiRequest(
  config: AvatarApiConfig,
  path: string,
  init: {
    method: string;
    body?: string;
    extraHeaders?: Record<string, string>;
  },
): Promise<Response> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...init.extraHeaders,
  };
  if (init.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const token = await config.getAccessToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return fetch(`${config.baseUrl.replace(/\/+$/, '')}${path}`, {
    method: init.method,
    headers,
    body: init.body,
  });
}

async function readJson(response: Response): Promise<unknown> {
  if (response.status === 204) {
    return null;
  }
  return response.json();
}

export async function requireOk(response: Response): Promise<unknown> {
  const payload = await readJson(response);
  if (response.ok) {
    return payload;
  }
  if (
    payload !== null &&
    typeof payload === 'object' &&
    'code' in payload &&
    typeof (payload as { code: unknown }).code === 'string'
  ) {
    throw new ApiProblemError((payload as { code: string }).code);
  }
  throw new Error('Request failed');
}

// Shared initiate -> direct private PUT -> complete upload path. Media bytes
// never pass through the API as base64 or multipart.
export async function uploadMedia(
  config: AvatarApiConfig,
  file: MediaUpload,
  purpose?: string,
): Promise<string> {
  const path = purpose == null ? '/uploads' : `/uploads?purpose=${purpose}`;
  const ticketPayload = await requireOk(
    await apiRequest(config, path, {
      method: 'POST',
      body: JSON.stringify({
        contentType: file.contentType,
        contentLength: file.contentLength,
      }),
    }),
  );
  if (ticketPayload === null || typeof ticketPayload !== 'object') {
    throw new Error('Invalid upload ticket');
  }
  const ticket = ticketPayload as UploadTicket;
  const putResponse = await fetch(ticket.url, {
    method: ticket.method || 'PUT',
    headers: ticket.headers,
    body: file.body,
  });
  if (!putResponse.ok) {
    throw new Error('Upload failed');
  }
  await requireOk(
    await apiRequest(config, `/uploads/${ticket.id}/complete`, {
      method: 'POST',
    }),
  );
  return ticket.id;
}
