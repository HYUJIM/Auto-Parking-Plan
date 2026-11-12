# AWS 소규모 배포 (서버 한 대 + S3·CloudFront)

시범 운영용 저비용 구성입니다. 월 약 15달러(서울 리전 정가 기준 추정)이며, 사용자가 늘면 `BE/deploy/aws`(ECS·RDS·WAF, [DEPLOY_AWS.md](../../BE/docs/DEPLOY_AWS.md))로 옮깁니다.

```
사용자 ─ HTTPS ─ CloudFront ─┬─ (기본)        S3 버킷: FE 정적 파일
                             └─ /v1/*            Lightsail 서버 :80   (/v1/map/tiles/* 만 엣지 캐시 1일)
                                                  └ nginx(비밀 헤더 확인) ─ API(app.jar) ─ PostgreSQL
```

- 화면과 API 가 같은 주소로 나가므로 CORS 설정과 `VITE_API_BASE` 가 필요 없습니다. 도메인이 없어도 CloudFront 기본 주소로 HTTPS 가 됩니다.
- 배경지도 타일(`/v1/map/tiles/*`, 서버가 브이월드 타일을 대신 받아 주는 경로)은 CloudFront 가 하루 동안 캐시합니다 (캐시 키는 경로뿐, 서버의 `Cache-Control: public, max-age=86400` 을 따름). 같은 타일은 엣지에서 바로 나가고 서버·브이월드까지 가는 것은 처음 한 번뿐입니다. 나머지 `/v1/*` 는 캐시하지 않습니다.
- 서버 80 포트는 CloudFront 전용입니다. CloudFront 가 붙이는 비밀 헤더가 없으면 프록시가 403 으로 거부합니다 (서버 IP 직접 접속 차단).
- 관리자 API(`/admin`)와 상태 확인(`/healthz`)은 인터넷에 열지 않습니다. 관리자 API 는 SSH 터널로만 접속합니다 (아래 "검수").
- 화면 응답에는 CloudFront 가 CSP 등 보안 헤더를 붙입니다 (`template.yaml` 의 `FeSecurityHeaders`). 네이버 지도나 다른 타일 호스트를 쓰려면 CSP 에 그 호스트를 더해야 합니다.
- 서버는 이미지를 빌드하지 않습니다. 공식 JRE 이미지에 `app.jar` 와 `data/` 를 읽기 전용으로 붙여 실행합니다.

| 파일 | 내용 |
|---|---|
| `template.yaml` | CloudFormation: Lightsail 서버·고정 IP·일일 스냅샷, S3, CloudFront, GitHub 배포 역할 |
| `server/` | 서버에서 도는 `compose.yaml`, 프록시 설정, 릴리스 교체(`remote-deploy.sh`), 서버 설정(`host-setup.sh`: SSH·자동 재부팅·백업 예약), DB 백업(`backup-db.sh`) |
| `scripts/infra.sh` | 인프라 생성·변경 (`--plan` 은 미리 보기) |
| `scripts/server-env.sh` | 서버의 `/opt/app/.env` 작성 (공공 API 키, 비밀값) |
| `scripts/deploy-be.sh`, `scripts/deploy-fe.sh` | BE·FE 배포. 로컬과 GitHub Actions 가 같은 스크립트를 씁니다 |

## 준비

AWS CLI 로그인(리전 `ap-northeast-2`), JDK 21, Node, 그리고 공공 API 키가 들어 있는 `BE/.env`.
로그인한 사용자에게 CloudFormation·Lightsail·S3·CloudFront·IAM·Secrets Manager 권한이 있어야 합니다 (처음에는 `AdministratorAccess`).

## 처음 배포

```bash
deploy/aws-lite/scripts/infra.sh
```

SSH 키(`~/.ssh/autoparkingplan_lightsail`)를 만들어 Lightsail 에 등록하고 스택을 만듭니다. CloudFront 때문에 5~10분 걸립니다. 끝나면 사이트 주소(`SiteUrl`)가 나옵니다. 서버 초기 설정(Docker 설치)은 그 뒤 1~2분 더 걸립니다.

```bash
deploy/aws-lite/scripts/server-env.sh
```

`BE/.env` 의 공공 API 키를 서버로 보내고, DB 비밀번호와 관리자 토큰은 서버에서 무작위로 만듭니다. 키 값은 화면에 나오지 않습니다.

```bash
deploy/aws-lite/scripts/deploy-be.sh
```

```bash
deploy/aws-lite/scripts/deploy-fe.sh
```

첫 BE 배포는 법령 원문과 추출 결과를 적재합니다 (`bootstrap`, 일괄 승인 없음).

## 검수 (규칙 승인)

운영(`APP_ENV=prod`)은 일괄 승인을 거부합니다. 승인 전에는 모든 검토 항목이 "직접 확인 필요"로 나옵니다. 관리자 API 는 SSH 터널로 접속합니다.

```bash
ssh -i ~/.ssh/autoparkingplan_lightsail -L 8000:127.0.0.1:8000 ubuntu@<ServerIp>
```

2026-11-12 기준 95개 중 91개가 승인돼 있습니다. 보류 4개는 조례 용적률(중심·일반·근린상업지역, 준공업지역)로, 조례 제80조제4항(상업지역 주거복합건축물)·제6항(준공업지역 오피스텔)의 단서가 규칙에 예외 근거로 들어 있지 않아 그대로 승인하면 단서 없이 표시되기 때문입니다. 이 용도지역의 용적률은 그동안 국가 법령 범위로 표시됩니다.

터널을 연 채로 `http://127.0.0.1:8000/admin/rules?status=auto_checked` 를 `X-Admin-Token` 헤더(값은 서버 `/opt/app/.env` 의 `ADMIN_TOKEN`)와 함께 부릅니다. 승인 절차는 [BE/README.md](../../BE/README.md)의 관리자 API 표를 따릅니다.

## 자동 배포 (GitHub Actions)

`main` 에 병합되면 `.github/workflows/deploy-be.yml`, `deploy-fe.yml` 이 테스트 후 배포합니다. 저장소 설정에 아래 값을 등록해야 동작하고, 없으면 건너뜁니다.

| 종류 | 이름 | 값 |
|---|---|---|
| Variable | `AWS_DEPLOY_ROLE_ARN` | 스택 출력 `FeDeployRoleArn` |
| Variable | `FE_BUCKET` | 스택 출력 `FeBucketName` |
| Variable | `CF_DISTRIBUTION_ID` | 스택 출력 `DistributionId` |
| Variable | `DEPLOY_HOST` | 스택 출력 `ServerIp` |
| Variable | `DEPLOY_KNOWN_HOSTS` | `~/.ssh/autoparkingplan_known_hosts` 의 내용 (서버 호스트 키) |
| Secret | `DEPLOY_SSH_KEY` | 배포 전용 개인 키. 본인 키와 따로 만들어 공개 키를 서버 `~/.ssh/authorized_keys` 에 추가 |

FE 는 장기 키 없이 OIDC 로 `autoparkingplan-fe-deploy` 역할(버킷 쓰기 + 캐시 비움만 허용)을 맡습니다. BE 는 SSH 로 올립니다.
BE 배포는 단위 테스트와 종단 검증(`e2e-verify`)을 모두 통과해야 진행되고, 두 배포 모두 `main` 가지에서만 실행됩니다.

### 저장소 설정 (한 번, 직접)

코드로는 할 수 없는 설정입니다. 하지 않아도 배포는 되지만, 아래가 없으면 쓰기 권한이 있는 누구나 검사 없이 운영에 올리거나 배포 키를 읽을 수 있습니다.

1. **main 보호 규칙**: Settings → Rules → New ruleset → 대상 `main`, "Require a pull request before merging" 과 "Require status checks to pass"(`be-ci`, `fe-ci`).
2. **배포 키를 main 전용으로**: Settings → Environments → `production`(첫 BE 배포 때 자동으로 생깁니다) → Deployment branches 를 `main` 만으로 →
   Environment secrets 에 `DEPLOY_SSH_KEY` 를 넣고, 저장소 Secrets 의 같은 이름은 지웁니다. 다른 가지의 워크플로는 이 키를 읽지 못하게 됩니다.
3. **Dependabot alerts**: Settings → Code security → Dependabot alerts 를 켭니다 (`.github/dependabot.yml` 의 갱신 PR 은 설정 없이도 열립니다).

정적 보안 분석(`codeql.yml`)의 결과는 저장소의 Security → Code scanning 에 쌓입니다.

## 운영 메모

- **로그**: 서버에서 `cd /opt/app && docker compose logs -f api`
- **키 교체**: `BE/.env` 를 고친 뒤 `server-env.sh` 를 다시 실행 (DB 비밀번호·관리자 토큰은 유지)
- **로그 크기**: 컨테이너마다 10MB × 5개로 묶여 있습니다. 프록시 접근 기록에는 실제 사용자 주소(`xff=`)가 남습니다
- **백업**: 두 겹입니다. ① 매일 03:10 KST 에 `pg_dump` 를 `/opt/app/backups/app-날짜.dump` 로 (최근 7개), ② 04:00 KST 에 Lightsail 스냅샷이 디스크 전체를 (최근 7개).
  데이터만 되돌릴 때: `cd /opt/app && docker compose exec -T db pg_restore -U app -d app --clean --if-exists < backups/app-YYYY-MM-DD.dump`
- **되돌리기**: 배포 후 API 가 5분 안에 정상이 되지 않으면 이전 `app.jar`·`data`·compose·프록시 설정으로 자동 복구합니다 (DB 마이그레이션은 되돌리지 않습니다)
- **서버 설정**: BE 배포 때마다 `host-setup.sh` 가 맞춥니다 — SSH(X11 전달·root 로그인 끔), 보안 업데이트 뒤 재부팅이 필요하면 04:30 KST 자동 재부팅, 백업 예약
- **예산 알림**: `BUDGET_EMAIL=you@example.com deploy/aws-lite/scripts/infra.sh` (월 30달러 기준 50·80·100% 와 예상 초과 때 메일. 한도는 `BUDGET_LIMIT_USD`)
- **서버를 지우지 않는 안전장치**: 스택 종료 보호가 켜져 있고, 서버·고정 IP 는 스택을 지워도 남습니다 (DB 가 서버 디스크에 있기 때문).
  요금제·OS 이미지처럼 서버 교체가 필요한 변경은 일부러 실패합니다 — 옮길 때는 스냅샷으로 새 서버를 만드세요
- **도메인 연결**: ACM 인증서(us-east-1)를 만들고 `template.yaml` 의 Distribution 에 `Aliases`·`ViewerCertificate` 를 추가합니다. 브이월드 키의 등록 도메인과 서버 `.env` 의 `CORS_ORIGINS` 도 같이 바꿉니다
- **지우기**: 종료 보호를 끈 뒤(`aws cloudformation update-termination-protection --stack-name autoparkingplan --no-enable-termination-protection`)
  `aws cloudformation delete-stack --stack-name autoparkingplan`. FE 버킷·서버·고정 IP·비밀값은 남으므로 Lightsail·S3·Secrets Manager 에서 직접 지웁니다 (서버를 지우면 DB 도 사라집니다)

## 알려진 한계

- CloudFront 와 서버 사이는 HTTP 입니다 (비밀 헤더로 출처만 확인). 그래서 관리자 API 를 이 경로에 싣지 않습니다. 도메인을 붙이면 서버에도 인증서를 달아 HTTPS 로 바꿀 수 있습니다
- SSH(22)가 인터넷에 열려 있습니다 (키 인증만). GitHub Actions 가 접속해야 해서입니다
- CloudFront 기본 주소(`*.cloudfront.net`)는 최소 TLS 버전을 올릴 수 없습니다. 자체 도메인 인증서를 붙이면 TLS 1.2 이상으로 정할 수 있습니다
- 요청 제한은 서버 한 대의 메모리에서 셉니다. WAF 가 없어 '모든 사용자 합계' 제한(`RATE_LIMIT_GLOBAL_FACTOR`)이 그 자리를 대신합니다
- 서버가 한 대라 장애·재배포 때 API 가 잠깐 멈춥니다 (재배포는 보통 1분 이내)
- 국가법령정보 Open API(`LAWGO_OC`)는 서버 IP 등록이 필요합니다. 고정 IP(`ServerIp`)를 등록하면 됩니다
