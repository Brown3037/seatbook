# Seatbook · 校园自习室预约系统

一个可以在浏览器演示、也可以用 Swagger 调试的 Java 后端项目。管理员开放自习室的座位时段，学生注册登录后查看座位、提交预约、查看自己的记录并取消预约。页面和 API 由同一个 Spring Boot 服务提供。

| 模块 | 实现 |
| --- | --- |
| 账号 | 学生注册、管理员首次启动初始化、BCrypt 密码哈希、30 分钟 JWT、角色检查 |
| 排期 | 两间示例自习室、三个固定时段，按北京时间开放今天起 7 天 |
| 预约 | 同一座位与时段最多一单，同一用户同一时段最多一单 |
| 取消 | 时段开始前可取消，重复调用返回同一条记录，取消后释放名额 |
| 查询 | 按自习室、日期和时段查询座位，个人历史使用游标分页 |
| Redis | 座位快照缓存、15 秒过期、提交后按时段失效、连接异常时查 MySQL |
| RabbitMQ | 异步预约通知、事务 Outbox、发布确认、失败重试、消费去重、死信队列 |
| 工程 | Flyway 版本管理、Swagger UI、Actuator 健康检查、Docker Compose、GitHub Actions、真实 MySQL / Redis / RabbitMQ 集成测试 |

**技术栈**：Java 17 · Spring Boot 4.0 · Spring Security · Spring JDBC · MySQL/InnoDB · Spring Data Redis · Spring AMQP / RabbitMQ · Flyway · Maven · JUnit。

## 运行

### 使用 Docker Compose

从包含 `pom.xml` 的项目根目录打开 PowerShell：

```powershell
.\scripts\New-LocalEnv.ps1
docker compose up --build -d
docker compose ps
```

初始化脚本创建 `.env` 并生成随机密码和 JWT 密钥。已有 1.0 版 `.env` 的情况下，保留原值并补上 `SEATBOOK_REDIS_PASSWORD` 和 `SEATBOOK_RABBITMQ_PASSWORD` 两个独立的随机值。其他系统可以复制 `.env.example` 为 `.env` 并填写密码与密钥；`SEATBOOK_JWT_SECRET` 要填至少 32 个随机字节的 Base64 编码，例如用 `openssl rand -base64 32` 生成。

- 演示页面：`http://localhost:8080/`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`
- 健康检查：`http://localhost:8080/actuator/health`
- RabbitMQ 管理台：`http://localhost:15672/`，用户名 `seatbook`，密码取 `.env` 中的 `SEATBOOK_RABBITMQ_PASSWORD`。

如本地 8080 或 15672 已占用，修改 `.env` 中的 `SEATBOOK_HOST_PORT` 或 `SEATBOOK_RABBITMQ_UI_PORT`。运行 `docker compose down` 停止演示，命名卷保留 MySQL 和 RabbitMQ 数据。管理员只在用户表中不存在 `admin` 时创建。

### 使用本地 JDK 和 Maven

1. 从项目根目录运行 `.\scripts\New-LocalEnv.ps1` 生成 `.env`。如果已经生成，使用现有文件。
2. 在 MySQL 8.0 或 8.4 中创建空库 `seatbook`（字符集 `utf8mb4`）。创建可从本机连接的应用账号 `seatbook`，密码取 `.env` 中的 `SEATBOOK_DB_PASSWORD`，授予它这个库的读写和迁移权限。
3. 准备可连接的 Redis 和 RabbitMQ。默认端口分别为 6379 和 5672。非本机实例通过 `SEATBOOK_REDIS_HOST`、`SEATBOOK_REDIS_PORT`、`SEATBOOK_RABBITMQ_HOST` 和 `SEATBOOK_RABBITMQ_PORT` 配置。
4. 从项目根目录执行：

```powershell
# 加载 .env 到当前 PowerShell 会话
Get-Content .env | ForEach-Object {
    $key, $value = $_ -split '=', 2
    if ($key -and $null -ne $value) { Set-Item -Path "Env:$key" -Value $value }
}

$env:SEATBOOK_DB_URL = 'jdbc:mysql://127.0.0.1:3306/seatbook'
$env:SEATBOOK_DB_USER = 'seatbook'
$env:SEATBOOK_RABBITMQ_USER = 'seatbook'  # 改为你的 RabbitMQ 应用用户
mvn spring-boot:run
```

可通过环境变量 `SEATBOOK_PORT` 修改本机端口。第一次启动时 Flyway 自动建表并导入两间示例自习室，不需要手动导入 SQL。也可以用 IntelliJ IDEA 打开 `pom.xml`，选择 JDK 17，并将相同的环境变量填入 `SeatbookApplication` 的运行配置。

## 试一遍完整流程

1. 用管理员用户名 `admin` 和 `.env` 中的 `SEATBOOK_ADMIN_PASSWORD` 登录。选择明天、任一自习室和时段，点击「开放所选时段」。
2. 退出后注册一个学生账号。在同一自习室和时段选任一可预约座位，在「我的预约」查看记录。「预约通知」会定时刷新，也可以手动刷新。
3. 用另一账号尝试在同一时段预约同一座位。页面会显示该座位不可选，直接打 API 也会收到 409。原账号取消后，第二个账号即可预约。
4. 想在 Swagger 调试时，用 `POST /api/auth/login` 获取 `accessToken`，再用 **Authorize** 输入该 token。管理员和学生需要各用自己的 token。

## API 一览

| 方法和路径 | 权限 | 用途 |
| --- | --- | --- |
| `POST /api/auth/register` | 公开 | 创建学生账号 |
| `POST /api/auth/login` | 公开 | 获取 Bearer token |
| `GET /api/schedule` | 公开 | 查看校区日期、窗口与时段 |
| `GET /api/rooms` | 公开 | 查看自习室和座位数量 |
| `GET /api/rooms/{roomId}/slots?date=YYYY-MM-DD&period=MORNING` | 公开 | 查看指定排期 |
| `PUT /api/admin/rooms/{roomId}/slots` | 管理员 | 批量开放座位，提交 `date`、`period` |
| `POST /api/bookings` | 学生 | 预约座位，提交 `slotId` |
| `GET /api/bookings?size=20&beforeId=...` | 学生 | 个人历史，首次省略 `beforeId` |
| `PUT /api/bookings/{bookingId}/cancellation` | 预约人 | 取消并返回记录 |
| `GET /api/notifications` | 学生 | 查询本人最近 20 条预约通知 |

`period` 可用值为 `MORNING`、`AFTERNOON`、`EVENING`。`size` 范围为 1–100，下一页取上一次返回的 `nextCursor`。业务错误以 Problem Details 返回，常见状态为 400（参数错误）、401（未登录）、403（角色不匹配）、404（资源不可见）和 409（预约冲突或时段已关闭）。

## 复跑集成测试

准备独立的空数据库 `seatbook_test`，并使用专用 Redis 和 RabbitMQ 实例。测试会插入测试数据，并临时移除再恢复测试队列的路由绑定。通过前面的环境变量配置 Redis 和 RabbitMQ，再运行：

```powershell
$env:SEATBOOK_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:3306/seatbook_test'
$env:SEATBOOK_TEST_DB_USER = 'seatbook_test'
$env:SEATBOOK_TEST_DB_PASSWORD = '<测试数据库密码>'
mvn -Pintegration clean verify
```

测试覆盖 **8 个学生并发预约同一座位**、身份认证、权限、重复放号和取消、分页及开场时间边界。新增测试验证 Redis 命中和提交后失效、RabbitMQ 重复投递去重、路由中断后的补发，以及无效消息进入死信队列。

[学习路线、源码导航和面试讲解](docs/learning.md)
