# AWS 배포 절차

리전 `ap-northeast-2`(서울). Terraform 1.6 이상, AWS CLI, Docker 가 필요합니다. 윈도우·맥 모두 같은 명령이며, 이미지 빌드·Docker 명령은 `BE/` 폴더에서 실행합니다.

## 1. 인프라 생성

```bash
cd BE/deploy/aws
terraform init
terraform apply -var env=dev
```

생성물: VPC(퍼블릭 2·프라이빗 2 서브넷), ALB, ECS 클러스터(api·worker 서비스), RDS PostgreSQL 16(프라이빗), S3 원문 버킷(버전 관리·암호화·공개 차단), Secrets Manager(앱 키·DB URL), CloudWatch 로그, EventBridge Scheduler(매일 04:00 KST `refresh-sources`).

> MVP 비용 절감을 위해 NAT 게이트웨이 없이 ECS 작업을 퍼블릭 서브넷(공인 IP, 보안그룹으로 ALB 에서만 8000 허용)에 둡니다. 운영 단계에서는 프라이빗 서브넷 + NAT 또는 VPC 엔드포인트로 옮기세요.

## 2. 비밀값 등록

`terraform output app_secret_arn` 의 비밀에 JSON 으로 값을 넣습니다 (키는 서버에만 둡니다).

```json
{"JUSO_API_KEY":"...","VWORLD_API_KEY":"...","BUILDING_HUB_KEY":"...","LAWGO_OC":"...","ANTHROPIC_API_KEY":"...","OPENAI_API_KEY":"","ADMIN_TOKEN":"충분히 긴 임의 문자열"}
```

- 국가법령정보 공동활용(open.law.go.kr) Open API 는 **서버 IP 등록**이 필요합니다. ECS 작업의 공인 IP 는 바뀌므로, 운영에서는 NAT 게이트웨이의 고정 EIP 를 등록하세요.
- 브이월드 키는 발급 시 등록한 도메인을 `vworld_domain` 변수로 넘깁니다.

## 3. 이미지 빌드·배포

```bash
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin <ecr_repository_url 의 호스트>
```

```bash
docker build --platform linux/amd64 -t <ecr_repository_url>:latest .
```

```bash
docker push <ecr_repository_url>:latest
```

```bash
aws ecs update-service --cluster app-dev --service api --force-new-deployment
```

```bash
aws ecs update-service --cluster app-dev --service worker --force-new-deployment
```

## 4. 최초 데이터 적재

`terraform output bootstrap_command` 로 나온 명령을 실행하면 원문 번들 적재 + 기록된 추출 결과 적재가 됩니다(승인 없음).
검수자는 관리자 API(`/admin/rules?status=auto_checked` → `/admin/rules/{id}/approve`)로 규칙과 사실 원장을 승인합니다.
승인된 규칙만 검토 결과에 쓰입니다.

## 5. 확인

```bash
curl http://<api_url>/healthz
```

```bash
curl "http://<api_url>/v1/regions"
```
