package com.autoparkingplan.server.legal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import com.autoparkingplan.db.entity.SourceDocument;
import com.autoparkingplan.legal.LawGoDrfClient;
import com.autoparkingplan.legal.SourceBundle;
import com.autoparkingplan.server.config.AppSettings;
import com.autoparkingplan.server.storage.BlobStore;

/** 국가법령정보 Open API(DRF)로 문서 하나를 다시 받아 적재 (관리자 재수집·매일 재수집 공통). */
public final class Refetch {
    private Refetch() {
    }

    public static String target(SourceDocument doc) {
        return "ordinance".equals(doc.getSourceType()) || "local_rule".equals(doc.getSourceType()) ? "ordin" : "law";
    }

    public static Ingest.IngestResult refetch(EntityManager em, SourceDocument doc, AppSettings settings, BlobStore blobs) {
        return refetch(em, doc, new LawGoDrfClient(settings.lawgoOc, settings.lawgoBaseUrl), blobs);
    }

    /** 받은 XML 을 그대로 보관·해시하기 위해 임시 폴더(raw/)에 저장하고 base_dir 로 넘긴다. */
    public static Ingest.IngestResult refetch(EntityManager em, SourceDocument doc, LawGoDrfClient client, BlobStore blobs) {
        Path tmp;
        try {
            tmp = Files.createTempDirectory("lawgo-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            SourceBundle bundle = client.getDocument(target(doc), doc.getExternalId(), tmp.resolve("raw"));
            bundle = bundle.withDocument(bundle.document().withKey(doc.getKey()));
            return Ingest.ingestBundle(em, bundle, blobs, tmp);
        } finally {
            deleteTree(tmp);
        }
    }

    static void deleteTree(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 임시 파일 정리 실패는 무시
                }
            });
        } catch (IOException ignored) {
            // 없음
        }
    }
}
