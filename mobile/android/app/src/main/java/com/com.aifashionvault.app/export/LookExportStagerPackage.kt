package com.aifashionvault.app.export

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * Registers [LookExportStagerModule] under New Architecture. Follows the same hand-built
 * [ReactModuleInfo] fallback pattern as the share package: no codegen, `isTurboModule=false`,
 * so the module resolves through legacy interop on bridgeless.
 */
class LookExportStagerPackage : BaseReactPackage() {

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
      if (name == LookExportStagerModule.MODULE_NAME) {
        LookExportStagerModule(reactContext)
      } else {
        null
      }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
      ReactModuleInfoProvider {
        mapOf(
            LookExportStagerModule.MODULE_NAME to
                ReactModuleInfo(
                    LookExportStagerModule.MODULE_NAME,
                    LookExportStagerModule::class.java.name,
                    /* canOverrideExistingModule = */ false,
                    /* needsEagerInit = */ false,
                    /* isCxxModule = */ false,
                    /* isTurboModule = */ false,
                ))
      }
}
