# AWS 배포 절차 (스프링판)

파이썬판(`BE/docs/DEPLOY_AWS.md`)과 같은 구성·같은 순서입니다. 다른 점: 이미지(`java -jar app.jar …`), 작업 메모리 기본 2GB(`task_memory`), 자원 이름 접두어 `app-spring-<env>` (파이썬판과 같은 계정에 함께 띄울 수 있음).

리전 `ap-northeast-2`(서울). Terraform 1.6 이상, AWS CLI, Docker 가 필요합니다. 윈도우·맥 모두 같은 명령이며, 이미지 빌드·Docker 명령은 `BE-spring/` 폴더에서 실행합니다. 이미지 빌드는 Docker 안에서 Gradle 로 하므로 로컬 JDK 는 없어도 됩니다.

## 1. 인프라 생성

```bash
cd BE-spring/deploy/aws
terraform init
terraform apply -var env=dev
```

운영(`env=prod`)은 HTTPS 인증서가 필수입니다 (없으면 plan 이 실패합니다. 관리자 토큰이 평문으로 오가지 않게).

```bash
terraform apply -var env=prod -var acm_certificate_arn=<ACM 인증서 ARN> -var 'cors_origins=["https://<프론트 주소>"]'
```

- WAF(IP 당 요청 수 제한 + AWS 관리형 공통 규칙)가 기본으로 ALB 에 붙습니다 (`enable_waf`, `waf_rate_limit`).
- 관리자 API 를 사무실 IP 에서만 열려면 `-var 'admin_allowed_cidrs=["<사무실 IP>/32"]'`.
- AWS 의 작업은 모두 `APP_ENV=prod` 로 돕니다: API 문서 비공개, 개발용 일괄 승인 금지, 약한 관리자 토큰이면 시작 거부. 보안 설정 전체는 [`SECURITY.md`](SECURITY.md).

생성물: VPC(퍼블릭 2·프라이빗 2 서브넷), ALB, ECS 클러스터(api·worker 서비스), RDS PostgreSQL 16(프라이빗), S3 원문 버킷(버전 관리·암호화·공개 차단), Secrets Manager(앱 키·DB URL), CloudWatch 로그, EventBridge Scheduler(매일 04:00 KST `refresh-sources`).

> MVP 비용 절감을 위해 NAT 게이트웨이 없이 ECS 작업을 퍼블릭 서브넷(공인 IP, 보안그룹으로 ALB 에서만 8000 허용)에 둡니다.
> JVM 은 시작에 20~40초가 걸립니다. ECS 서비스에 헬스체크 유예 120초(`health_check_grace_period_seconds`)를 두어 그동안 작업을 교체하지 않습니다. 운영 단계에서는 프라이빗 서브넷 + NAT 또는 VPC 엔드포인트로 옮기세요.

## 2. 비밀값 등록

`terraform output app_secret_arn` 의 비밀에 JSON 으로 값을 넣습니다 (키는 서버에만 둡니다).

```json
{"JUSO_API_KEY":"...","VWORLD_API_KEY":"...","BUILDING_HUB_KEY":"...","LAWGO_OC":"...","ANTHROPIC_API_KEY":"...","OPENAI_API_KEY":"","ADMIN_TOKEN":"32자 이상 무작위 값"}
```

`ADMIN_TOKEN` 은 32자 이상 무작위 값이어야 합니다 (짧거나 `change-me` 가 들어 있으면 서버가 시작하지 않습니다). 만드는 법:

```bash
openssl rand -base64 32
```

윈도우 PowerShell:

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

- 국가법령정보 공동활용(open.law.go.kr) Open API 는 **서버 IP 등록**이 필요합니다. ECS 작업의 공인 IP 는 바뀌므로, 운영에서는 NAT 게이트웨이의 고정 EIP 를 등록하세요.
- 브이월드 키는 발급 시 등록한 도메인을 `vworld_domain` 변수로 넘깁니다.

## 3. 이미지 빌드·배포

```bash
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin <ecr_repository_url 의 호스트>
```

ECR 태그는 덮어쓸 수 없습니다(바꿔치기 방지). 배포마다 고유 태그(예: git 커밋 SHA)로 올리고, 그 태그로 Terraform 을 적용합니다.

```bash
docker build --platform linux/amd64 -t <ecr_repository_url>:<git SHA> .
```

```bash
docker push <ecr_repository_url>:<git SHA>
```

```bash
cd deploy/aws
terraform apply -var env=dev -var image_tag=<git SHA>
```

같은 태그를 다시 올리면 push 가 거부됩니다 (태그 덮어쓰기를 꼭 써야 하면 `-var ecr_immutable_tags=false`, 권장하지 않음).

## 4. 최초 데이터 적재

`terraform output bootstrap_command` 로 나온 명령을 실행하면 원문 번들 적재 + 기록된 추출 결과 적재가 됩니다(승인 없음).
검수자는 관리자 API(`/admin/rules?status=auto_checked` → `/admin/rules/{id}/approve`)로 규칙과 사실 원장을 승인합니다.
승인된 규칙만 검토 결과에 쓰입니다.

## 5. 확인

`api_url` 은 `terraform output api_url` (인증서가 있으면 https).

```bash
curl <api_url>/healthz
```

```bash
curl "<api_url>/v1/regions"
```
