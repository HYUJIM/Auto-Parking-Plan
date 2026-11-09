-- 검토 요청을 워커(또는 스레드 실행기)가 가져간 시각.
-- 워커 재시작 때 '오래된 running' 판정을 생성 시각이 아니라 이 값으로 해서, 밀린 대기열에서 방금 다른 워커가 가져간 요청을
-- 다시 대기열로 돌려 두 번 실행하는 일을 막는다. 파이썬판이 만든 행은 NULL → 생성 시각 기준으로 되돌린다.
ALTER TABLE review_request ADD COLUMN claimed_at TIMESTAMP WITH TIME ZONE;

-- 대기열 조회(대기 수·가져가기·되돌리기)는 status 로 거른다. 결과는 지우지 않아 표가 계속 커지므로 색인을 둔다.
CREATE INDEX ix_review_request_status_created ON review_request (status, created_at);
