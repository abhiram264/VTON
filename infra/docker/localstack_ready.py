#!/usr/bin/env python3
"""Idempotent LocalStack READY hook for local S3 buckets, generation-work queue, and DLQ."""

import json

import boto3
from botocore.exceptions import ClientError

ENDPOINT = "http://127.0.0.1:4566"
REGION = "us-east-1"
ACCESS_KEY = "test"
SECRET_KEY = "test"

BUCKETS = (
    "ai-fashion-vault-local-avatar-private",
    "ai-fashion-vault-local-vault-assets",
    "ai-fashion-vault-local-look-private",
    "ai-fashion-vault-local-generation-temp",
)
TEMP_BUCKET = "ai-fashion-vault-local-generation-temp"
QUEUE_NAME = "ai-fashion-vault-local-generation-work"
DLQ_NAME = "ai-fashion-vault-local-generation-work-dlq"

_ALREADY_EXISTS = frozenset(
    {
        "BucketAlreadyOwnedByYou",
        "BucketAlreadyExists",
        "QueueNameExists",
        "QueueAlreadyExists",
    }
)

_QUEUE_DEFAULTS = {
    "ReceiveMessageWaitTimeSeconds": "20",
    "VisibilityTimeout": "120",
}


def _client(service: str):
    return boto3.client(
        service,
        endpoint_url=ENDPOINT,
        region_name=REGION,
        aws_access_key_id=ACCESS_KEY,
        aws_secret_access_key=SECRET_KEY,
    )


def _ensure(operation) -> None:
    try:
        operation()
    except ClientError as exc:
        code = exc.response.get("Error", {}).get("Code")
        if code not in _ALREADY_EXISTS:
            raise


def _ensure_queue(sqs, name: str, attributes: dict) -> str:
    try:
        return sqs.create_queue(QueueName=name, Attributes=attributes)["QueueUrl"]
    except ClientError as exc:
        code = exc.response.get("Error", {}).get("Code")
        if code not in _ALREADY_EXISTS:
            raise
        url = sqs.get_queue_url(QueueName=name)["QueueUrl"]
        sqs.set_queue_attributes(QueueUrl=url, Attributes=attributes)
        return url


def main() -> None:
    s3 = _client("s3")
    sqs = _client("sqs")
    for name in BUCKETS:
        _ensure(lambda bucket=name: s3.create_bucket(Bucket=bucket))

    dlq_url = _ensure_queue(
        sqs,
        DLQ_NAME,
        {**_QUEUE_DEFAULTS, "MessageRetentionPeriod": "1209600"},
    )
    dlq_arn = sqs.get_queue_attributes(
        QueueUrl=dlq_url, AttributeNames=["QueueArn"]
    )["Attributes"]["QueueArn"]
    _ensure_queue(
        sqs,
        QUEUE_NAME,
        {
            **_QUEUE_DEFAULTS,
            "MessageRetentionPeriod": "345600",
            "RedrivePolicy": json.dumps(
                {"deadLetterTargetArn": dlq_arn, "maxReceiveCount": "5"}
            ),
        },
    )
    s3.put_bucket_lifecycle_configuration(
        Bucket=TEMP_BUCKET,
        LifecycleConfiguration={
            "Rules": [
                {
                    "ID": "temp-safety-net",
                    "Status": "Enabled",
                    "Filter": {"Prefix": ""},
                    "Expiration": {"Days": 1},
                    "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1},
                }
            ]
        },
    )


# LocalStack's Python init runner may not set __name__ == "__main__".
main()
