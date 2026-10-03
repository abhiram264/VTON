package com.aifashionvault.platform.queue;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsGenerationWorkQueueTest {

	private static final String QUEUE_URL =
		"http://127.0.0.1:4566/000000000000/ai-fashion-vault-local-generation-work";
	private static final String STAGING_QUEUE_URL =
		"https://sqs.us-east-1.amazonaws.com/123456789012/ai-fashion-vault-staging-generation-work";
	private static final UUID JOB_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
	private static final String RECEIPT_HANDLE = "rh-abc";

	private SqsClient sqsClient;
	private SqsGenerationWorkQueue queue;

	@BeforeEach
	void setUp() {
		sqsClient = mock(SqsClient.class);
		queue = new SqsGenerationWorkQueue(sqsClient, properties(AppEnv.development, QUEUE_URL));
	}

	@Test
	void sendWritesRawUuidBodyWithNoAttributes() {
		queue.send(JOB_ID);

		SendMessageRequest request = captureSend();
		assertThat(request.messageBody()).isEqualTo(JOB_ID.toString());
		assertThat(request.messageBody()).doesNotContain("{").doesNotContain(":");
		assertThat(request.messageAttributes()).isNullOrEmpty();
		assertThat(request.messageSystemAttributes()).isNullOrEmpty();
		assertThat(request.messageGroupId()).isNull();
	}

	@Test
	void sendUsesExplicitConfiguredQueueUrl() {
		queue.send(JOB_ID);

		assertThat(captureSend().queueUrl()).isEqualTo(QUEUE_URL);
		verifyNeverAdministersQueues();
	}

	@Test
	void sendDoesNotDeriveQueueIdentityFromAppEnv() {
		SqsGenerationWorkQueue productionQueue = new SqsGenerationWorkQueue(
			sqsClient,
			properties(AppEnv.production, STAGING_QUEUE_URL));

		productionQueue.send(JOB_ID);

		assertThat(captureSend().queueUrl()).isEqualTo(STAGING_QUEUE_URL);
		verify(sqsClient, never()).getQueueUrl(any(GetQueueUrlRequest.class));
	}

	@Test
	void receiveUsesExplicitConfiguredQueueUrlAndLongPoll() {
		when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
			.thenReturn(ReceiveMessageResponse.builder().build());

		assertThat(queue.receive()).isEmpty();

		ReceiveMessageRequest request = captureReceive();
		assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
		assertThat(request.maxNumberOfMessages()).isEqualTo(1);
		assertThat(request.waitTimeSeconds()).isEqualTo(20);
		assertThat(request.messageSystemAttributeNames()).isNullOrEmpty();
		verifyNeverAdministersQueues();
	}

	@Test
	void receiveReturnsLeaseWithoutSqsReceiveCount() {
		when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
			.thenReturn(messageResponse(JOB_ID, RECEIPT_HANDLE, "7"));

		GenerationWorkLease lease = queue.receive().orElseThrow();

		assertThat(lease.generationJobId()).isEqualTo(JOB_ID);
		assertThat(lease.receiptHandle()).isEqualTo(RECEIPT_HANDLE);
		assertThat(Arrays.stream(GenerationWorkLease.class.getRecordComponents())
			.map(RecordComponent::getName))
			.containsExactly("generationJobId", "receiptHandle");
	}

	@Test
	void receiveDeletesNonUuidPoisonWithoutReturningLease() {
		when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
			.thenReturn(ReceiveMessageResponse.builder()
				.messages(Message.builder()
					.body("not-a-uuid")
					.receiptHandle(RECEIPT_HANDLE)
					.build())
				.build());

		assertThat(queue.receive()).isEmpty();

		ArgumentCaptor<DeleteMessageRequest> captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
		verify(sqsClient).deleteMessage(captor.capture());
		assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE_URL);
		assertThat(captor.getValue().receiptHandle()).isEqualTo(RECEIPT_HANDLE);
		assertThat(queue.poisonMessageCount()).isEqualTo(1);
	}

	@Test
	void feature19QueueDefaultsAreAligned() {
		assertThat(SqsGenerationWorkQueue.VISIBILITY_TIMEOUT_SECONDS).isEqualTo(120);
		assertThat(SqsGenerationWorkQueue.HEARTBEAT_INTERVAL_SECONDS).isEqualTo(45);
		assertThat(SqsGenerationWorkQueue.LONG_POLL_SECONDS).isEqualTo(20);
		assertThat(SqsGenerationWorkQueue.RECEIVE_BATCH_SIZE).isEqualTo(1);
		assertThat(SqsGenerationWorkQueue.MAIN_RETENTION_SECONDS).isEqualTo(345600);
		assertThat(SqsGenerationWorkQueue.DLQ_RETENTION_SECONDS).isEqualTo(1209600);
		assertThat(SqsGenerationWorkQueue.MAX_RECEIVE_COUNT).isEqualTo(5);
	}

	@Test
	void apiDoesNotExposeBusinessRetryCountFromSqsReceiveCount() {
		assertThat(Arrays.stream(GenerationWorkQueue.class.getDeclaredMethods()).map(Method::getName))
			.containsExactlyInAnyOrder("send", "receive", "extendVisibility", "delete");
		for (Method method : GenerationWorkQueue.class.getDeclaredMethods()) {
			assertThat(method.getName().toLowerCase()).doesNotContain("receivecount", "retry", "attempt");
			assertThat(method.getReturnType()).isNotEqualTo(int.class).isNotEqualTo(Integer.class);
			for (Class<?> parameterType : method.getParameterTypes()) {
				assertThat(parameterType).isNotEqualTo(int.class).isNotEqualTo(Integer.class);
			}
		}
	}

	@Test
	void extendVisibilityUsesReceiptHandleAndQueueUrl() {
		GenerationWorkLease lease = new GenerationWorkLease(JOB_ID, RECEIPT_HANDLE);

		queue.extendVisibility(lease, Duration.ofSeconds(90));

		ArgumentCaptor<ChangeMessageVisibilityRequest> captor =
			ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
		verify(sqsClient).changeMessageVisibility(captor.capture());
		ChangeMessageVisibilityRequest request = captor.getValue();
		assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
		assertThat(request.receiptHandle()).isEqualTo(RECEIPT_HANDLE);
		assertThat(request.visibilityTimeout()).isEqualTo(90);
		verifyNeverAdministersQueues();
	}

	@Test
	void deleteUsesReceiptHandleAndQueueUrl() {
		GenerationWorkLease lease = new GenerationWorkLease(JOB_ID, RECEIPT_HANDLE);

		queue.delete(lease);

		ArgumentCaptor<DeleteMessageRequest> captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
		verify(sqsClient).deleteMessage(captor.capture());
		DeleteMessageRequest request = captor.getValue();
		assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
		assertThat(request.receiptHandle()).isEqualTo(RECEIPT_HANDLE);
		verifyNeverAdministersQueues();
	}

	@Test
	void duplicateSqsDeliveryCreatesZeroDuplicateHandlerSideEffects() {
		when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(
			messageResponse(JOB_ID, "rh-1", "1"),
			messageResponse(JOB_ID, "rh-2", "2"));
		InMemoryGenerationWorkClaimStore claims = new InMemoryGenerationWorkClaimStore();
		AtomicInteger sideEffects = new AtomicInteger();

		for (int i = 0; i < 2; i++) {
			Optional<GenerationWorkLease> received = queue.receive();
			assertThat(received).isPresent();
			GenerationWorkLease lease = received.get();
			if (claims.tryClaim(lease.generationJobId())) {
				sideEffects.incrementAndGet();
				claims.complete(lease.generationJobId());
			}
			queue.delete(lease);
		}

		assertThat(sideEffects).hasValue(1);
		assertThat(claims.tryClaim(JOB_ID)).isFalse();
		ArgumentCaptor<DeleteMessageRequest> captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
		verify(sqsClient, times(2)).deleteMessage(captor.capture());
		assertThat(captor.getAllValues())
			.extracting(DeleteMessageRequest::receiptHandle)
			.containsExactly("rh-1", "rh-2");
	}

	private SendMessageRequest captureSend() {
		ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
		verify(sqsClient).sendMessage(captor.capture());
		return captor.getValue();
	}

	private ReceiveMessageRequest captureReceive() {
		ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
		verify(sqsClient).receiveMessage(captor.capture());
		return captor.getValue();
	}

	private void verifyNeverAdministersQueues() {
		verify(sqsClient, never()).createQueue(any(CreateQueueRequest.class));
		verify(sqsClient, never()).deleteQueue(any(DeleteQueueRequest.class));
		verify(sqsClient, never()).purgeQueue(any(PurgeQueueRequest.class));
		verify(sqsClient, never()).getQueueUrl(any(GetQueueUrlRequest.class));
		verify(sqsClient, never()).setQueueAttributes(any(SetQueueAttributesRequest.class));
	}

	private static ReceiveMessageResponse messageResponse(UUID jobId, String receiptHandle, String receiveCount) {
		return ReceiveMessageResponse.builder()
			.messages(Message.builder()
				.body(jobId.toString())
				.receiptHandle(receiptHandle)
				.attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, receiveCount))
				.build())
			.build();
	}

	private static VaultEnvironmentProperties properties(AppEnv env, String queueUrl) {
		return new VaultEnvironmentProperties(
			env,
			null,
			null,
			new VaultEnvironmentProperties.Sqs(queueUrl),
			null,
			null,
			null,
			null,
			null);
	}
}
