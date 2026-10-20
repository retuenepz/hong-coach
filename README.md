# hong-coach

每次去公园跟老头下棋，总是被打的落花流水。
于是痛定思痛，决定提升棋艺。但是又苦于没有老师，于是乎想到了用 AI 来教自己下棋的办法。
尝试一下看看效果如何，能不能干得过公园老大爷，成败在此一举。
我！~要！~变！~强！

## 现在能做什么

两个页面：

**`/play.html` —— 下棋**

进去先选边（决定棋盘朝向与教练怎么称呼你），然后：

- **红黑都由你走**：轮到哪一方就能动哪一方的棋子 —— 可以自己跟自己摆变化，也可以复演名局。
- **电脑走一步**：电脑由 Pikafish 驱动，但**不会自动出手**。点一下「电脑走一步」，
  它就替**当前该走的那一方**走一手（轮红替红走，轮黑替黑走）。走一步要点一次。
- **走亏棋被主动提醒**：你手动走的每步棋前后各让引擎评估一次，评分落差超过阈值（默认 0.8 个兵）
  教练就主动开口，指出问题并给出更好的着法。评分按**走子方**的视角算，红黑互不串味。
- **随时提问**：右侧对话窗口可以直接问「我该怎么走」「现在谁占优」，
  教练会结合当前盘面、引擎评分与主变回答。
- **悔棋不限次数**：点「悔棋」退回最近的一手，一路能退到本局开局那个局面。
  电脑替你应过一手时，一次会把它的应招和你上一步**一起**退掉 —— 也就是直接回到你落子之前；
  其余情况退一手。已经分出胜负的棋局也能悔回来接着下。悔掉的电脑着法，对话里那条
  「我替你走一步」会一并摘掉，不留自相矛盾的记录。
- **换边**：对局中途改成我执另一方 —— 棋盘翻过来，教练换个称呼。棋局一步都不动。
- **翻转视角**：只是把棋盘转过来看，不影响我执哪边。
- **刷新不丢局**：对局、棋谱、违规记录、对话都落库，刷新页面接着下。
- **走子有手感**：棋盘和棋子都是矢量现画的（不是图片），所以走子会飞过去、带拖尾，
  走完在盘上留一条虚线轨迹标出从哪到哪；吃子会把对方的子打飞并迸出碎屑，棋盘跟着震一下。
  落子声用 Web Audio 现场合成，右下角可以关。样式全部改成了代码里的几个颜色常量，
  想换棋子长相不用重画图。

**`/setup.html` —— 摆棋**

- 从托盘里点棋子、往棋盘上落，摆出任意的中残局；右键擦掉一格，可随时翻转视角。
  点棋盘上已有的棋子可以把它拿起来，再点别处就是挪过去。
- **校验发生在落子之前**：手里拿着子时，鼠标底下那一格会先亮绿圈（放得下）或红圈（放不下），
  放不下的摆放根本不进盘面，棋盘下面那句话直接说原因。数量、九宫、仕象点位、兵卒方向
  这些「只跟这一枚子有关」的规则都能当场判；两将照面、无棋可走这类要通盘看的，等摆完再报。
- 指定**轮到谁走**、**你执哪边**（只影响棋盘朝向），再点「开始对局」。开局后不自动走棋。
- 局面是否合法由服务端实时判定：缺将少帅、士相越界、兵卒后退、两将照面、
  「轮到你得走、对手却正被将军」这类接不上的局面，都会在开局之前一条条列出来，
  出错的格子直接在棋盘上圈出来。
- 也可以直接粘贴一段 FEN 载入；摆好后页面会自动生成标准 FEN，方便存下来复用。
  局面文本**第 1 行是黑方底线、第 10 行是红方底线**（大写＝红、小写＝黑），
  这个顺序跟谁执红执黑、有没有翻转视角都无关 —— 写反了页面会提示一键「翻正」。
- 摆棋开出来的对局同样落库，重启后按当初那个局面接着下。

## 怎么跑

```bash
mvn spring-boot:run
# 然后打开 http://localhost:8080/ → 自动跳到 /play.html
```

- 引擎：`resources/engine/` 里内置了 Pikafish 与 nnue（Windows 版）。配置 `engine.path` 指向别的目录也可以，
  配置无效时会自动回退到内置引擎。**Linux 服务器上要换成 Linux 版二进制**，见 `deploy/DEPLOY.md`。
- 数据库：默认连 `10.100.6.20:3307/chess_coach_db`，建表脚本在 `resources/db/schema.sql`，
  启动时自动执行。**数据库是可选的** —— 连不上时对局只存在内存里，页面会给提示，不影响下棋。
  - 连接信息可用环境变量覆盖：`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD`。
- 大模型：默认走 **DeepSeek 开放平台**，key 只能由环境变量 `DEEPSEEK_API_KEY` 注入，配置里没有硬编码兜底。
  想换厂商就改环境变量 `COACH_PROVIDER`：`deepseek`（默认）/ `dashscope`（阿里云百炼，配 `DASHSCOPE_API_KEY`）
  / `none`（不要大模型）—— 见下面「大模型怎么切换」。
  不配 key 也不影响下棋：教练改用本地规则提示，一个请求都不会发。`GET /api/game/status` 里的
  `coachProvider` / `coachAvailable` 一眼能看出当前用的是哪家、能不能用。
- 体检接口：`GET /api/game/status` 一次看清引擎、教练、数据库三者的状态。

## 部署到公网

项目是 Java + MySQL，需要 JVM，所以**不能用内置的「发布为应用」托管** —— 那个沙箱只有
Node.js / Python / Go / 静态站点，既没有 JVM 也没有数据库。走自建服务器。

完整步骤（Docker 一键起、Nginx + HTTPS、裸机 systemd、备份、排错）见 **[deploy/DEPLOY.md](deploy/DEPLOY.md)**。
服务器上最简路径就三步：

```bash
cp .env.example .env && vim .env     # 填数据库密码（大模型 key 可留空）
bash deploy/fetch-engine.sh          # 下载 Linux 版 Pikafish 到 ./engine
docker compose up -d --build
```

表结构由应用启动时自动建，不需要手工导 SQL。

## 大模型怎么切换

默认用 DeepSeek 开放平台。换厂商只改一个环境变量，不用动代码、不用重新打包：

```bash
COACH_PROVIDER=deepseek      # 默认。用 DEEPSEEK_API_KEY
COACH_PROVIDER=dashscope     # 切回阿里云百炼，用 DASHSCOPE_API_KEY
COACH_PROVIDER=none          # 不要大模型，教练只出本地规则提示
```

DeepSeek 侧还可以调模型档位（`DEEPSEEK_MODEL`，默认 `deepseek-flash`）：

| 值 | 说明 |
| --- | --- |
| `deepseek-flash` | 默认。快、便宜，教练那种一两百字的短点评完全够用 |
| `deepseek-v4-pro` | 棋理分析更细，价格约 4 倍 |

三个必须知道的点：

1. **模型是「翻译」，不是「军师」。** 棋是 Pikafish 算的，大模型只负责把结论讲成人话。
   所以提示词里写死了「不要自己推演、不要质疑结论」，参数上也把 DeepSeek 的思考关掉了
   （`COACH_REASONING_EFFORT=none`）：不关的话它每次都先把棋推演一遍，正文要等 4~8 秒才开始吐，
   页面上就是「卡死」。关掉之后开局致辞 0.6 秒出字，带棋谱的提问 1.3 秒（原 8.3 秒）。
   想恢复推演（点评细节会多一些），设成 `low` / `high` / `max`。
2. **DeepSeek 不走 Spring AI，是我们自己发的请求。** 它那两个模型都是推理模型，而「关掉思考」
   要往请求体里塞 `reasoning_effort`，可 `spring-ai-deepseek` 的请求体是固定字段的 record，
   参数根本传不下去。所以这一家由 `coach/DeepSeekLlmClient` 直接发 HTTP（JDK HttpClient + SSE 解析），
   请求体自己组；百炼仍走 Spring AI（`coach/SpringAiLlmClient`）。两者都实现 `coach/LlmClient`，
   `CoachService` 不关心底下是谁，选谁由 `config/CoachLlmConfig` 决定。
3. **没配对应厂商的 key 就自动降级。** 教练改用本地规则提示，一个请求都不会发。
   想知道实际生效的是哪家，看 `GET /api/game/status` 返回的 `coachProvider` 和 `coachAvailable`。

另外两条踩过的坑：

- **超时必须显式配。** 走 Spring AI 的那条路（百炼）原本没有任何地方设过超时：Boot 3.2 建
  `RestClient` 时硬编码用 default settings，classpath 上的 OkHttp（百炼 SDK 带进来的传递依赖）
  因而被探测选中，而它默认读超时只有 10 秒 —— 表现成 `SocketTimeoutException` + 教练降级成本地提示，
  看着像网络不通。`config/LlmHttpClientConfig` 换掉了这个客户端并写明超时，两个值都能用环境变量调：
  `COACH_HTTP_CONNECT_TIMEOUT_MS`（默认 10000）、`COACH_HTTP_READ_TIMEOUT_MS`（默认 120000）。
- **不选百炼时，必须把百炼整套自动配置关掉。** 它那批 agent / image / video 自动配置不认
  `spring.ai.model.chat`，只认 `spring.ai.dashscope.enabled`，key 为空时会去构造客户端并在启动阶段
  抛「DashScope API key must be set」，把整个应用带崩。这件事由 `config/LlmProviderGuard`
  在环境准备阶段做（自动配置的条件判断发生在 bean 创建之前，所以必须更早）。

## 教练的讲解是流式的

大模型是边想边吐字的，一次等全量往往要十几秒，用户看着空白的对话框会以为页面卡死。
所以教练的讲解全部走流式：内容分段推给浏览器，前端再逐字显示出来。

| 接口 | 用途 |
| --- | --- |
| `POST /api/game/greeting/{id}/stream` | 开局致辞 |
| `POST /api/game/coach/{id}/stream` | 上一步亏棋的点评 |
| `POST /api/game/chat/{id}/stream` | 向教练提问 |

三个都是 `text/event-stream`，事件格式：

```
event:delta
data:{"text":"这一小段"}

event:done
data:{ …, "chat":[ … ] }
```

（另有非流式的 `POST /api/game/coach/{id}` 和 `/chat/{id}` 保留着，给 curl 调试和不支持流式的调用方用。）

几个不那么显然的设计：

1. **开局不等开场白。** `newGame` 只建会话就返回，开场白交给 `/greeting/stream` 现生成 ——
   否则「开新局」按钮会为了等一句话卡上十几秒。前端在页面加载时若发现这局还没有开场白
   （一步没走、对话里也没有「开局」那条）会自动补拉，刷新和从摆棋页跳回来都照顾到了。
   接口本身是幂等的，重复调用不会多出第二条致辞。
2. **推字期间不攥锁。** 一条流可能跑十几秒，全程持着会话锁会把走子、悔棋、刷新全堵死。
   `GameService` 只在开头（加用户消息、问引擎）和收尾（记下回答、落库）两处短暂加锁。
3. **流式清洗比看起来麻烦。** 模型偶尔会漏出 Markdown 标记或 `（b0c2）` 这种英文坐标，
   得一边流一边删。难点是模式会被 chunk 切在中间：`**` 的两个星号可能分属两段，
   以及 `#` 的 Markdown 语义依赖行首 —— 而片段开头常常是句中，于是句中的 `#` 会被误删。
   `coach/StreamingTextFilter` + `coach/CoachText` 处理这些，并有随机切分的回归测试。
4. **断流时保留已说出的部分。** 一个字都没出来就失败才降级成本地提示；
   已经吐了一半再断，就把那半截存下来（用户都看到了，硬塞一句「大模型不可用」读起来接不上）。
5. **反代必须关缓冲。** nginx 默认 `proxy_buffering on`，会把流攒够一块再转发，
   逐字效果就全没了。见 `deploy/DEPLOY.md` 里的 nginx 配置。
6. **流式管不了「第一个字什么时候来」。** 模型思考期间 `delta.content` 是空的，服务端拿不到一个字，
   前端再怎么逐字也只会对着转圈 —— 实测「不关思考」时，同一套流式代码首字要等 4~8 秒，
   而等到的第一个字之后 98 个字在 0.46 秒内全吐完了。**所以「等待久」和「没有逐字效果」是两个问题**：
   前者靠关思考解决（`COACH_REASONING_EFFORT=none`），后者靠这套流式链路解决。别指望只改一边。

## 代码结构

依赖方向是单向的：`web → service → {game, engine, coach, repository}`。

| 包 | 职责 |
| --- | --- |
| `game` | 自研中国象棋规则内核。盘面、走法合法性、将军/将死/困毙、FEN 读写、摆棋局面校验、中文记谱。不依赖 Spring。 |
| `engine` | Pikafish 对接。进程池 + UCI 协议 + 结构化分析（评分、主变、深度）。 |
| `coach` | 大模型教练。把引擎结果翻译成人话，分「主动讲解」「问答」「开局致辞」三条链路，全部带本地降级；讲解走流式（`CoachStream` + `StreamingTextFilter`），并保留一份同步版本备用。厂商差异都收在 `LlmClient` 后面：DeepSeek 走自研的 `DeepSeekLlmClient`（自己组请求体，能把思考关掉），百炼走 `SpringAiLlmClient`。 |
| `service` | 对局编排。`GameService` 串流程，`GameSessionStore` 管会话与落库，`EngineAdvisor` 包装引擎调用，`GameViewAssembler` 统一出参。 |
| `repository` | MyBatis-Plus 持久化。四张表，`GameRepository` 是门面。 |
| `web` | REST 接口与页面跳转。 |
| `static/xq/xq-render.js` | 前端渲染。棋盘/棋子的矢量绘制 + 走子动画与特效，两个页面共用。 |

读档用的是「重放」而不是「回填盘面」：先按 `chess_game.initial_fen` 摆出开局局面，
再把 `chess_move` 按序走一遍。用户摆过棋的对局就是靠这个字段才能读回来的，
快照里的 `fen` 只负责自检。
