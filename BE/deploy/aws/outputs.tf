output "api_url" {
  # 인증서가 있으면 HTTPS (인증서의 도메인을 ALB DNS 이름에 CNAME/ALIAS 로 연결해 그 도메인으로 접속)
  value = "${var.acm_certificate_arn == "" ? "http" : "https"}://${aws_lb.api.dns_name}"
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "raw_bucket" {
  value = aws_s3_bucket.raw.bucket
}

output "app_secret_arn" {
  value       = aws_secretsmanager_secret.app.arn
  description = "JSON 으로 JUSO_API_KEY, VWORLD_API_KEY, BUILDING_HUB_KEY, LAWGO_OC, ANTHROPIC_API_KEY, OPENAI_API_KEY, ADMIN_TOKEN 값을 넣는다"
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "bootstrap_command" {
  value = "aws ecs run-task --cluster ${aws_ecs_cluster.main.name} --launch-type FARGATE --task-definition ${aws_ecs_task_definition.worker.family} --overrides '{\"containerOverrides\":[{\"name\":\"worker\",\"command\":[\"bootstrap\"]}]}' --network-configuration 'awsvpcConfiguration={subnets=[${aws_subnet.public[0].id}],securityGroups=[${aws_security_group.app.id}],assignPublicIp=ENABLED}'"
}
