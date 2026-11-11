# AWS 소규모 배포 (서버 한 대 + S3·CloudFront)

시범 운영용 저비용 구성입니다. 월 약 15달러(서울 리전 정가 기준 추정)이며, 사용자가 늘면 `BE/deploy/aws`(ECS·RDS·WAF, [DEPLOY_AWS.md](../../BE/docs/DEPLOY_AWS.md))로 옮깁니다.

```
사용자 ─ HTTPS ─ CloudFront ─┬─ (기본)        S3 버킷: FE 정적 파일
                             └─ /v1/*, /healthz  Lightsail 서버 :80
                                                  └ nginx(비밀 헤더 확인) ─ API(app.jar) ─ PostgreSQL
```

- 화면과 API 가 같은 주소로 나가므로 CORS 설정과 `VITE_API_BASE` 가 필요 없습니다. 도메인이 없어도 CloudFront 기본 주소로 HTTPS 가 됩니다.
- 서버 80 포트는 CloudFront 전용입니다. CloudFront 가 붙이는 비밀 헤더가 없으면 프록시가 403 으로 거부합니다 (서버 IP 직접 접속 차단).
- 관리자 API(`/admin`)는 인터넷에 열지 않습니다. SSH 터널로만 접속합니다 (아래 "검수").
- 서버는 이미지를 빌드하지 않습니다. 공식 JRE 이미지에 `app.jar` 와 `data/` 를 읽기 전용으로 붙여 실행합니다.

| 파일 | 내용 |
|---|---|
| `template.yaml` | CloudFormation: Lightsail 서버·고정 IP·일일 스냅샷, S3, CloudFront, GitHub 배포 역할 |
| `server/` | 서버에서 도는 `compose.yaml`, 프록시 설정, 릴리스 교체 스크립트 |
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

## 운영 메모

- **로그**: 서버에서 `cd /opt/app && docker compose logs -f api`
- **키 교체**: `BE/.env` 를 고친 뒤 `server-env.sh` 를 다시 실행 (DB 비밀번호·관리자 토큰은 유지)
- **백업**: Lightsail 자동 스냅샷이 매일 04:00 KST 에 디스크 전체를 찍습니다 (최근 7개)
- **되돌리기**: 배포 후 API 가 5분 안에 정상이 되지 않으면 이전 `app.jar` 로 자동 복구합니다
- **도메인 연결**: ACM 인증서(us-east-1)를 만들고 `template.yaml` 의 Distribution 에 `Aliases`·`ViewerCertificate` 를 추가합니다. 브이월드 키의 등록 도메인과 서버 `.env` 의 `CORS_ORIGINS` 도 같이 바꿉니다
- **지우기**: `aws cloudformation delete-stack --stack-name autoparkingplan` (FE 버킷은 남습니다. 비운 뒤 직접 삭제)

## 알려진 한계

- CloudFront 와 서버 사이는 HTTP 입니다 (비밀 헤더로 출처만 확인). 그래서 관리자 API 를 이 경로에 싣지 않습니다. 도메인을 붙이면 서버에도 인증서를 달아 HTTPS 로 바꿀 수 있습니다
- SSH(22)가 인터넷에 열려 있습니다 (키 인증만). GitHub Actions 가 접속해야 해서입니다
- 서버가 한 대라 장애·재배포 때 API 가 잠깐 멈춥니다 (재배포는 보통 1분 이내)
- 국가법령정보 Open API(`LAWGO_OC`)는 서버 IP 등록이 필요합니다. 고정 IP(`ServerIp`)를 등록하면 됩니다
