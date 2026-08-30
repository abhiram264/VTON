package com.aifashionvault.platform.generation;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.InputSizeLimit;
import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.ModeCapabilities;
import com.aifashionvault.generation.vton.OutputDeliveryMethod;
import com.aifashionvault.generation.vton.ProviderCapabilities;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProvider;
import com.aifashionvault.generation.vton.VtonRequest;

public final class ReplaySafeFakeVtonProvider implements VtonProvider {

	private final ProviderKey providerKey;
	private final AtomicInteger submitCount = new AtomicInteger();
	private final AtomicInteger getStatusCount = new AtomicInteger();
	private final AtomicInteger openOutputCount = new AtomicInteger();
	private final List<SubmissionKey> submittedKeys = new CopyOnWriteArrayList<>();
	private final Map<SubmissionKey, ProviderJobId> remoteIds = new ConcurrentHashMap<>();

	public ReplaySafeFakeVtonProvider() {
		this(new ProviderKey("replay-safe"));
	}

	public ReplaySafeFakeVtonProvider(ProviderKey providerKey) {
		this.providerKey = providerKey;
	}

	@Override
	public ProviderKey providerKey() {
		return providerKey;
	}

	@Override
	public ProviderCapabilities capabilities() {
		return new ProviderCapabilities(
			Map.of(
				new VtonMode("try-on"),
				new ModeCapabilities(
					Set.of(new ItemCategory("garment")),
					4,
					false,
					InputSizeLimit.unknown())),
			Set.of(InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ),
			Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
			SubmissionReplaySafety.SAME_KEY_SAFE);
	}

	@Override
	public GenerationSubmission submit(VtonRequest request, SubmissionKey submissionKey) {
		submitCount.incrementAndGet();
		submittedKeys.add(submissionKey);
		ProviderJobId existing = remoteIds.get(submissionKey);
		if (existing != null) {
			return new GenerationSubmission.Accepted(existing, Optional.empty());
		}
		ProviderJobId created = new ProviderJobId("stable-" + submissionKey.value());
		remoteIds.put(submissionKey, created);
		if (submitCount.get() == 1) {
			return new GenerationSubmission.Indeterminate(RecordingVtonProvider.indeterminateFailure());
		}
		return new GenerationSubmission.Accepted(created, Optional.empty());
	}

	@Override
	public StatusQueryResult getStatus(ProviderJobId providerJobId) {
		getStatusCount.incrementAndGet();
		return new StatusQueryResult.Observed(
			new GenerationStatus.Succeeded(List.of(new ProviderOutputRef("replay-output-1")), Optional.empty()));
	}

	@Override
	public ProviderMediaSource openOutput(ProviderOutputRef outputRef) {
		openOutputCount.incrementAndGet();
		return ProviderMediaSource.of(
			new ByteArrayInputStream(RecordingVtonProvider.DEFAULT_OUTPUT),
			OptionalLong.of(RecordingVtonProvider.DEFAULT_OUTPUT.length),
			1024L,
			"image/jpeg");
	}

	public int submitCount() {
		return submitCount.get();
	}

	public int getStatusCount() {
		return getStatusCount.get();
	}

	public List<SubmissionKey> submittedKeys() {
		return List.copyOf(submittedKeys);
	}

	public ProviderJobId remoteId(SubmissionKey key) {
		return remoteIds.get(key);
	}
}
