variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "env" {
  type    = string
  default = "dev"
  validation {
    condition     = contains(["dev", "staging", "prod"], var.env)
    error_message = "env 는 dev, staging, prod 중 하나"
  }
}

variable "image_tag" {
  type        = string
  default     = "latest"
  description = "배포할 이미지 태그. ecr_immutable_tags=true(기본)이면 태그를 덮어쓸 수 없으므로 git SHA 같은 고유 태그를 push 한 뒤 -var image_tag=<태그> 로 apply 한다"
}

variable "ecr_immutable_tags" {
  type        = bool
  default     = true
  description = "ECR 태그 불변(IMMUTABLE). 같은 태그를 다시 push 해 실행 중인 이미지를 몰래 바꾸는 것을 막는다"
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "api_count" {
  type    = number
  default = 1
}

variable "worker_count" {
  type    = number
  default = 1
}

variable "llm_provider" {
  type    = string
  default = "anthropic"
}

variable "cors_origins" {
  type    = list(string)
  default = ["http://localhost:3000"]
}

variable "vworld_domain" {
  type    = string
  default = ""
}

variable "acm_certificate_arn" {
  type        = string
  default     = ""
  description = "HTTPS 인증서 ARN. 주면 443(HTTPS) 리스너 + 80→443 리다이렉트, 비우면 HTTP 80 만 (dev 전용 — prod 는 필수)"
}

variable "enable_waf" {
  type        = bool
  default     = true
  description = "ALB 에 WAF(IP 당 요청 수 제한 + AWS 관리형 공통 규칙) 연결"
}

variable "waf_rate_limit" {
  type        = number
  default     = 1000
  description = "WAF: IP 하나가 5분 동안 보낼 수 있는 요청 수. 넘으면 차단"
  validation {
    condition     = var.waf_rate_limit >= 100
    error_message = "waf_rate_limit 은 100 이상"
  }
}

variable "waf_common_rule_count_overrides" {
  type        = list(string)
  default     = ["SizeRestrictions_BODY"]
  description = "AWSManagedRulesCommonRuleSet 중 차단 대신 집계(count)만 할 규칙. 기본: 8KB 넘는 본문(관리자 구역 GeoJSON 등) 차단 방지"
}

variable "admin_allowed_cidrs" {
  type        = list(string)
  default     = []
  description = "/admin/* 에 접근할 수 있는 IPv4 CIDR (WAF 필요). 비우면 IP 제한 없이 X-Admin-Token 만으로 보호"
}
