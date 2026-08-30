package com.aifashionvault.generation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationStoreContractTest {

	private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

	@Test
	void processorFacingRetrySeamsAreSourceCompatibleDefaults() {
		GenerationStore store = new UnsupportedStore();

		assertUnsupported(
			() -> store.scheduleRetryIfActive(null, NOW),
			"scheduleRetryIfActive");
		assertUnsupported(
			() -> store.scheduleRetryIfActive(null, 1, NOW),
			"scheduleRetryIfActive");
		assertUnsupported(
			() -> store.claimDueRetry(null, 1, NOW, null),
			"claimDueRetry");
		assertUnsupported(
			() -> store.listDueRetrying(NOW),
			"listDueRetrying");
		assertUnsupported(
			() -> store.tryRecordTransmission(null, NOW),
			"tryRecordTransmission");
		assertUnsupported(
			() -> store.advanceStepIfActive(null, 1, 1),
			"advanceStepIfActive");
		assertUnsupported(
			() -> store.resolveRetryPolicy(null),
			"resolveRetryPolicy");
	}

	@Test
	void singleItemCreateSeamIsSourceCompatibleDefault() {
		GenerationStore store = new UnsupportedStore();
		assertUnsupported(() -> store.createSingleItem(null, null, null), "createSingleItem");
		assertUnsupported(() -> store.findFrozenSingleItemIntent(null), "findFrozenSingleItemIntent");
		assertUnsupported(() -> store.findProductSourceUploadId(null, null), "findProductSourceUploadId");
	}

	@Test
	void qualityGateStoreSeamsAreSourceCompatibleDefaults() {
		GenerationStore store = new UnsupportedStore();

		assertUnsupported(
			() -> store.findQualityResult(null),
			"findQualityResult");
		assertUnsupported(
			() -> store.markQualityCheckIfActive(null, 1),
			"markQualityCheckIfActive");
	}

	private static void assertUnsupported(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String name) {
		assertThatThrownBy(operation)
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessage(name);
	}

	private static final class UnsupportedStore implements GenerationStore {

		@Override
		public void insertJob(GenerationJob job) {
		}

		@Override
		public Optional<GenerationJob> findJob(GenerationJobId jobId) {
			return Optional.empty();
		}

		@Override
		public void insertAttempt(GenerationAttempt attempt) {
		}

		@Override
		public Optional<GenerationAttempt> findAttempt(GenerationAttemptId attemptId) {
			return Optional.empty();
		}

		@Override
		public List<GenerationAttempt> listAttempts(GenerationJobId jobId) {
			return List.of();
		}

		@Override
		public boolean tryAcquireSubmitOwnership(GenerationAttemptId attemptId, Instant ownedAt) {
			return false;
		}

		@Override
		public void recordProviderExecution(GenerationAttemptId attemptId, ProviderJobId providerJobId) {
		}

		@Override
		public void markSubmissionIndeterminate(GenerationAttemptId attemptId) {
		}

		@Override
		public void saveQualityResult(QualityResult qualityResult) {
		}

		@Override
		public void markCancelled(GenerationJobId jobId, Instant cancelledAt) {
		}

		@Override
		public void markSubjectDeleted(GenerationJobId jobId, Instant subjectDeletedAt) {
		}

		@Override
		public void completeAttempt(GenerationAttempt attempt) {
		}

		@Override
		public void markJobCompleted(GenerationJobId jobId, Instant completedAt) {
		}
	}
}
