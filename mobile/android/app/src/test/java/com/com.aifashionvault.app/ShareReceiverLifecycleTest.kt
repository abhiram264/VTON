package com.aifashionvault.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle repair decisions, extracted as pure logic so they are JVM-testable without an
 * Activity harness.
 *
 * HIGH finding: a config-change recreation of an already-handled SEND intent must not
 * re-process it (no double store, no double handoff launch); only a fresh delivery may.
 *
 * HIGH finding (orphaned-instance half): terminal UI steps on a destroyed/finishing
 * instance must be suppressed — no handoff launch, no toast, no finish crash.
 */
class ShareReceiverLifecycleTest {

  // --- shouldProcessIntent ---

  @Test
  fun `fresh delivery with null saved state processes the intent`() {
    assertTrue(ShareReceiverLifecycle.shouldProcessIntent(hasSavedInstanceState = false))
  }

  @Test
  fun `recreation with saved state does not re-process the already-handled intent`() {
    assertFalse(ShareReceiverLifecycle.shouldProcessIntent(hasSavedInstanceState = true))
  }

  // --- terminalStepSuppressed ---

  @Test
  fun `live instance runs terminal steps`() {
    assertFalse(ShareReceiverLifecycle.terminalStepSuppressed(isFinishing = false, isDestroyed = false))
  }

  @Test
  fun `finishing instance suppresses terminal steps`() {
    assertTrue(ShareReceiverLifecycle.terminalStepSuppressed(isFinishing = true, isDestroyed = false))
  }

  @Test
  fun `destroyed instance suppresses terminal steps`() {
    assertTrue(ShareReceiverLifecycle.terminalStepSuppressed(isFinishing = false, isDestroyed = true))
  }

  @Test
  fun `finishing and destroyed instance suppresses terminal steps`() {
    assertTrue(ShareReceiverLifecycle.terminalStepSuppressed(isFinishing = true, isDestroyed = true))
  }
}
