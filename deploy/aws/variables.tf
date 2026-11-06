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
  type    = string
  default = "latest"
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
  description = "HTTPS 인증서 ARN (없으면 HTTP 80 만)"
}
