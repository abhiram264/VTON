package com.aifashionvault.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle repair decisions, extracted as pure logic so they are JVM-testable without an
 * Activity harness.
 * HIGH finding: restored unfinished work must fail closed rather than duplicate staging, while
 * restored completed work must finish without replaying the handoff.
 *
 * HIGH finding (orphaned-instance half): terminal UI steps on a destroyed/finishing
 * instance must be suppressed — no handoff launch, no toast, no finish crash.
 */
class ShareReceiverLifecycleTest {

  // --- restored work ---

  @Test
  fun `fresh delivery with no restored work processes the intent`() {
    assertTrue(ShareReceiverLifecycle.shouldProcessIntent(restoredWork = null))
  }

  @Test
  fun `restored unfinished work is rejected with the safe failure`() {
    val restoredWork = ShareReceiverLifecycle.WorkState.UNFINISHED

    assertFalse(ShareReceiverLifecycle.shouldProcessIntent(restoredWork))
    assertTrue(ShareReceiverLifecycle.shouldFailRestoredWork(restoredWork))
  }

  @Test
  fun `restored completed work finishes without failure or replay`() {
    val restoredWork = ShareReceiverLifecycle.WorkState.COMPLETED

    assertFalse(ShareReceiverLifecycle.shouldProcessIntent(restoredWork))
    assertFalse(ShareReceiverLifecycle.shouldFailRestoredWork(restoredWork))
  }

  @Test
  fun `second intent is explicitly rejected while the first is staging`() {
    assertFalse(ShareReceiverLifecycle.shouldAcceptNewIntent(isStaging = true))
    assertTrue(ShareReceiverLifecycle.shouldAcceptNewIntent(isStaging = false))
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
