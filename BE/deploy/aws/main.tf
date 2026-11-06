# APP 법규 서버 — AWS 배포 (ap-northeast-2)
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
  name = "app-${var.env}"
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
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "app" {
  name   = "${local.name}-app"
  vpc_id = aws_vpc.main.id
  ingress {
    from_port       = 8000
    to_port         = 8000
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  egress { # 공공 API·LLM API 호출
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
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
  image_tag_mutability = "MUTABLE"
  image_scanning_configuration { scan_on_push = true }
}

resource "aws_s3_bucket" "raw" {
  bucket_prefix = "${local.name}-law-raw-"
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
  identifier              = local.name
  engine                  = "postgres"
  engine_version          = "16"
  instance_class          = var.db_instance_class
  allocated_storage       = 20
  max_allocated_storage   = 100
  storage_encrypted       = true
  db_name                 = "app"
  username                = "app"
  password                = random_password.db.result
  db_subnet_group_name    = aws_db_subnet_group.db.name
  vpc_security_group_ids  = [aws_security_group.db.id]
  backup_retention_period = 7
  deletion_protection     = var.env == "prod"
  skip_final_snapshot     = var.env != "prod"
  publicly_accessible     = false
}

# 앱 비밀값: 콘솔/CLI 로 값만 채운다 (terraform 상태에 키를 남기지 않도록 기본값은 빈 JSON)
resource "aws_secretsmanager_secret" "app" {
  name_prefix = "${local.name}-app-"
}

resource "aws_secretsmanager_secret" "db_url" {
  name_prefix = "${local.name}-db-url-"
}

resource "aws_secretsmanager_secret_version" "db_url" {
  secret_id     = aws_secretsmanager_secret.db_url.id
  secret_string = "postgresql+psycopg://app:${random_password.db.result}@${aws_db_instance.db.address}:5432/app"
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

resource "aws_iam_role_policy_attachment" "exec" {
  role       = aws_iam_role.exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
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

resource "aws_iam_role_policy" "task_s3" {
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject", "s3:PutObject", "s3:ListBucket"]
      Resource = [aws_s3_bucket.raw.arn, "${aws_s3_bucket.raw.arn}/*"]
    }]
  })
}

locals {
  image = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"
  env = [
    { name = "APP_ENV", value = var.env },
    { name = "BLOB_STORE", value = "s3" },
    { name = "S3_BUCKET", value = aws_s3_bucket.raw.bucket },
    { name = "AWS_REGION", value = var.region },
    { name = "REVIEW_EXECUTOR", value = "worker" },
    { name = "LLM_PROVIDER", value = var.llm_provider },
    { name = "CORS_ORIGINS", value = jsonencode(var.cors_origins) },
    { name = "VWORLD_DOMAIN", value = var.vworld_domain },
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
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.exec.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name             = "api"
    image            = local.image
    essential        = true
    portMappings     = [{ containerPort = 8000, protocol = "tcp" }]
    environment      = local.env
    secrets          = local.secrets
    logConfiguration = local.log
  }])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.name}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.exec.arn
  task_role_arn            = aws_iam_role.task.arn
  container_definitions = jsonencode([{
    name             = "worker"
    image            = local.image
    essential        = true
    command          = ["python", "-m", "app.cli", "worker"]
    environment      = local.env
    secrets          = local.secrets
    logConfiguration = local.log
  }])
}

resource "aws_lb" "api" {
  name               = local.name
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id
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

resource "aws_ecs_service" "api" {
  name            = "api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_count
  launch_type     = "FARGATE"
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
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "scheduler.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "scheduler" {
  role = aws_iam_role.scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecs:RunTask"], Resource = [aws_ecs_task_definition.worker.arn_without_revision, "${aws_ecs_task_definition.worker.arn_without_revision}:*"] },
      { Effect = "Allow", Action = ["iam:PassRole"], Resource = [aws_iam_role.exec.arn, aws_iam_role.task.arn] }
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
    input = jsonencode({ containerOverrides = [{ name = "worker", command = ["python", "-m", "app.cli", "refresh-sources"] }] })
  }
}
