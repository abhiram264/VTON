package com.aifashionvault.app.photopicker

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * Registers [PhotoPickerModule] under the New Architecture, mirroring
 * [com.aifashionvault.app.share.ShareHandoffPackage]: a hand-built [ReactModuleInfo] map
 * (no codegen) with isTurboModule=false, so the module resolves through legacy interop on
 * bridgeless.
 */
class PhotoPickerPackage : BaseReactPackage() {

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
      if (name == PhotoPickerModule.MODULE_NAME) {
        PhotoPickerModule(reactContext)
      } else {
        null
      }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
      ReactModuleInfoProvider {
        mapOf(
            PhotoPickerModule.MODULE_NAME to
                ReactModuleInfo(
                    PhotoPickerModule.MODULE_NAME,
                    PhotoPickerModule::class.java.name,
                    /* canOverrideExistingModule = */ false,
                    /* needsEagerInit = */ false,
                    /* isCxxModule = */ false,
                    /* isTurboModule = */ false,
                ))
      }
}
