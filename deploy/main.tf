# Ledgerline Minimal Cloud Deployment (AWS)
# Cost-optimized single-VM + managed RDS PostgreSQL architecture (~$18-25/mo)

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.50"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  type    = string
  default = "production"
}

variable "db_password" {
  type        = string
  description = "PostgreSQL master password"
  sensitive   = true
}

variable "image_tag" {
  type        = string
  description = "Tag of the ledger-service/projection-service images to deploy (pushed to ECR by CI before this apply runs)"
  default     = "latest"
}

variable "jwt_secret" {
  type        = string
  description = "HMAC secret used to validate OAuth2 bearer JWTs"
  sensitive   = true
}

data "aws_caller_identity" "current" {}

locals {
  ecr_registry = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com"
}

# 1. Network Topology (VPC & Subnets)
resource "aws_vpc" "ledgerline_vpc" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = { Name = "ledgerline-${var.environment}-vpc" }
}

resource "aws_internet_gateway" "igw" {
  vpc_id = aws_vpc.ledgerline_vpc.id
}

resource "aws_subnet" "public_a" {
  vpc_id                  = aws_vpc.ledgerline_vpc.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "${var.aws_region}a"
  map_public_ip_on_launch = true
}

resource "aws_subnet" "public_b" {
  vpc_id                  = aws_vpc.ledgerline_vpc.id
  cidr_block              = "10.0.2.0/24"
  availability_zone       = "${var.aws_region}b"
  map_public_ip_on_launch = true
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.ledgerline_vpc.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw.id
  }
}

resource "aws_route_table_association" "a" {
  subnet_id      = aws_subnet.public_a.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "b" {
  subnet_id      = aws_subnet.public_b.id
  route_table_id = aws_route_table.public.id
}

# 2. Security Groups
resource "aws_security_group" "app_sg" {
  name        = "ledgerline-app-sg"
  description = "Allow inbound HTTP and internal traffic"
  vpc_id      = aws_vpc.ledgerline_vpc.id

  ingress {
    description = "HTTP to Ledger Service"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTP to Projection Service"
    from_port   = 8081
    to_port     = 8081
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "Prometheus Metrics"
    from_port   = 9090
    to_port     = 9090
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

resource "aws_security_group" "db_sg" {
  name        = "ledgerline-db-sg"
  description = "Allow PostgreSQL inbound from app VM"
  vpc_id      = aws_vpc.ledgerline_vpc.id

  ingress {
    description     = "PostgreSQL from App VM"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app_sg.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# 3. Managed Database: RDS PostgreSQL (db.t4g.micro)
resource "aws_db_subnet_group" "rds" {
  name       = "ledgerline-db-subnet-group"
  subnet_ids = [aws_subnet.public_a.id, aws_subnet.public_b.id]
}

resource "aws_db_instance" "postgres" {
  identifier             = "ledgerline-${var.environment}-db"
  engine                 = "postgres"
  engine_version         = "16.3"
  instance_class         = "db.t4g.micro"
  allocated_storage      = 20
  max_allocated_storage  = 50
  storage_type           = "gp3"
  db_name                = "ledgerline"
  username               = "ledger_admin"
  password               = var.db_password
  db_subnet_group_name   = aws_db_subnet_group.rds.name
  vpc_security_group_ids = [aws_security_group.db_sg.id]
  skip_final_snapshot    = true
  publicly_accessible    = false

  tags = { Environment = var.environment }
}

# 4. Container Registry: ECR repos for the two application images.
# CI does a two-phase apply: `terraform apply -target=aws_ecr_repository...` first so the
# repos exist to push into, then builds+pushes the images, then the full apply below bakes
# that exact image tag into the EC2 user_data so the instance can actually pull and run them
# on first boot — this is the part that was previously missing (user_data only installed Docker).
resource "aws_ecr_repository" "ledger_service" {
  name                 = "ledgerline/ledger-service"
  image_tag_mutability = "MUTABLE"
  force_delete         = true
}

resource "aws_ecr_repository" "projection_service" {
  name                 = "ledgerline/projection-service"
  image_tag_mutability = "MUTABLE"
  force_delete         = true
}

# 5. IAM: lets the EC2 instance pull from ECR and be reached via SSM Session Manager
# (no SSH key pair / open port 22 needed to run the smoke test or psql invariant queries).
resource "aws_iam_role" "app_host" {
  name = "ledgerline-app-host-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ecr_read" {
  role       = aws_iam_role.app_host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.app_host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "app_host" {
  name = "ledgerline-app-host-profile"
  role = aws_iam_role.app_host.name
}

# 6. Compute: App Host (t4g.small ARM64) running Kafka + ledger-service + projection-service
# + Prometheus via Docker Compose, pulled from ECR at boot.
data "aws_ami" "amazon_linux_2023" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-arm64"]
  }
}

resource "aws_instance" "app_host" {
  ami                    = data.aws_ami.amazon_linux_2023.id
  instance_type          = "t4g.small"
  subnet_id              = aws_subnet.public_a.id
  vpc_security_group_ids = [aws_security_group.app_sg.id]
  iam_instance_profile   = aws_iam_instance_profile.app_host.name

  user_data = templatefile("${path.module}/cloud-init.sh.tpl", {
    aws_region   = var.aws_region
    ecr_registry = local.ecr_registry
    image_tag    = var.image_tag
    db_endpoint  = aws_db_instance.postgres.endpoint
    db_password  = var.db_password
    jwt_secret   = var.jwt_secret
  })

  tags = {
    Name        = "ledgerline-app-host"
    Environment = var.environment
  }

  depends_on = [
    aws_ecr_repository.ledger_service,
    aws_ecr_repository.projection_service,
  ]
}

# Outputs
output "app_public_ip" {
  value       = aws_instance.app_host.public_ip
  description = "Public IP address of Ledgerline App Host"
}

output "ecr_ledger_service_repo_url" {
  value       = aws_ecr_repository.ledger_service.repository_url
  description = "ECR repository URL to push the ledger-service image to (do this before the full apply)"
}

output "ecr_projection_service_repo_url" {
  value       = aws_ecr_repository.projection_service.repository_url
  description = "ECR repository URL to push the projection-service image to (do this before the full apply)"
}

output "ledger_service_url" {
  value       = "http://${aws_instance.app_host.public_ip}:8080"
  description = "Ledger Service Base URL"
}

output "db_endpoint" {
  value       = aws_db_instance.postgres.endpoint
  description = "RDS PostgreSQL endpoint"
}

output "app_instance_id" {
  value       = aws_instance.app_host.id
  description = "EC2 instance id used for SSM smoke and RDS invariant checks"
}
