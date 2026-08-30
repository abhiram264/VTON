export type LocalPhoto = {
  uri: string;
};

export type AvatarResource = {
  id: string;
  imageId: string;
  revision: number;
  width: number;
  height: number;
  mediaType: string;
  displayUrl: string;
  displayExpiresAt: string;
  warnings: string[];
};

export type AvatarApiConfig = {
  baseUrl: string;
  getAccessToken: () => Promise<string | null> | string | null;
};

export type AvatarApi = {
  list(): Promise<AvatarResource[]>;
  create(photo: LocalPhoto): Promise<AvatarResource>;
  replace(
    id: string,
    revision: number,
    photo: LocalPhoto,
  ): Promise<AvatarResource>;
  remove(id: string, revision: number): Promise<void>;
};
