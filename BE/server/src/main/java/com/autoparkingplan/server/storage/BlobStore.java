package com.autoparkingplan.server.storage;

/** 원문 보관소: 받은 원문(XML/HTML/PDF)을 그대로 저장한다. 로컬 파일 또는 AWS S3 (파이썬 app.storage.blob). */
public interface BlobStore {
    /** 저장하고 참조 문자열(file://키 또는 s3://버킷/키)을 돌려준다. */
    String put(String key, byte[] data, String contentType);

    byte[] get(String key);
}
