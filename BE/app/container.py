"""앱 구성요소 조립 (API·워커·CLI 공통). 테스트는 구성요소를 직접 바꿔 끼울 수 있다."""
from __future__ import annotations

import logging
from dataclasses import dataclass

from app.config import Settings
from app.db.session import Database
from app.explain.service import ExplanationService
from app.llm.factory import LLMSet, build_llms
from app.parcels.service import ParcelService
from app.review.service import ReviewService
from app.storage.blob import BlobStore, make_blob_store

log = logging.getLogger(__name__)


@dataclass
class Container:
    settings: Settings
    db: Database
    blobs: BlobStore
    llms: LLMSet
    parcels: ParcelService
    explainer: ExplanationService
    reviews: ReviewService

    def close(self) -> None:
        self.reviews.shutdown()


OFFICIAL_HOSTS = ("juso.go.kr", "vworld.kr", "data.go.kr")


def _official_hosts(settings: Settings) -> bool:
    from urllib.parse import urlparse  # noqa: PLC0415

    urls = [settings.juso_base_url, settings.vworld_data_url, settings.vworld_ned_url, settings.building_hub_url]
    return all((urlparse(u).hostname or "").endswith(OFFICIAL_HOSTS) for u in urls)


def build_external_clients(settings: Settings):
    """설정된 키가 있는 외부 API 클라이언트만 만든다."""
    juso = vworld = building = None
    if settings.juso_api_key:
        from app.external.juso import JusoClient  # noqa: PLC0415
        juso = JusoClient(settings.juso_api_key, base_url=settings.juso_base_url, timeout=settings.external_timeout_s)
    if settings.vworld_api_key:
        from app.external.vworld import VWorldClient  # noqa: PLC0415
        vworld = VWorldClient(settings.vworld_api_key, domain=settings.vworld_domain, base_url_data=settings.vworld_data_url,
                              base_url_ned=settings.vworld_ned_url, timeout=settings.external_timeout_s)
    if settings.building_hub_key:
        from app.external.building_hub import BuildingHubClient  # noqa: PLC0415
        building = BuildingHubClient(settings.building_hub_key, base_url=settings.building_hub_url, timeout=settings.external_timeout_s)
    return juso, vworld, building


def build_container(settings: Settings, *, db: Database | None = None, llms: LLMSet | None = None,
                    parcels: ParcelService | None = None) -> Container:
    db = db or Database(settings.database_url, echo=settings.db_echo)
    db.create_all()
    blobs = make_blob_store(settings)
    llms = llms or build_llms(settings)
    if parcels is None:
        juso, vworld, building = build_external_clients(settings)
        parcels = ParcelService(juso=juso, vworld=vworld, building=building, cache_days=settings.parcel_cache_days,
                                example_data=not _official_hosts(settings),
                                refresh_min_interval_s=settings.parcel_refresh_min_interval_s)
    explainer = ExplanationService(writer=llms.writer, judge=llms.judge, max_attempts=settings.explanation_max_attempts)
    reviews = ReviewService(db, parcels, explainer, executor=settings.review_executor, workers=settings.review_workers,
                            timezone=settings.timezone, max_pending=settings.max_pending_reviews)
    return Container(settings, db, blobs, llms, parcels, explainer, reviews)
