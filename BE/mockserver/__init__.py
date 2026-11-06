"""
외부 공공 API(도로명주소·브이월드·건축HUB) 로컬 mock 서버.

실행:  python -m mockserver --port 8090
클라이언트 연결: JusoClient(base_url="http://127.0.0.1:8090"), VWorldClient(base_url_data=..., base_url_ned=...),
                BuildingHubClient(base_url=...) 에 같은 주소를 넣는다. 키는 아무 값이나 되고 'invalid' 만 인증 오류.

예시 필지 목록: mockserver.load_parcels()  /  파일 경로: mockserver.fixtures_path()
"""

from mockserver.dataset import fixtures_path, load_dataset, load_parcels

__all__ = ["fixtures_path", "load_dataset", "load_parcels"]
