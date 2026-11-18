# Seatbook · 校园自习室预约系统

一个可以在浏览器演示、也可以用 Swagger 调试的 Java 后端项目。管理员开放自习室的座位时段，学生注册登录后查看座位、提交预约、查看自己的记录并取消预约。页面和 API 由同一个 Spring Boot 服务提供。

| 模块 | 实现 |
| --- | --- |
| 账号 | 学生注册、管理员首次启动初始化、BCrypt 密码哈希、30 分钟 JWT、角色检查 |
| 排期 | 两间示例自习室、三个固定时段，按北京时间开放今天起 7 天 |
| 预约 | 同一座位与时段最多一单，同一用户同一时段最多一单 |
| 取消 | 时段开始前可取消，重复调用返回同一条记录，取消后释放名额 |
| 查询 | 按自习室、日期和时段查询座位，个人历史使用游标分页 |
| 工程 | Flyway 初始化和版本管理、Swagger UI、Actuator 健康检查、Docker Compose、GitHub Actions、真实 MySQL 集成测试 |

**技术栈**：Java 17 · Spring Boot 4.0 · Spring Security · Spring JDBC · MySQL/InnoDB · Flyway · Maven · JUnit。

## 运行

### 使用 Docker Compose

从包含 `pom.xml` 的项目根目录打开 PowerShell：

```powershell
.\scripts\New-LocalEnv.ps1
docker compose up --build -d
docker compose ps
```

初始化脚本创建 `.env` 并生成随机密码和 JWT 密钥。已有 `.env` 时直接复用。其他系统可以复制 `.env.example` 为 `.env` 并填写四个随机值，其中 `SEATBOOK_JWT_SECRET` 要填至少 32 个随机字节的 Base64 编码，例如用 `openssl rand -base64 32` 生成。

- 演示页面：`http://localhost:8080/`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`
- 健康检查：`http://localhost:8080/actuator/health`

如本地 8080 已占用，把 `.env` 的 `SEATBOOK_HOST_PORT` 改为可用端口。停止演示可运行 `docker compose down`，命名卷保留 MySQL 数据。管理员只在用户表中不存在 `admin` 时创建。

### 使用 Windows 本地 JDK、Maven 和 MySQL

1. 从项目根目录运行 `.\scripts\New-LocalEnv.ps1` 生成 `.env`。如果已经生成，使用现有文件。
2. 在 MySQL 8.0 或 8.4 中创建空库 `seatbook`（字符集 `utf8mb4`）。创建可从本机连接的应用账号 `seatbook`，密码取 `.env` 中的 `SEATBOOK_DB_PASSWORD`，授予它这个库的读写和迁移权限。
3. 从项目根目录执行：

```powershell
# 加载 .env 到当前 PowerShell 会话
Get-Content .env | ForEach-Object {
    $key, $value = $_ -split '=', 2
    if ($key -and $null -ne $value) { Set-Item -Path "Env:$key" -Value $value }
}

$env:SEATBOOK_DB_URL = 'jdbc:mysql://127.0.0.1:3306/seatbook'
$env:SEATBOOK_DB_USER = 'seatbook'
mvn spring-boot:run
```

可通过环境变量 `SEATBOOK_PORT` 修改本机端口。第一次启动时 Flyway 自动建表并导入两间示例自习室，不需要手动导入 SQL。也可以用 IntelliJ IDEA 打开 `pom.xml`，选择 JDK 17，并将相同的环境变量填入 `SeatbookApplication` 的运行配置。

## 试一遍完整流程

1. 用管理员用户名 `admin` 和 `.env` 中的 `SEATBOOK_ADMIN_PASSWORD` 登录。选择明天、任一自习室和时段，点击「开放所选时段」。
2. 退出后注册一个学生账号。在同一自习室和时段选任一可预约座位，在「我的预约」查看记录。
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

`period` 可用值为 `MORNING`、`AFTERNOON`、`EVENING`。`size` 范围为 1–100，下一页取上一次返回的 `nextCursor`。业务错误以 Problem Details 返回，常见状态为 400（参数错误）、401（未登录）、403（角色不匹配）、404（资源不可见）和 409（预约冲突或时段已关闭）。

## 复跑集成测试

准备独立的空数据库 `seatbook_test`，给测试账号授予该库权限。测试会迁移并插入测试数据。运行：

```powershell
$env:SEATBOOK_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:3306/seatbook_test'
$env:SEATBOOK_TEST_DB_USER = 'seatbook_test'
$env:SEATBOOK_TEST_DB_PASSWORD = '<测试数据库密码>'
mvn -Pmysql-it clean verify
```

其中一个测试会让 **8 个学生并发预约同一座位**，断言返回 1 个 201、7 个 409，且数据库里只有一条有效记录。其他用例覆盖身份认证、角色限制、重复放号、两座位同账号冲突、取消和释放、游标分页及开场时间边界。

[学习路线、源码导航和面试讲解](docs/learning.md)
