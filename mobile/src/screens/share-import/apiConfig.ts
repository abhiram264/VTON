import type {AvatarApiConfig} from '../../features/my-model/types';
import {productionApiConfig} from '../../config/apiConfig';

export type ShareImportApiConfig = AvatarApiConfig;

// The share-import screen must not own a second API seam: it delegates to the
// single shared module, which stays fail-closed (unset EXPO_PUBLIC_API_URL and
// the absent session store both throw). A local stub here would drift.
export const apiConfig: ShareImportApiConfig = productionApiConfig;
