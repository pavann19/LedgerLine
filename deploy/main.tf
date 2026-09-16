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
  default     = "LedgerlineSecure2026!"
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

# 4. Compute: App Runner Host (t4g.small ARM64 with Docker & Kafka container)
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

  user_data = <<-EOF
              #!/bin/bash
              dnf update -y
              dnf install -y docker git
              systemctl enable --now docker
              usermod -aG docker ec2-user
              EOF

  tags = {
    Name        = "ledgerline-app-host"
    Environment = var.environment
  }
}

# Outputs
output "app_public_ip" {
  value       = aws_instance.app_host.public_ip
  description = "Public IP address of Ledgerline App Host"
}

output "ledger_service_url" {
  value       = "http://${aws_instance.app_host.public_ip}:8080"
  description = "Ledger Service Base URL"
}

output "db_endpoint" {
  value       = aws_db_instance.postgres.endpoint
  description = "RDS PostgreSQL endpoint"
}
