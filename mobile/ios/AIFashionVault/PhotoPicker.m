// Registers the Swift PhotoPicker module with React Native under exactly
// 'PhotoPicker' (legacy interop — the same registration approach as
// ShareHandoff.m; works bridgeless through RN's TurboModule interop layer).
// Consumed by mobile/src/features/my-model/photoSource.ts.
#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(PhotoPicker, NSObject)

RCT_EXTERN_METHOD(takePhoto:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(choosePhoto:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

@end
