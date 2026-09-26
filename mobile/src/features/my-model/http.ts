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
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    return await fetch(`${config.baseUrl.replace(/\/+$/, '')}${path}`, {
      method: init.method,
      headers,
      body: init.body,
      signal: controller.signal,
    });
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error(`Request timed out after ${REQUEST_TIMEOUT_MS}ms: ${path}`);
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

async function extractProblemCode(response: Response): Promise<string | null> {
  try {
    const contentType = response.headers?.get?.('content-type') ?? '';
    if (contentType && !contentType.includes('application/json') && !contentType.includes('+json')) {
      return null;
    }
    const payload = await response.json();
    if (payload !== null && typeof payload === 'object' && 'code' in payload) {
      const code = payload.code;
      if (typeof code === 'string') {
        return code;
      }
    }
  } catch {
    // Body is empty or unparsable non-JSON
  }
  return null;
}

export async function requireOk(response: Response): Promise<unknown> {
  if (response.ok) {
    if (response.status === 204) {
      return null;
    }
    return response.json();
  }

  const problemCode = await extractProblemCode(response);
  if (problemCode) {
    throw new ApiProblemError(problemCode);
  }

  if (response.status === 401) {
    throw new ApiProblemError('UNAUTHORIZED');
  }

  throw new ApiProblemError(`HTTP_${response.status}`);
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
