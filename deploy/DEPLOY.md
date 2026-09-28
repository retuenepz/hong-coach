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

顺便看一眼 `APP_PORT`（默认 8080）和 `DASHSCOPE_API_KEY`。
**DashScope key 留空也完全能跑** —— 应用会自动关掉 DashScope 的自动配置（一个请求都不会发），
AI 教练改用本地规则提示，下棋、引擎复盘、悔棋、摆棋全都不受影响。填了 key 才是真人陪练口吻的讲解。

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

    # 全站静态页 + JSON 接口，没有 WebSocket，普通反代即可
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

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

**没填 DashScope key，教练还会说话吗**

会，但换成本地的规则提示，比如「这步有点亏，更值得考虑的是『炮二平五』」——
内容来自引擎评分，只是没有大模型的自然语言润色。日志里不会出现任何大模型报错，
因为 key 为空时应用会在启动阶段就把 DashScope 整体关掉（`DashScopeAutoConfigGuard`），
连客户端 bean 都不创建。想开：在 `.env` 里填上 `DASHSCOPE_API_KEY` 再
`docker compose up -d` 重启即可。

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
