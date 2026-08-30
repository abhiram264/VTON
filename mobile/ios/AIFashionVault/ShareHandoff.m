// Registers the Swift ShareHandoff module with React Native under exactly
// 'ShareHandoff' (legacy interop — same registration approach as the Android
// module; RN 0.86 bridgeless exposes it through the TurboModule interop layer).
#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(ShareHandoff, NSObject)

RCT_EXTERN_METHOD(consumeShare:(NSString *)shareId
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(releaseStagedImage:(NSString *)localImageUri
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

@end
