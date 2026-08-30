package com.aifashionvault.app.share

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * Registers [ShareHandoffNativeModule] under New Architecture. Follows RN's own
 * MainReactPackage fallback pattern: a hand-built [ReactModuleInfo] map (no codegen) with
 * isTurboModule=false, so the module resolves through legacy interop on bridgeless.
 */
class ShareHandoffPackage : BaseReactPackage() {

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
      if (name == ShareHandoffNativeModule.MODULE_NAME) {
        ShareHandoffNativeModule(reactContext)
      } else {
        null
      }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
      ReactModuleInfoProvider {
        mapOf(
            ShareHandoffNativeModule.MODULE_NAME to
                ReactModuleInfo(
                    ShareHandoffNativeModule.MODULE_NAME,
                    ShareHandoffNativeModule::class.java.name,
                    /* canOverrideExistingModule = */ false,
                    /* needsEagerInit = */ false,
                    /* isCxxModule = */ false,
                    /* isTurboModule = */ false,
                ))
      }
}
