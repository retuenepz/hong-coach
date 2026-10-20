# 部署到自己的服务器

Spring Boot + MySQL 项目，走**自建 Linux 服务器 + Docker** 这条路：一条命令把应用和
数据库一起拉起来，服务器上不用装 JDK 和 MySQL。

---

## 最短路径

服务器上依次执行（第一次约 10 分钟，主要花在下依赖和编译上）：

```bash
cd hong-coach                              # 先把代码弄上来：见下面「把代码放到服务器上」
sudo apt install -y p7zip-full             # CentOS / Rocky: sudo yum install -y p7zip
cp .env.example .env && vim .env           # 改掉 DB_PASSWORD / DB_ROOT_PASSWORD 这两行
bash deploy/fetch-engine.sh                # 下载 Linux 版引擎（约 51MB）
docker compose up -d --build
```

然后浏览器开 `http://<服务器IP>:8080/`。

跑之前先确认这两件事，它们是最容易卡住的地方：

```bash
uname -m                  # 必须是 x86_64
docker compose version    # 要有 v2（不是 docker-compose 老版）
```

> **服务器是 ARM（aarch64）？** 官方 Pikafish 没有 Linux ARM 二进制，这条路走不通。
> 先看「常见问题」里的「服务器是 ARM 架构」一节，别急着往下做。

---

## 前置条件

| 项目 | 要求 |
|---|---|
| 服务器 | Linux **x86-64**，建议 ≥ 2 核 4G（引擎分析吃 CPU，1 核也能跑但会排队） |
| Docker | 20.10+，以及 `docker compose`（v2，装 docker-ce 自带） |
| 磁盘 | ≥ 5G（镜像约 500MB + MySQL 数据 + 引擎 57MB） |
| 端口 | 放行一个对外端口（默认 `8080`），面板/安全组都要开 |
| 网络 | 能到 GitHub（下引擎）、Docker Hub（拉基础镜像）、Maven 仓库（编译依赖） |

确认 Docker 装好了（已经装过就跳过）：

```bash
docker version && docker compose version
```

万一没装：

```bash
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
```

### 把代码放到服务器上

两条路，按你的情况选。

**A. 打包传上去（最省事）** —— 不要求服务器能访问 GitHub，也不用配任何密钥。
对本地已经有完整代码的人来说这是最快的：

```bash
# 在开发机上、项目目录的上一级执行。
# 别把 target/ 一起传：里面有本地编译出来的 150MB jar，容器里会重新编译。
# .env 也排除 —— 那里面是本机验证用的密码，服务器上要从 .env.example 重新填一份。
tar --exclude=target --exclude=logs --exclude=tmp --exclude=.workbuddy \
    --exclude=.env -czf hong-coach.tar.gz hong-coach
scp hong-coach.tar.gz user@server:/opt/
ssh user@server 'cd /opt && tar xzf hong-coach.tar.gz'
```

**B. 服务器上 git clone** —— 以后想在服务器上直接拉更新就选这条。

**重要：服务器上通常没有你 GitHub 的 SSH 密钥**，直接 `git clone git@github.com:...`
会报 `Permission denied (publickey)`。两种解法：

1. **HTTPS + 访问令牌**（私有仓库必须带令牌，否则会提示要密码而 GitHub 早已不支持密码）：

   ```bash
   git clone https://<用户名>:<令牌>@github.com/retuenepz/hong-coach.git
   ```

   令牌在 GitHub → Settings → Developer settings → Personal access tokens 生成，勾 `repo` 权限。

2. **配只读部署密钥**：服务器上执行 `ssh-keygen -t ed25519`，把生成的
   `~/.ssh/id_ed25519.pub` 内容加到仓库的 Settings → Deploy keys（**不要**勾写入权限）。

另外 GitHub 在国内服务器上经常连不上（clone 卡住或超时），拉不动就直接用 A 方案。

### 国内服务器的网络（重要）

不处理这三件事，`docker compose up --build` 多半会卡在拉镜像或编译上。

**1）Docker Hub 拉不动** —— 配镜像加速器，编辑 `/etc/docker/daemon.json`
（没有就新建）：

```json
{
  "registry-mirrors": ["https://docker.1ms.run", "https://docker.xuanyuan.me"]
}
```

```bash
sudo systemctl restart docker
```

加速站经常换，上面这俩不通就搜一个当前可用的换上。验证办法很简单：

```bash
docker pull mysql:8.0     # 能拉下来就说明配对了
```

注意网上大量教程里还在流传的这几个加速站**都已经停止服务了**，配上等于没配：
`dockerhub.azk8s.cn`（2020 年就关了）、`reg-mirror.qiniu.com`、`registry.docker-cn.com`、
`hub-mirror.c.163.com`。如果 `docker pull` 一直卡着不动，先 `docker info` 看一眼
`Registry Mirrors` 里是不是只剩这些死站。

**2）Maven 依赖拉得慢** —— 已经内置好了，不用管。`Dockerfile` 的构建阶段会用
`deploy/maven-settings.xml` 把中央仓库指向阿里云公共镜像。服务器在海外、或者想走
官方源，把 `Dockerfile` 里这行删掉即可：

```dockerfile
COPY deploy/maven-settings.xml /root/.m2/settings.xml
```

**3）GitHub 下引擎失败** —— 脚本会明确告诉你（不会留下半个残包，见下面的校验）。
换源重试：

```bash
PIKAFISH_URL="https://ghfast.top/https://github.com/official-pikafish/Pikafish/releases/download/Pikafish-2026-09-06/Pikafish.2026-09-06.7z" \
  bash deploy/fetch-engine.sh
```

或者干脆在能上网的机器上下好那个 7z 包，解出 `Pikafish-Linux-x86-64-universal` 和
`pikafish.nnue` 两个文件，scp 到服务器项目的 `./engine/` 目录里，跳过这一步。

---

## 方式一：Docker（推荐）

代码已经放到服务器上了（见前面「把代码放到服务器上」），接着：

### 第 1 步：填环境变量

```bash
cp .env.example .env
vim .env
```

至少要改这两行，换成你自己的强密码：

```ini
DB_PASSWORD=改成你的应用密码
DB_ROOT_PASSWORD=改成你的 root 密码
```

顺便看一眼 `APP_PORT`（默认 8080）、`COACH_PROVIDER`（默认 `deepseek`）和对应的那个大模型 key。
**大模型 key 留空也完全能跑** —— 应用会把大模型整体关掉（一个请求都不会发），
AI 教练改用本地规则提示，下棋、引擎复盘、悔棋、摆棋全都不受影响。填了 key 才是真人陪练口吻的讲解。

`COACH_PROVIDER` 三选一，想换厂商只改这一行：

| 值 | 用哪家的 key | 说明 |
| --- | --- | --- |
| `deepseek` | `DEEPSEEK_API_KEY` | 默认。可再用 `DEEPSEEK_MODEL` 选 `deepseek-flash`（默认，便宜快）或 `deepseek-v4-pro`（更强） |
| `dashscope` | `DASHSCOPE_API_KEY` | 阿里云百炼 |
| `none` | —— | 明确不要大模型，只走本地提示 |

**注意别把 key 填错家**：选 `deepseek` 却只填了 `DASHSCOPE_API_KEY`，等于没配 key，教练会静默降级。
改完可以用 `curl -s localhost:8080/api/game/status` 看 `coachProvider` 和 `coachAvailable` 确认。

**思考量：默认已经关掉，别去开它**（`COACH_REASONING_EFFORT`，默认 `none`）

DeepSeek 现在提供的两个模型都是**推理模型**：它会先把棋局在脑子里推演一遍，才开始说正文。
可我们这个场景里棋是引擎算的，模型只负责把结论翻译成人话，这一遍推演纯属白等 ——
实测同一个提问，不关思考要 **8.3 秒**才蹦出第一个字，关掉后 **1.3 秒**（开局致辞 0.6 秒）。
`none` 就是关掉它，这是「首字快慢」上最大的一根杠杆。
想让它重新推演（点评的细节会多一些），设成 `low` / `high` / `max`，代价是首字重新变慢。

大模型超时也有几个可调的变量，一般不用动：

| 变量 | 默认 | 管什么 |
| --- | --- | --- |
| `COACH_FIRST_BYTE_TIMEOUT_MS` | 30000 | 发给 DeepSeek 的请求「到响应头为止」的超时，含 TLS 握手与服务端排队 |
| `COACH_TIMEOUT_MS` | 40000 | 应用层面「两段输出之间最多停顿多久」，超了就用本地提示 |
| `COACH_STREAM_MAX_MS` | 180000 | 一整条流的最长时长 |
| `COACH_HTTP_READ_TIMEOUT_MS` | 120000 | 走 Spring AI 那条路（百炼）的读超时 |
| `COACH_HTTP_CONNECT_TIMEOUT_MS` | 10000 | 同上，连接超时 |

**这几个超时都别往小改** —— 小过模型的首字延迟，每次调用都会被误判成失败
（原因见下面「日志里报 SocketTimeoutException」）。

### 第 2 步：下载 Linux 版引擎

仓库里 `src/main/resources/engine/` 那份是 **Windows 的 `pikafish-avx2.exe`**，Linux 上跑不了，
而且它不进仓库。所以要在服务器上单独下一份：

```bash
sudo apt install -y p7zip-full     # CentOS: sudo yum install -y p7zip
bash deploy/fetch-engine.sh
```

这一步会从 Pikafish 官方 GitHub Release 下载 `Pikafish.2026-09-06.7z`，解出两个文件放进 `./engine/`：

- `Pikafish-Linux-x86-64-universal` —— 通用二进制，自动识别 CPU 指令集（AVX2/BMI2/SSE4.1 都能跑）
- `pikafish.nnue` —— NNUE 权重（50MB），**引擎必需**，缺了会直接退出

脚本有三道自检，都是为了把「报错看不懂」变成「报错能照着做」：

1. **先看 CPU 架构** —— 不是 x86_64 直接停下来告诉你去编译（官方没有 Linux ARM 版）
2. **再看包体积** —— 不到 20MB 就判定没下全，并给出换源命令。
   GitHub 不通时最常见的「假成功」就是拿到一个 HTML 错误页，被这一关挡在解压之前
3. **最后冒烟测试** —— `uci` + 试搜一步，看到 `引擎工作正常：bestmove …` 就算对了。
   这一步会真的加载 nnue，权重放错目录在这里就会露出来

> 网络到不了 GitHub 的话，手动下载同一个 release 的 7z 包，把上面两个文件解到 `./engine/` 即可。
> 也可以到 [pikafish.org](http://pikafish.org) 的「For Experts / 纯引擎文件」下载。

### 第 3 步：起服务

```bash
docker compose up -d --build
```

第一次会编译镜像，几分钟。看日志：

```bash
docker compose logs -f app
```

出现这两行就说明都对了：

```
引擎就绪：2 个实例，可执行文件 /opt/engine/Pikafish-Linux-x86-64-universal
数据库表结构已就绪（chess_game / chess_move / chess_foul / chess_chat_message）
```

表结构**不需要手工导入** —— 应用启动时会自己执行 `db/schema.sql`，全是
`CREATE TABLE IF NOT EXISTS`，重复执行无副作用。

### 第 4 步：验证

```bash
curl -I http://127.0.0.1:8080/play.html      # 期望 200
curl -s http://127.0.0.1:8080/api/game/status   # 引擎 / 教练 / 数据库的体检结果
```

然后浏览器开 `http://<服务器IP>:8080/`，根路径会自动跳到对局页。
随手走一步，再点「电脑走一步」，能应招就说明引擎通了。

---

## 方式二：不用 Docker（裸机 + systemd）

服务器上如果已经有 JDK 17 和 MySQL 8，可以只跑 jar。

### 1. 准备数据库

```sql
CREATE DATABASE chess_coach_db DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'chess'@'%' IDENTIFIED BY '你的密码';
GRANT ALL PRIVILEGES ON chess_coach_db.* TO 'chess'@'%';
FLUSH PRIVILEGES;
```

表不用建，应用启动时会自己建。

### 2. 打包并上传

在开发机（Windows 上就是本项目目录）执行：

```bash
mvn -DskipTests package
# 产物：target/hong-coach-1.0-SNAPSHOT.jar
```

传到服务器，比如 `/opt/hong-coach/app.jar`。引擎同样用 `deploy/fetch-engine.sh /opt/hong-coach/engine` 准备好。

### 3. 环境变量文件

`/opt/hong-coach/hong-coach.env`（权限设 `600`）：

```ini
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=chess_coach_db
DB_USER=chess
DB_PASSWORD=你的密码
COACH_PROVIDER=deepseek
DEEPSEEK_API_KEY=
DEEPSEEK_MODEL=deepseek-flash
COACH_REASONING_EFFORT=none
DASHSCOPE_API_KEY=
ENGINE_PATH=/opt/hong-coach/engine
ENGINE_EXE=Pikafish-Linux-x86-64-universal
ENGINE_NNUE=pikafish.nnue
ENGINE_POOL_SIZE=2
```

```bash
chmod 600 /opt/hong-coach/hong-coach.env
```

### 4. systemd 服务

`/etc/systemd/system/hong-coach.service`：

```ini
[Unit]
Description=hong-coach 中国象棋教练
After=network.target mysql.service

[Service]
Type=simple
User=hongcoach
WorkingDirectory=/opt/hong-coach
EnvironmentFile=/opt/hong-coach/hong-coach.env
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75 -jar /opt/hong-coach/app.jar

Restart=always
RestartSec=5
StandardOutput=append:/opt/hong-coach/logs/stdout.log
StandardError=append:/opt/hong-coach/logs/stderr.log

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd -r -s /usr/sbin/nologin hongcoach
sudo mkdir -p /opt/hong-coach/logs
sudo chown -R hongcoach:hongcoach /opt/hong-coach
sudo systemctl daemon-reload
sudo systemctl enable --now hong-coach
sudo systemctl status hong-coach
```

---

## 挂域名 + HTTPS（可选，但建议）

应用本身没做 TLS，正式对外建议前面放一层 Nginx。

```nginx
server {
    listen 80;
    server_name chess.example.com;

    # 静态页 + JSON 接口 + AI 教练的流式讲解
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # ↓↓↓ 这三行别删 ↓↓↓
        # 教练的讲解是一个字一个字推过来的（Server-Sent Events）。nginx 默认开着
        # proxy_buffering，会把上游的响应攒够一块再转发 —— 那样「逐字往外跳」就被吞掉了，
        # 用户还是要等整段生成完才看到第一句话，跟没改一样。
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;      # 一条流可能持续一两分钟，别被默认的 60s 掐断
    }
}
```

应用自己也会在流式响应上带一个 `X-Accel-Buffering: no` 头（nginx 认得它），
上面几行是双保险 —— 换个反代软件（Caddy 默认就不缓冲，Apache 要看 `mod_proxy` 配置）时
记得确认同样的事。

（`application-prod.yml` 里已经开了 `server.forward-headers-strategy: framework`，
所以反代后的真实访客 IP 能正确记进日志。）

再上证书：

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d chess.example.com
```

如果走 Nginx，记得把 compose 里的端口收敛到本机，别让 8080 直接暴露：

```yaml
ports:
  - "127.0.0.1:${APP_PORT:-8080}:8080"
```

同时确认服务器防火墙 / 云安全组只放行 80、443（和 SSH）。

---

## 日常运维

```bash
docker compose logs -f app          # 看日志
docker compose restart app          # 重启应用（不动数据库）
docker compose down                 # 停机（数据留在 mysql-data 卷里）
docker compose up -d --build        # 改了代码后重新构建并上线
```

`docker compose logs` 是主力 —— 应用的 logback 同时往控制台和文件写。
文件日志留在容器里的 `/app/logs/`（按天滚动，保留 30 天），需要翻历史时可以：

```bash
docker compose exec app tail -100 /app/logs/hong-coach.log
```

**备份**对局数据：

```bash
docker compose exec mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" chess_coach_db' > backup-$(date +%F).sql
```

**恢复**：

```bash
cat backup-2026-09-28.sql | docker compose exec -T mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" chess_coach_db'
```

---

## 常见问题

**服务器是 ARM 架构（`uname -m` 是 `aarch64`）**

官方 Release 里只有这四个二进制：Windows x86-64、Linux x86-64、MacOS、Android arm64。
**没有 Linux ARM 版**，`fetch-engine.sh` 会在下载前直接拦下来，不会白等。

两条路：

1. **从源码编译**（推荐，引擎在 ARM 上跑得挺好）：

   ```bash
   sudo apt install -y git make g++    # 或 yum install -y git make gcc-c++
   git clone https://github.com/official-pikafish/Pikafish.git
   cd Pikafish/src && make -j"$(nproc)" build ARCH=armv8
   # 产物是 Pikafish/src/Pikafish，权重文件从官方 release 里取 pikafish.nnue
   ```

   编译可能需要在 `Makefile` 里调 `ARCH`（`armv8` / `armv8-dotprod`，后者要求 CPU 支持
   dot product 指令）。编好后把可执行文件（建议改名成 `Pikafish-Linux-arm64`）和
   `pikafish.nnue` 一起放进 `./engine/`，并同步改 `.env` / compose 里的 `ENGINE_EXE`。

2. **先不带引擎部署** —— 应用没有引擎**不会崩**，摆棋、手动走子、悔棋全都正常，
   只有「电脑走一步」和引擎复盘不可用。想先把站点跑起来给人看，这条路几分钟就够。

**`docker compose up` 起来了，但引擎读不到（CentOS / RHEL / openEuler 上）**

这些发行版默认开着 SELinux，容器访问宿主机上的 `./engine` 目录会被拦掉。先确认：

```bash
getenforce          # 输出 Enforcing 就是它在拦
```

解决办法是给挂载加 SELinux 标签，改 `docker-compose.yml`：

```yaml
volumes:
  - ./engine:/opt/engine:ro,z     # z = 共享标签（Z 则是私有标签）
```

再 `docker compose up -d` 重建容器。（Ubuntu / Debian 默认没装 SELinux，不会有这个问题。）

**引擎不可用（页面提示「引擎不可用」，或日志里 `找不到引擎可执行文件`）**

按顺序查：

1. `./engine/` 目录里到底有没有这两个文件 —— `ls -lh engine/`
2. 可执行文件有没有执行权限：`chmod 755 engine/Pikafish-Linux-x86-64-universal`
3. `ENGINE_EXE` 的名字和文件名**一字不差**（默认 `Pikafish-Linux-x86-64-universal`）
4. `pikafish.nnue` 必须和可执行文件在**同一个目录**，缺了引擎会直接退出
5. 手动试：`cd engine && printf 'uci\nquit\n' | ./Pikafish-Linux-x86-64-universal`

应用在没有引擎时**不会崩**，只是「电脑走一步」和复盘讲解不可用，摆棋和下棋照常。

**引擎日志里报 `version 'GLIBC_2.38' not found`（改过基础镜像的话）**

Pikafish 官方 Linux 二进制要求 **GLIBC ≥ 2.38**。`Dockerfile` 的运行阶段用的是
`eclipse-temurin:17-jre-noble`（Ubuntu 24.04，glibc 2.39），**不能改回 `-jammy`** ——
Ubuntu 22.04 只有 glibc 2.35，引擎会在启动时报：

```
/opt/engine/Pikafish-Linux-x86-64-universal: /lib/x86_64-linux-gnu/libc.so.6:
version `GLIBC_2.38' not found (required by ...)
```

当时的表现是：应用正常起来、体检接口显示 `engineAvailable: false`，日志里只有一句
「找不到可用的引擎」—— 容易误以为是引擎文件没放对。确认办法是在容器里看 glibc 版本：

```bash
docker compose exec app ldd --version | head -1     # 要 >= 2.38
```

排查顺序建议先看这个，再看引擎文件本身。裸机部署同理 —— 系统要是 CentOS 7
（glibc 2.17）这类老发行版，引擎一样跑不起来。

**应用起来了但连不上数据库**

日志里会有一行 `建表失败，对局将只保存在内存里，重启后丢失`。这是**刻意设计**的 ——
数据库可选，连不上也能下棋，只是刷新页面后对局丢失。查：

```bash
docker compose ps                    # mysql 是不是 healthy
docker compose exec mysql mysql -uroot -p -e 'SHOW DATABASES;'
```

**大模型日志里报 `SocketTimeoutException`，教练老是退回本地提示**

请求其实发出去了、响应头也回来了，只是**读 body 时超时**，看着像网络不通。多半是超时值
被压到了 10 秒：Spring Boot 3.2 建 `RestClient` 时用的是各 HTTP 客户端自带的默认值，而
classpath 上有 OkHttp（百炼 SDK 带进来的传递依赖）时它会被探测选中，OkHttp 默认读超时正好
10 秒，大模型的首字延迟却经常超过 10 秒。项目里 `config/LlmHttpClientConfig` 已经换掉这个
客户端并显式设了超时（默认 120 秒）。如果还报同样的错，按顺序查：

1. `.env` 里的 `COACH_HTTP_READ_TIMEOUT_MS` 是不是被改小了（它必须大于模型的首字延迟）；
2. 模型太慢：把 `COACH_TIMEOUT_MS`（应用层面等多久，默认 40 秒）一并调大，
   或先换成 `DEEPSEEK_MODEL=deepseek-flash`；
3. 服务器出网是否正常：`curl -sv https://api.deepseek.com 2>&1 | head`；
   走了代理的话，别忘了给容器也配上 `HTTP_PROXY` / `HTTPS_PROXY`。

**讲解不是逐字蹦出来的，要等好几秒才整段冒出来**

说明中间有东西在攒响应。按顺序查：

1. **反向代理没关缓冲** —— 最常见。nginx 默认 `proxy_buffering on`，会把上游的流攒够一块再转发，
   应用自带的 `X-Accel-Buffering: no` 响应头救不了显式配了缓冲的写法。
   见上面「挂域名 + HTTPS」那段的 `proxy_buffering off;`；
2. 还有没有别的中间层：公司网关、云厂商的负载均衡、CDN，都可能缓冲，挨个确认；
3. 用 curl 对照一下（`-N` 关掉 curl 自己的缓冲，应当一个字一个字往外冒）：

   ```bash
   curl -sN -X POST localhost:8080/api/game/chat/你的会话id/stream \
        -H 'Content-Type: application/json' -d '{"question":"我该怎么走？"}'
   ```

   如果这条命令是逐字的、网页不是，那问题一定在浏览器和容器之间的某一层。

**没填大模型 key，教练还会说话吗**

会，但换成本地的规则提示，比如「这步有点亏，更值得考虑的是『炮二平五』」——
内容来自引擎评分，只是没有大模型的自然语言润色。日志里不会出现任何大模型报错，
因为 key 为空时应用压根不会创建客户端（见 `config/CoachLlmConfig`），一个请求都不会发。
想开：在 `.env` 里填上**当前 `COACH_PROVIDER` 对应的那个** key
（默认是 `DEEPSEEK_API_KEY`）再 `docker compose up -d` 重启即可。

**怎么确认现在用的是哪家模型**

```bash
curl -s localhost:8080/api/game/status
```

`coachProvider` 是实际生效的厂商，`coachAvailable` 表示教练能不能说话，`coach` 是一句可读状态。几个常见结果：

| 返回 | 含义 |
| --- | --- |
| `coachProvider: "deepseek"` + `coachAvailable: true` | 正常在用 DeepSeek |
| `coachProvider: "deepseek"` + `coachAvailable: false` | 选的是 DeepSeek，但没配 key（或 `COACH_ENABLED=false`），教练走本地提示 |
| `coachProvider: "dashscope"` | 当前切到了百炼 |
| `coachProvider: "none"` | `COACH_PROVIDER` 写成了不认识的值 |

**为什么教练要等好几秒才出字**（老版本的症状）

分两种，先看是哪一种：

1. **第一个字就要等 4~8 秒**，出来之后哗哗地往外蹦 —— 这是模型的「思考时间」。
   DeepSeek 的模型默认会先把棋推演一遍才开口，而正文在思考结束前一个字都不吐。
   本项目已默认把思考关掉（`COACH_REASONING_EFFORT=none`），正常情况下第一个字在 1 秒上下。
   如果又变慢了，先确认这个变量没被改过。
2. **整段话要等好几秒之后一次性冒出来** —— 那是中间有东西在攒响应，见下一条。

**能不能两个 key 都填**

可以，但同一时刻只有一个生效，由 `COACH_PROVIDER` 决定。别指望它「谁便宜用谁」——
没有自动故障转移，DeepSeek 挂了不会自动切百炼。

**同时下棋的人多了会排队**

引擎池大小 `ENGINE_POOL_SIZE` 决定能同时服务几局，每个实例占一个 CPU 核。
2 核机器设 1~2；4 核设 3~4。设太大反而会因为争抢 CPU 让每一步都变慢。

**每一步想更快 / 更强**

`ENGINE_PLAY_MOVETIME` 是电脑落子的思考时间（毫秒，默认 600）。
调到 1500 会明显更强，代价是每次「电脑走一步」要等 1.5 秒。

**内存不够被 OOM 杀掉**

容器启动参数是 `-XX:MaxRAMPercentage=75`，即最多用容器内存的 75%。
1G 小机器建议换 2G 以上，或者调小 `ENGINE_POOL_SIZE`。

---

## 与「发布为应用」的关系

WorkBuddy 内置的「发布为应用」走的是托管沙箱，只支持 Node.js / Python / Go / 静态站点，
**没有 JVM 也没有 MySQL**，所以这个 Java 项目不能被它托管 —— 这也是走自建服务器的原因。
如果哪天想省掉运维，可以把规则校验和棋盘交互改写成纯前端、发布成静态页，
但引擎（Pikafish 是本地进程）和 AI 教练就得上云函数另行安排了。
