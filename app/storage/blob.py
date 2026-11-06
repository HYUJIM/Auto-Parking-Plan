"""원문 보관소: 받은 원문(XML/HTML/PDF)을 그대로 저장한다. 로컬 파일 또는 AWS S3."""
from __future__ import annotations

from pathlib import Path
from typing import Protocol


class BlobStore(Protocol):
    def put(self, key: str, data: bytes, content_type: str) -> str: ...
    def get(self, key: str) -> bytes: ...


class LocalBlobStore:
    def __init__(self, root: Path):
        self.root = Path(root)

    def _path(self, key: str) -> Path:
        # 키는 항상 '/' 구분. 윈도우에서도 pathlib 가 올바른 구분자로 바꾼다. 상위 경로 탈출 금지.
        p = (self.root / Path(*key.split("/"))).resolve()
        if not p.is_relative_to(self.root.resolve()):
            raise ValueError(f"잘못된 키: {key}")
        return p

    def put(self, key: str, data: bytes, content_type: str) -> str:
        p = self._path(key)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        return f"file://{key}"

    def get(self, key: str) -> bytes:
        return self._path(key.removeprefix("file://")).read_bytes()


class S3BlobStore:
    def __init__(self, bucket: str, prefix: str = "", region: str | None = None):
        import boto3  # noqa: PLC0415  (선택 의존성: pip install .[aws])

        self._s3 = boto3.client("s3", region_name=region)
        self.bucket = bucket
        self.prefix = prefix

    def put(self, key: str, data: bytes, content_type: str) -> str:
        full = self.prefix + key
        self._s3.put_object(Bucket=self.bucket, Key=full, Body=data, ContentType=content_type,
                            ServerSideEncryption="AES256")
        return f"s3://{self.bucket}/{full}"

    def get(self, key: str) -> bytes:
        k = key.removeprefix(f"s3://{self.bucket}/")
        return self._s3.get_object(Bucket=self.bucket, Key=k)["Body"].read()


def make_blob_store(settings) -> BlobStore:  # noqa: ANN001
    if settings.blob_store == "s3":
        if not settings.s3_bucket:
            raise ValueError("BLOB_STORE=s3 인데 S3_BUCKET 이 없습니다")
        return S3BlobStore(settings.s3_bucket, settings.s3_prefix, settings.aws_region)
    return LocalBlobStore(settings.blob_local_dir)
