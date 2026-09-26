import { NativeModules } from 'react-native';
import {
  choosePhoto,
  isCameraPermissionDenied,
  takePhoto,
} from '../src/features/my-model/photoSource';

const nativeModules = NativeModules as unknown as Record<string, unknown>;

function setPhotoPicker(module: unknown): void {
  nativeModules.PhotoPicker = module;
}

function clearPhotoPicker(): void {
  delete nativeModules.PhotoPicker;
}

function photoPickerDouble(result: unknown): void {
  setPhotoPicker({
    takePhoto: jest.fn().mockResolvedValue(result),
    choosePhoto: jest.fn().mockResolvedValue(result),
  });
}

afterEach(clearPhotoPicker);

describe('photo source picker boundary', () => {
  it('reports a cancelled pick as no selection, never a bogus photo or a throw', async () => {
    photoPickerDouble({ cancelled: true });

    await expect(choosePhoto()).resolves.toBeNull();
    await expect(takePhoto()).resolves.toBeNull();
  });

  it('resolves a real pick to the LocalPhoto the caller uploads', async () => {
    photoPickerDouble({ cancelled: false, uri: 'content://media/1' });

    await expect(choosePhoto()).resolves.toEqual({
      uri: 'content://media/1',
    });
  });

  it('surfaces a denied camera permission as a recoverable error', async () => {
    photoPickerDouble({ cancelled: false, permissionDenied: true });

    const error = await takePhoto().then(
      () => null,
      (thrown: unknown) => thrown,
    );

    expect(error).toBeInstanceOf(Error);
    expect(isCameraPermissionDenied(error)).toBe(true);
  });

  it('throws when no picker bridge is registered instead of reporting a cancellation', async () => {
    await expect(choosePhoto()).rejects.toThrow();
  });
});
