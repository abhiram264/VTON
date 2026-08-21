package com.aifashionvault.platform.queue;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

public final class SqsGenerationWorkQueue implements GenerationWorkQueue {

	private static final int LONG_POLL_SECONDS = 20;
	private static final int MAX_MESSAGES = 1;

	private final SqsClient sqsClient;
	private final String queueUrl;

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
			.maxNumberOfMessages(MAX_MESSAGES)
			.waitTimeSeconds(LONG_POLL_SECONDS)
			.build());
		List<Message> messages = response.messages();
		if (messages.isEmpty()) {
			return Optional.empty();
		}
		Message message = messages.getFirst();
		return Optional.of(new GenerationWorkLease(
			UUID.fromString(message.body().strip()),
			message.receiptHandle()));
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
}
