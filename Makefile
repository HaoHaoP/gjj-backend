.PHONY: up down restart logs ps build run test status clean help

# ── Docker Compose ──
up:
	docker compose up -d

down:
	docker compose down

restart:
	docker compose down && docker compose up -d

logs:
	docker compose logs -f --tail=50

ps:
	docker compose ps

# ── Backend ──
build:
	JAVA_HOME=$$(/usr/libexec/java_home -v 17) ./mvnw clean package -DskipTests -DfinalName=gjj-backend

run:
	java -jar target/gjj-backend.jar

test:
	JAVA_HOME=$$(/usr/libexec/java_home -v 17) ./mvnw test

# ── Utils ──
status:
	@echo "=== Service Ports ==="
	@for port in 5432 9000 7474 7687 8002 8080 19530 2379; do \
		pid=$$(lsof -ti :$$port 2>/dev/null); \
		if [ -n "$$pid" ]; then \
			proc=$$(ps -p $$pid -o comm= 2>/dev/null || echo "docker"); \
			echo "  :$$port  →  $$proc (pid $$pid)"; \
		else \
			echo "  :$$port  →  (free)"; \
		fi; \
	done

clean:
	docker compose down -v
	rm -rf target/

help:
	@echo "gjj-backend Makefile"
	@echo "  make up        启动所有 Docker 服务"
	@echo "  make down      停止所有 Docker 服务"
	@echo "  make restart   重启"
	@echo "  make logs      查看容器日志"
	@echo "  make ps        查看容器状态"
	@echo "  make build     编译后端"
	@echo "  make run       启动 Spring Boot"
	@echo "  make test      运行单测"
	@echo "  make status    检查各端口占用"
	@echo "  make clean     彻底清理（含数据卷）"
