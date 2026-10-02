import type { LocalPhoto } from '../my-model/types';

export type GenerationStatus =
  | 'QUEUED'
  | 'PREFLIGHT'
  | 'SUBMITTED'
  | 'GENERATING'
  | 'QUALITY_CHECK'
  | 'RETRYING'
  | 'COMPLETED'
  | 'FAILED';

export type CoarseGenerationPhase =
  | 'QUEUED'
  | 'PREPARING'
  | 'GENERATING'
  | 'CHECKING_QUALITY'
  | 'READY'
  | 'FAILED';

export type GenerationResource = {
  id: string;
  status: GenerationStatus;
  phase: CoarseGenerationPhase;
  statusVersion: number;
  createdAt: string;
  updatedAt: string;
  lookId?: string;
  failureCode?: string;
};

export type LookResource = {
  id: string;
  generationId: string;
  createdAt: string;
  image: { url: string; expiresAt: string };
};

export type GenerationApiConfig = {
  baseUrl: string;
  getAccessToken: () => Promise<string | null> | string | null;
};

export type GenerationApi = {
  create(
    productUploadId: string,
    idempotencyKey: string,
  ): Promise<GenerationResource>;
  get(id: string): Promise<GenerationResource>;
  getLook(id: string): Promise<LookResource>;
  /**
   * Owner-scoped recovery surface: the caller's recent generations, newest
   * first. This is the durable source of truth for in-flight/recent state, so
   * a process restart can rebuild it without relying on a process-local
   * variable that died with the process.
   */
  list(): Promise<GenerationResource[]>;
  /** Requests cancellation. The response reports the state the server reached. */
  cancel(id: string): Promise<GenerationResource>;
};

// Null return means the user cancelled the picker. Injectable so flows are
// testable and runnable without a device picker dependency.
export type ProductPicker = () => Promise<LocalPhoto | null>;

// Resolves the completed product upload ID. Injectable for the same reason.
export type ProductUploader = (photo: LocalPhoto) => Promise<string>;
