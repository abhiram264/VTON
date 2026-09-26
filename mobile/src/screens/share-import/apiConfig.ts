import type {AvatarApiConfig} from '../../features/my-model/types';

export type ShareImportApiConfig = AvatarApiConfig;

// The mounted default must fail before any relative or bearer-less request.
// M06 owns replacing this with the live configured authenticated session.
export const apiConfig: ShareImportApiConfig = {
  baseUrl: '',
  getAccessToken: () => {
    throw new Error('Share import API is not configured');
  },
};
