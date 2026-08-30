package com.aifashionvault.platform.generation;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.ProviderUsage;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProvider;
import com.aifashionvault.generation.vton.VtonRequest;

public final class RecordingVtonProvider implements VtonProvider {

	static final byte[] DEFAULT_OUTPUT = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x10, 0x11, 0x12, 0x13};

	private final ProviderKey providerKey;
	private final AtomicInteger submitCount = new AtomicInteger();
	private final AtomicInteger getStatusCount = new AtomicInteger();
	private final AtomicInteger openOutputCount = new AtomicInteger();
	private final List<SubmissionKey> submittedKeys = new CopyOnWriteArrayList<>();
	private final List<VtonRequest> submittedRequests = new CopyOnWriteArrayList<>();

	private volatile GenerationSubmission submitResult = new GenerationSubmission.Accepted(
		new ProviderJobId("provider-job-1"),
		Optional.empty());
	private volatile StatusQueryResult statusResult = new StatusQueryResult.Observed(
		new GenerationStatus.Succeeded(List.of(new ProviderOutputRef("output-1")), Optional.empty()));
	private volatile byte[] outputBytes = DEFAULT_OUTPUT;
	private volatile String outputMediaType = "image/jpeg";
	private volatile long outputMaxBytes = 1024L;
	private volatile RuntimeException submitException;
	private volatile RuntimeException openOutputException;
	private volatile Runnable beforeSubmit = () -> {
	};
	private volatile Runnable beforeGetStatus = () -> {
	};

	public RecordingVtonProvider() {
		this(new ProviderKey("recording"));
	}

	public RecordingVtonProvider(ProviderKey providerKey) {
		this.providerKey = providerKey;
	}

	@Override
	public ProviderKey providerKey() {
		return providerKey;
	}

	@Override
	public ProviderCapabilities capabilities() {
		return unsafeCapabilities();
	}

	@Override
	public GenerationSubmission submit(VtonRequest request, SubmissionKey submissionKey) {
		submitCount.incrementAndGet();
		submittedKeys.add(submissionKey);
		submittedRequests.add(request);
		beforeSubmit.run();
		RuntimeException thrown = submitException;
		if (thrown != null) {
			throw thrown;
		}
		return submitResult;
	}

	@Override
	public StatusQueryResult getStatus(ProviderJobId providerJobId) {
		getStatusCount.incrementAndGet();
		beforeGetStatus.run();
		return statusResult;
	}

	@Override
	public ProviderMediaSource openOutput(ProviderOutputRef outputRef) {
		openOutputCount.incrementAndGet();
		RuntimeException thrown = openOutputException;
		if (thrown != null) {
			throw thrown;
		}
		byte[] payload = outputBytes == null ? new byte[0] : outputBytes;
		return ProviderMediaSource.of(
			new ByteArrayInputStream(payload),
			OptionalLong.of(payload.length),
			outputMaxBytes,
			outputMediaType);
	}

	public int submitCount() {
		return submitCount.get();
	}

	public int getStatusCount() {
		return getStatusCount.get();
	}

	public int openOutputCount() {
		return openOutputCount.get();
	}

	public List<SubmissionKey> submittedKeys() {
		return List.copyOf(submittedKeys);
	}

	public List<VtonRequest> submittedRequests() {
		return List.copyOf(submittedRequests);
	}

	public void submitResult(GenerationSubmission result) {
		this.submitResult = result;
	}

	public void statusResult(StatusQueryResult result) {
		this.statusResult = result;
	}

	public void outputBytes(byte[] bytes) {
		this.outputBytes = bytes;
	}

	public void outputMediaType(String mediaType) {
		this.outputMediaType = mediaType;
	}

	public void outputMaxBytes(long maxBytes) {
		this.outputMaxBytes = maxBytes;
	}

	public void submitException(RuntimeException exception) {
		this.submitException = exception;
	}

	public void openOutputException(RuntimeException exception) {
		this.openOutputException = exception;
	}

	public void beforeSubmit(Runnable action) {
		this.beforeSubmit = action == null ? () -> {
		} : action;
	}

	public void beforeGetStatus(Runnable action) {
		this.beforeGetStatus = action == null ? () -> {
		} : action;
	}

	public void usage(ProviderUsage usage) {
		Optional<ProviderUsage> mapped = usage == null ? Optional.empty() : Optional.of(usage);
		if (submitResult instanceof GenerationSubmission.Accepted accepted) {
			submitResult = new GenerationSubmission.Accepted(accepted.providerJobId(), mapped);
		}
		if (statusResult instanceof StatusQueryResult.Observed observed
			&& observed.status() instanceof GenerationStatus.Succeeded succeeded) {
			statusResult = new StatusQueryResult.Observed(new GenerationStatus.Succeeded(succeeded.outputs(), mapped));
		}
		if (statusResult instanceof StatusQueryResult.Observed observed
			&& observed.status() instanceof GenerationStatus.Failed failed) {
			statusResult = new StatusQueryResult.Observed(new GenerationStatus.Failed(failed.failure(), mapped));
		}
	}

	static ProviderCapabilities unsafeCapabilities() {
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
			SubmissionReplaySafety.UNSAFE);
	}

	static ProviderFailure retryableReject() {
		return new ProviderFailure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "PROVIDER_TEMPORARY");
	}

	static ProviderFailure indeterminateFailure() {
		return new ProviderFailure(ProviderFailureCategory.TIMEOUT_NETWORK, true, "PROVIDER_INDETERMINATE");
	}
}
