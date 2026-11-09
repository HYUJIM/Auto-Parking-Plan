# APP 법규 서버 (스프링판) — AWS 배포 (ap-northeast-2)
# 이식 전 파이썬판과 같은 구성. 컨테이너 명령은 java -jar app.jar …, 작업 메모리 기본 2GB, 자원 이름 접두어 app-spring-<env>.
# 구성: ECR · ECS Fargate(api, worker) · ALB · RDS PostgreSQL 16 · S3(원문 보관) · Secrets Manager · CloudWatch · EventBridge(매일 원문 개정 확인)
# 비용을 줄이기 위해 MVP 는 NAT 게이트웨이 없이 ECS 작업을 퍼블릭 서브넷(공인 IP)에 두고, RDS 는 프라이빗 서브넷에 둔다.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.60" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
}

provider "aws" {
  region = var.region
  default_tags { tags = { Project = "AutoParkingPlan", Env = var.env } }
}

locals {
  name = "app-spring-${var.env}" # 파이썬판(app-<env>)과 같은 계정에 함께 띄워도 이름이 겹치지 않게
  azs  = slice(data.aws_availability_zones.available.names, 0, 2)
}

data "aws_availability_zones" "available" { state = "available" }
data "aws_caller_identity" "me" {}

# ── 네트워크 ────────────────────────────────────────────────
resource "aws_vpc" "main" {
  cidr_block           = "10.40.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = { Name = local.name }
}

resource "aws_internet_gateway" "igw" { vpc_id = aws_vpc.main.id }

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  cidr_block              = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index)
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = true
  tags                    = { Name = "${local.name}-public-${count.index}" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index + 10)
  availability_zone = local.azs[count.index]
  tags              = { Name = "${local.name}-private-${count.index}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw.id
  }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# VPC 기본 보안그룹은 규칙을 모두 비워 둔다 (실수로 붙여도 아무 통신도 허용하지 않도록)
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.main.id
}

resource "aws_security_group" "alb" {
  name   = "${local.name}-alb"
  vpc_id = aws_vpc.main.id
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress { # 대상(ECS 작업)의 8000 포트로만
    from_port   = 8000
    to_port     = 8000
    protocol    = "tcp"
    cidr_blocks = [aws_vpc.main.cidr_block]
  }
}

# 나가는 통신: HTTPS(공공 API·LLM·ECR·Secrets Manager·S3·CloudWatch), HTTP(국가법령정보 DRF 가 http://www.law.go.kr),
# PostgreSQL(프라이빗 서브넷의 RDS). VPC DNS(Route 53 Resolver)·작업 메타데이터는 보안그룹 대상이 아니다.
resource "aws_security_group" "app" {
  name   = "${local.name}-app"
  vpc_id = aws_vpc.main.id
  ingress {
    from_port       = 8000
    to_port         = 8000
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  egress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = aws_subnet.private[*].cidr_block
  }
}

resource "aws_security_group" "db" {
  name   = "${local.name}-db"
  vpc_id = aws_vpc.main.id
  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }
}

# ── 저장소 ──────────────────────────────────────────────────
resource "aws_ecr_repository" "app" {
  name                 = local.name
  image_tag_mutability = var.ecr_immutable_tags ? "IMMUTABLE" : "MUTABLE"
  image_scanning_configuration { scan_on_push = true }
}

resource "aws_s3_bucket" "raw" {
  bucket_prefix = "${local.name}-law-raw-"
}

# ACL 비활성 (객체는 항상 버킷 소유자 것, 공개 ACL 불가)
resource "aws_s3_bucket_ownership_controls" "raw" {
  bucket = aws_s3_bucket.raw.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

# TLS(HTTPS) 가 아닌 요청은 모두 거부
resource "aws_s3_bucket_policy" "raw" {
  bucket = aws_s3_bucket.raw.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.raw.arn, "${aws_s3_bucket.raw.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.raw]
}

resource "aws_s3_bucket_versioning" "raw" {
  bucket = aws_s3_bucket.raw.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "raw" {
  bucket = aws_s3_bucket.raw.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_public_access_block" "raw" {
  bucket                  = aws_s3_bucket.raw.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "aws_db_subnet_group" "db" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "db" {
  identifier                = local.name
  engine                    = "postgres"
  engine_version            = "16"
  instance_class            = var.db_instance_class
  allocated_storage         = 20
  max_allocated_storage     = 100
  storage_encrypted         = true
  db_name                   = "app"
  username                  = "app"
  password                  = random_password.db.result
  db_subnet_group_name      = aws_db_subnet_group.db.name
  vpc_security_group_ids    = [aws_security_group.db.id]
  backup_retention_period   = 7
  copy_tags_to_snapshot     = true
  deletion_protection       = var.env == "prod"
  skip_final_snapshot       = var.env != "prod"
  final_snapshot_identifier = "${local.name}-final" # skip_final_snapshot=false(prod)일 때 필수
  publicly_accessible       = false
}

# 앱 비밀값: 콘솔/CLI 로 값만 채운다 (terraform 상태에 키를 남기지 않도록 기본값은 빈 JSON)
resource "aws_secretsmanager_secret" "app" {
  name_prefix = "${local.name}-app-"
}

resource "aws_secretsmanager_secret" "db_url" {
  name_prefix = "${local.name}-db-url-"
}

# sslmode=require: 앱↔RDS 구간 암호화 강제 (PostgreSQL 16 RDS 기본 파라미터도 rds.force_ssl=1)
resource "aws_secretsmanager_secret_version" "db_url" {
  secret_id     = aws_secretsmanager_secret.db_url.id
  secret_string = "postgresql+psycopg://app:${random_password.db.result}@${aws_db_instance.db.address}:5432/app?sslmode=require"
}

# ── ECS ─────────────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${local.name}"
  retention_in_days = 30
}

resource "aws_ecs_cluster" "main" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "exec" {
  name_prefix        = "${local.name}-exec-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

# 실행 역할(이미지 pull·로그·비밀 주입): AmazonECSTaskExecutionRolePolicy 와 같은 동작을 이 저장소·로그 그룹으로만 한정
resource "aws_iam_role_policy" "exec_pull_logs" {
  role = aws_iam_role.exec.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" }, # 리소스 수준 권한을 지원하지 않는 API
      {
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
        Resource = aws_ecr_repository.app.arn
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = [aws_cloudwatch_log_group.app.arn, "${aws_cloudwatch_log_group.app.arn}:*"]
      }
    ]
  })
}

resource "aws_iam_role_policy" "exec_secrets" {
  role = aws_iam_role.exec.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [aws_secretsmanager_secret.app.arn, aws_secretsmanager_secret.db_url.arn] }]
  })
}

resource "aws_iam_role" "task" {
  name_prefix        = "${local.name}-task-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

# 작업 역할(앱 코드가 쓰는 권한): 원문 버킷의 접두사 아래 읽기·쓰기만 (삭제 없음). 비밀값은 실행 역할이 주입하므로 여기엔 없다.
resource "aws_iam_role_policy" "task_s3" {
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:ListBucket"], Resource = aws_s3_bucket.raw.arn },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject"], Resource = "${aws_s3_bucket.raw.arn}/${local.s3_prefix}*" }
    ]
  })
}

locals {
  image     = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"
  s3_prefix = "law-raw/" # 앱 기본값(S3_PREFIX)과 같게 두고 환경변수로도 명시
  env = [
    # 앱은 APP_ENV 로 local|test|prod 만 받는다 (dev·staging 을 넘기면 기동 실패). AWS 배포는 env 와 상관없이
    # 인터넷에 공개되므로 항상 prod — 일괄 승인 금지, ADMIN_TOKEN 강도 검사 등 운영 보호가 켜진다
    { name = "APP_ENV", value = "prod" },
    { name = "BLOB_STORE", value = "s3" },
    { name = "S3_BUCKET", value = aws_s3_bucket.raw.bucket },
    { name = "S3_PREFIX", value = local.s3_prefix },
    { name = "AWS_REGION", value = var.region },
    { name = "REVIEW_EXECUTOR", value = "worker" },
    { name = "LLM_PROVIDER", value = var.llm_provider },
    { name = "CORS_ORIGINS", value = jsonencode(var.cors_origins) },
    { name = "VWORLD_DOMAIN", value = var.vworld_domain },
    # 앱 앞단 프록시 수 (ALB 1개). 앱이 X-Forwarded-For 에서 실제 클라이언트 IP 를 고를 때 쓴다
    { name = "TRUSTED_PROXY_HOPS", value = "1" },
  ]
  secret_keys = ["JUSO_API_KEY", "VWORLD_API_KEY", "BUILDING_HUB_KEY", "LAWGO_OC", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "ADMIN_TOKEN"]
  secrets = concat(
    [{ name = "DATABASE_URL", valueFrom = aws_secretsmanager_secret.db_url.arn }],
    [for k in local.secret_keys : { name = k, valueFrom = "${aws_secretsmanager_secret.app.arn}:${k}::" }]
  )
  log = {
    logDriver = "awslogs"
    options   = { "awslogs-group" = aws_cloudwatch_log_group.app.name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "app" }
  }
  # 루트 파일시스템 읽기 전용 + 리눅스 권한(capability) 모두 제거. 운영(BLOB_STORE=s3)에서 앱이 쓰는 곳은 tempfile(/tmp) 뿐
  hardening = {
    readonlyRootFilesystem = true
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    linuxParameters        = { capabilities = { drop = ["ALL"] } }
  }
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.exec.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  volume { name = "tmp" } # 작업 임시 저장소(바인드 마운트) → /tmp
  container_definitions = jsonencode([merge(local.hardening, {
    name             = "api"
    image            = local.image
    essential        = true
    portMappings     = [{ containerPort = 8000, protocol = "tcp" }]
    environment      = local.env
    secrets          = local.secrets
    logConfiguration = local.log
  })])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.name}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.exec.arn
  task_role_arn            = aws_iam_role.task.arn
  volume { name = "tmp" }
  container_definitions = jsonencode([merge(local.hardening, {
    name             = "worker"
    image            = local.image
    essential        = true
    command          = ["worker"] # 이미지 ENTRYPOINT = java -jar /srv/app/app.jar
    environment      = local.env
    secrets          = local.secrets
    logConfiguration = local.log
  })])
}

resource "aws_lb" "api" {
  name                       = local.name
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.public[*].id
  drop_invalid_header_fields = true # 헤더 이름이 RFC 에 맞지 않는 요청 헤더는 앱에 넘기지 않는다 (요청 밀반입·헤더 혼동 방지)
  enable_deletion_protection = var.env == "prod"
}

resource "aws_lb_target_group" "api" {
  name        = local.name
  port        = 8000
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id
  health_check {
    path    = "/healthz"
    matcher = "200"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = var.acm_certificate_arn == "" ? "forward" : "redirect"
    target_group_arn = var.acm_certificate_arn == "" ? aws_lb_target_group.api.arn : null
    dynamic "redirect" {
      for_each = var.acm_certificate_arn == "" ? [] : [1]
      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
  lifecycle {
    precondition {
      condition     = var.env != "prod" || var.acm_certificate_arn != ""
      error_message = "prod 는 acm_certificate_arn 이 필요합니다 (평문 HTTP 로 X-Admin-Token 이 오가지 않도록)"
    }
  }
}

resource "aws_lb_listener" "https" {
  count             = var.acm_certificate_arn == "" ? 0 : 1
  load_balancer_arn = aws_lb.api.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.acm_certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

# ── WAF (ALB 앞단) ──────────────────────────────────────────
# 로그인 없는 공개 API 이므로 IP 당 요청 수 제한 + AWS 관리형 공통 규칙(SQLi·XSS·LFI·SSRF 등).
# admin_allowed_cidrs 를 주면 /admin/* 은 그 IP 에서만 허용.
resource "aws_wafv2_ip_set" "admin" {
  count              = var.enable_waf && length(var.admin_allowed_cidrs) > 0 ? 1 : 0
  name               = "${local.name}-admin"
  scope              = "REGIONAL"
  ip_address_version = "IPV4"
  addresses          = var.admin_allowed_cidrs
}

resource "aws_wafv2_web_acl" "api" {
  count = var.enable_waf ? 1 : 0
  name  = local.name
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  dynamic "rule" {
    for_each = length(var.admin_allowed_cidrs) > 0 ? [1] : []
    content {
      name     = "admin-ip-allowlist"
      priority = 0
      action {
        block {}
      }
      statement {
        and_statement {
          statement {
            byte_match_statement {
              search_string         = "/admin"
              positional_constraint = "STARTS_WITH"
              field_to_match {
                uri_path {}
              }
              # 앱 서버가 경로의 %xx 를 풀어서 라우팅하므로 같은 방식으로 풀어 본다 (/%61dmin 우회 방지)
              text_transformation {
                priority = 0
                type     = "URL_DECODE"
              }
              text_transformation {
                priority = 1
                type     = "NORMALIZE_PATH"
              }
            }
          }
          statement {
            not_statement {
              statement {
                ip_set_reference_statement {
                  arn = aws_wafv2_ip_set.admin[0].arn
                }
              }
            }
          }
        }
      }
      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${local.name}-admin-ip-allowlist"
        sampled_requests_enabled   = true
      }
    }
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = 10
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit              = var.waf_rate_limit
        aggregate_key_type = "IP"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-common"
    priority = 20
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
        dynamic "rule_action_override" {
          for_each = var.waf_common_rule_count_overrides
          content {
            name = rule_action_override.value
            action_to_use {
              count {}
            }
          }
        }
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-aws-common"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = local.name
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "api" {
  count        = var.enable_waf ? 1 : 0
  resource_arn = aws_lb.api.arn
  web_acl_arn  = aws_wafv2_web_acl.api[0].arn
}

resource "aws_ecs_service" "api" {
  name            = "api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_count
  launch_type     = "FARGATE"
  # JVM 시작(Flyway 확인 포함) 동안 ALB 헬스체크 실패로 작업이 교체되지 않게
  health_check_grace_period_seconds = 120
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8000
  }
  depends_on = [aws_lb_listener.http]
}

resource "aws_ecs_service" "worker" {
  name            = "worker"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_count
  launch_type     = "FARGATE"
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true
  }
}

# ── 매일 원문 개정 확인 (국가법령정보 Open API) ───────────────
resource "aws_iam_role" "scheduler" {
  name_prefix = "${local.name}-sched-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.me.account_id } } # 혼동된 대리인 방지
    }]
  })
}

resource "aws_iam_role_policy" "scheduler" {
  role = aws_iam_role.scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Action    = ["ecs:RunTask"]
        Resource  = [aws_ecs_task_definition.worker.arn_without_revision, "${aws_ecs_task_definition.worker.arn_without_revision}:*"]
        Condition = { ArnEquals = { "ecs:cluster" = aws_ecs_cluster.main.arn } }
      },
      {
        Effect    = "Allow"
        Action    = ["iam:PassRole"]
        Resource  = [aws_iam_role.exec.arn, aws_iam_role.task.arn]
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      }
    ]
  })
}

resource "aws_scheduler_schedule" "refresh_sources" {
  name                         = "${local.name}-refresh-sources"
  schedule_expression          = "cron(0 4 * * ? *)"
  schedule_expression_timezone = "Asia/Seoul"
  flexible_time_window { mode = "OFF" }
  target {
    arn      = aws_ecs_cluster.main.arn
    role_arn = aws_iam_role.scheduler.arn
    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.worker.arn
      launch_type         = "FARGATE"
      network_configuration {
        subnets          = aws_subnet.public[*].id
        security_groups  = [aws_security_group.app.id]
        assign_public_ip = true
      }
    }
    input = jsonencode({ containerOverrides = [{ name = "worker", command = ["refresh-sources"] }] })
  }
}
