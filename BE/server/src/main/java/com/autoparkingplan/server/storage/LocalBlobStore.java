package com.autoparkingplan.server.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 로컬 파일 보관소. 키는 항상 '/' 구분 (윈도우에서도 Path 가 올바른 구분자로 바꾼다). 상위 경로 탈출 금지. */
public final class LocalBlobStore implements BlobStore {
    private final Path root;

    public LocalBlobStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Path path(String key) {
        Path p = root;
        for (String part : key.split("/")) {
            if (!part.isEmpty()) {
                p = p.resolve(part);
            }
        }
        p = p.toAbsolutePath().normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("잘못된 키: " + key);
        }
        return p;
    }

    @Override
    public String put(String key, byte[] data, String contentType) {
        Path p = path(key);
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "file://" + key;
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(path(key.startsWith("file://") ? key.substring("file://".length()) : key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
