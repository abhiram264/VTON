package com.aifashionvault.platform.queue;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

public final class SqsGenerationWorkQueue implements GenerationWorkQueue {

	private static final Logger log = LoggerFactory.getLogger(SqsGenerationWorkQueue.class);

	public static final int VISIBILITY_TIMEOUT_SECONDS = 120;
	public static final int HEARTBEAT_INTERVAL_SECONDS = 45;
	public static final int LONG_POLL_SECONDS = 20;
	public static final int RECEIVE_BATCH_SIZE = 1;
	public static final int MAIN_RETENTION_SECONDS = 345600;
	public static final int DLQ_RETENTION_SECONDS = 1209600;
	/**
	 * Transport poison threshold for the generation queue (Q41). DLQ redrive
	 * consumes no business retry budget; redrive must reload database truth and
	 * respect retry deadlines.
	 */
	public static final int MAX_RECEIVE_COUNT = 5;

	private final SqsClient sqsClient;
	private final String queueUrl;
	private final AtomicLong poisonMessageCount = new AtomicLong();

	public SqsGenerationWorkQueue(SqsClient sqsClient, VaultEnvironmentProperties properties) {
		this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient");
		this.queueUrl = Objects.requireNonNull(properties, "properties")
			.sqs()
			.generationQueueUrl();
		Objects.requireNonNull(this.queueUrl, "SQS_GENERATION_QUEUE_URL");
	}

	@Override
	public void send(UUID generationJobId) {
		Objects.requireNonNull(generationJobId, "generationJobId");
		sqsClient.sendMessage(SendMessageRequest.builder()
			.queueUrl(queueUrl)
			.messageBody(generationJobId.toString())
			.build());
	}

	@Override
	public Optional<GenerationWorkLease> receive() {
		ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
			.queueUrl(queueUrl)
			.maxNumberOfMessages(RECEIVE_BATCH_SIZE)
			.waitTimeSeconds(LONG_POLL_SECONDS)
			.build());
		List<Message> messages = response.messages();
		if (messages.isEmpty()) {
			return Optional.empty();
		}
		Message message = messages.getFirst();
		UUID generationJobId = parseGenerationJobId(message.body());
		if (generationJobId == null) {
			discardPoison(message.receiptHandle());
			return Optional.empty();
		}
		return Optional.of(new GenerationWorkLease(generationJobId, message.receiptHandle()));
	}

	@Override
	public void extendVisibility(GenerationWorkLease lease, Duration extension) {
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(extension, "extension");
		if (extension.isNegative()) {
			throw new IllegalArgumentException("extension must not be negative");
		}
		long seconds = extension.toSeconds();
		if (seconds > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("extension exceeds SQS visibility timeout");
		}
		sqsClient.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
			.queueUrl(queueUrl)
			.receiptHandle(lease.receiptHandle())
			.visibilityTimeout((int) seconds)
			.build());
	}

	@Override
	public void delete(GenerationWorkLease lease) {
		Objects.requireNonNull(lease, "lease");
		sqsClient.deleteMessage(DeleteMessageRequest.builder()
			.queueUrl(queueUrl)
			.receiptHandle(lease.receiptHandle())
			.build());
	}

	long poisonMessageCount() {
		return poisonMessageCount.get();
	}

	private UUID parseGenerationJobId(String body) {
		if (body == null) {
			return null;
		}
		try {
			return UUID.fromString(body.strip());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private void discardPoison(String receiptHandle) {
		poisonMessageCount.incrementAndGet();
		log.info("generation code=POISON_MESSAGE");
		if (receiptHandle == null || receiptHandle.isBlank()) {
			return;
		}
		sqsClient.deleteMessage(DeleteMessageRequest.builder()
			.queueUrl(queueUrl)
			.receiptHandle(receiptHandle)
			.build());
	}
}
