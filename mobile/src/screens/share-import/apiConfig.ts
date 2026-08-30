import type {AvatarApiConfig} from '../../features/my-model/types';

export type ShareImportApiConfig = AvatarApiConfig;

// Same convention as avatarApi/generationApi: unauthenticated local default;
// the auth boundary attaches the token provider in the running app.
export const apiConfig: ShareImportApiConfig = {
  baseUrl: '',
  getAccessToken: () => null,
};
