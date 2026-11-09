package com.autoparkingplan.server.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** AWS S3 보관소 (서버 측 암호화 AES256). 인증은 기본 자격 증명 체인 (ECS 태스크 역할). */
public final class S3BlobStore implements BlobStore {
    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3BlobStore(String bucket, String prefix, String region) {
        this.s3 = S3Client.builder().region(Region.of(region)).build();
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix;
    }

    @Override
    public String put(String key, byte[] data, String contentType) {
        String full = prefix + key;
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(full).contentType(contentType)
                .serverSideEncryption(ServerSideEncryption.AES256).build(), RequestBody.fromBytes(data));
        return "s3://" + bucket + "/" + full;
    }

    @Override
    public byte[] get(String key) {
        String k = key.startsWith("s3://" + bucket + "/") ? key.substring(("s3://" + bucket + "/").length()) : key;
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(k).build()).asByteArray();
    }
}
