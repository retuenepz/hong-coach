package com.hong.coach.web;

import com.hong.coach.game.PositionMove;
import com.hong.coach.game.PositionSetup;
import com.hong.coach.game.Side;
import com.hong.coach.service.CoachStreamHandle;
import com.hong.coach.service.GameService;
import com.hong.coach.web.dto.ChatRequest;
import com.hong.coach.web.dto.NewGameRequest;
import com.hong.coach.web.dto.PositionSetupRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 中国象棋对局接口。
 * <p>
 * 对局以 {@code {id}} 标识：由前端生成并在 sessionStorage 里复用，服务端按 id 保存会话
 * 并落库，因此刷新页面、甚至重启服务之后都能接着下同一局。
 * <p>
 * 路径上的 {@code {id}} 允许省略（走 {@code /new}、{@code /move}），此时归到 {@code default} 局，
 * 方便用 curl 手工调试。
 */
@Slf4j
@RestController
@RequestMapping("/api/game")
@CrossOrigin(origins = "*")
public class GameController {

    private static final String DEFAULT_SESSION = "default";

    /** 摆棋校验用的临时棋盘标识，只是个名字，不会建成对局 */
    private static final String POSITION_PREVIEW = "position-preview";

    /**
     * 流式连接的最长存活时间。要比服务端 {@code coach.stream-max-ms} 宽裕，
     * 免得内容还在推、连接先被容器掐了。
     */
    private static final long SSE_TIMEOUT_MS = 240_000L;

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    /**
     * 开新局。body 可选：{"playerSide":"RED"|"BLACK"}
     */
    @PostMapping({"/new/{id}", "/new"})
    public Map<String, Object> newGame(@PathVariable(required = false) String id,
                                       @RequestBody(required = false) NewGameRequest request) {
        String playerSide = request == null ? null : request.getPlayerSide();
        return gameService.newGame(resolveId(id), parseSide(playerSide));
    }

    /**
     * 摆棋：校验一个自定义局面。不建对局，前端边摆边调它。
     * body：{"matrix":[[...]], "turn":"RED"} 或 {"fen":"..."}
     */
    @PostMapping("/position/check")
    public Map<String, Object> checkPosition(@RequestBody(required = false) PositionSetupRequest request) {
        return gameService.checkPosition(buildPosition(POSITION_PREVIEW, request));
    }

    /**
     * 摆棋：落子<b>之前</b>判断「这一枚子放在这个点上行不行」。
     * body：{"matrix":[[...]], "r":9, "c":4, "code":"K"}
     * <p>
     * 与 {@link #checkPosition} 的分工：那边看整盘（两将照面、无棋可走都得等摆完才判得出），
     * 这边只看落点那一枚子，所以能在落子之前就给答案，界面据此把不合规的摆放挡下来。
     */
    @PostMapping("/position/place")
    public Map<String, Object> checkPlacement(@RequestBody(required = false) PositionSetupRequest request) {
        if (request == null) {
            return gameService.checkPlacement(PositionSetup.Placement.refused("没有收到落点"));
        }
        return gameService.checkPlacement(PositionSetup.place(POSITION_PREVIEW,
                request.getMatrix(), request.getR(), request.getC(), request.getCode()));
    }

    /**
     * 摆棋：用指定局面开一局。
     * body：{"playerSide":"RED","turn":"BLACK","matrix":[[...]]}
     */
    @PostMapping({"/setup/{id}", "/setup"})
    public Map<String, Object> setup(@PathVariable(required = false) String id,
                                     @RequestBody(required = false) PositionSetupRequest request) {
        String sessionId = resolveId(id);
        return gameService.startFromPosition(sessionId, parseSide(request == null ? null : request.getPlayerSide()),
                buildPosition(sessionId, request));
    }

    /**
     * 换视角：改成站在另一方看这盘棋（棋盘朝向随之翻转）。棋局本身一步都不动。
     */
    @PostMapping("/switch/{id}")
    public Map<String, Object> switchSide(@PathVariable String id) {
        return gameService.switchSide(id);
    }

    /**
     * 让电脑替「当前该走的那一方」走一步。轮到红方就替红方走，轮到黑方就替黑方走。
     * <p>
     * 电脑不会自动出手：想让它走，就点一次这个接口。
     */
    @PostMapping("/engine/{id}")
    public Map<String, Object> engineMove(@PathVariable String id) {
        return gameService.engineMove(id);
    }

    /**
     * 悔棋：把最近的着法退回盘面。<b>次数不限</b>，一直能退到本局开局那个局面。
     * <p>
     * 退几手由服务端决定，前端不必也不该自己算：电脑替你应过一手时，会把它的应招
     * 和你上一步一起退掉（回到你落子之前）；其余情况退一手。
     * <p>
     * 出参在常规对局状态之外多了 {@code undone}（被撤的着法，按原先后）、
     * {@code undoneCount}；已经退到开局时返回 {@code result=nothing_to_undo}。
     */
    @PostMapping("/undo/{id}")
    public Map<String, Object> undo(@PathVariable String id) {
        return gameService.undoMove(id);
    }

    /**
     * 人手动走子。body：{"fromR":9,"fromC":1,"toR":7,"toC":2}
     * <p>
     * 红黑两边都能走：轮到哪一方，就能动哪一方的棋子。
     */
    @PostMapping({"/move/{id}", "/move"})
    public Map<String, Object> playerMove(@PathVariable(required = false) String id,
                                          @RequestBody PositionMove move) {
        return gameService.playerMove(resolveId(id), move);
    }

    /**
     * 取上一步的教练点评。走子接口返回 coachTriggered=true 时前端会主动来取。
     */
    @PostMapping("/coach/{id}")
    public Map<String, Object> coach(@PathVariable String id) {
        return gameService.coachComment(id);
    }

    /**
     * 向教练提问。body：{"question":"我这马该跳上去吗"}
     */
    @PostMapping("/chat/{id}")
    public Map<String, Object> chat(@PathVariable String id,
                                    @RequestBody(required = false) ChatRequest request) {
        String question = request == null ? null : request.getQuestion();
        if (question == null || question.isBlank()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("result", "empty_question");
            resp.put("message", "问题不能为空");
            return resp;
        }
        return gameService.chat(id, question.trim());
    }

    // ==================== 流式讲解（页面走这三个） ====================
    //
    // 大模型是边想边吐字的，一次等全量往往要十几秒，用户会以为页面卡死。
    // 这三个接口把内容分段推出来，前端逐字显示。
    //
    // 事件格式（标准 SSE）：
    //   event: delta   data: {"text":"这一小段"}          —— 增量文本，可能来很多次
    //   event: done    data: { …, "chat":[…] }            —— 结束，带上完整对话记录
    //   event: error   data: {"message":"…"}              —— 出错

    /** 开局致辞（流式） */
    @PostMapping(value = "/greeting/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter greetingStream(@PathVariable String id, HttpServletResponse response) {
        return streamTo(gameService.greetingStream(id), response);
    }

    /** 上一步亏棋的点评（流式）。走子接口返回 coachTriggered=true 时前端会主动来取 */
    @PostMapping(value = "/coach/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter coachStream(@PathVariable String id, HttpServletResponse response) {
        return streamTo(gameService.coachStream(id), response);
    }

    /** 向教练提问（流式） */
    @PostMapping(value = "/chat/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String id,
                                 @RequestBody(required = false) ChatRequest request,
                                 HttpServletResponse response) {
        String question = request == null ? null : request.getQuestion();
        if (question == null || question.isBlank()) {
            return doneOnly(Map.of("result", "empty_question", "message", "问题不能为空"), response);
        }
        return streamTo(gameService.chatStream(id, question.trim()), response);
    }

    /**
     * 把服务端的增量流桥接成 SSE 推给浏览器。
     * <p>
     * 前端用 fetch + ReadableStream 读（POST 没法用 EventSource），所以这里按标准 SSE 写事件。
     * 推完必须 complete，否则连接会一直挂着。
     */
    private SseEmitter streamTo(CoachStreamHandle handle, HttpServletResponse response) {
        disableProxyBuffering(response);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 收尾动作：把最终文本记进对话、落库，并把完整对话回给前端。
        // 正常结束和出错中断都要走这一步 —— 中断时用户已经看到了半截回答，
        // 那些字也得存下来，不然刷新页面就前后对不上了。
        Runnable settle = () -> {
            Map<String, Object> done;
            try {
                done = handle.finish().get();
            } catch (Exception e) {
                log.warn("流式讲解收尾失败：{}", e.toString());
                done = Map.of("result", "error");
            }
            send(emitter, "done", done);
            emitter.complete();
        };

        Disposable disposable = handle.deltas().subscribe(
                text -> send(emitter, "delta", Map.of("text", text)),
                error -> {
                    log.warn("流式讲解中断，已生成的部分照常保存：{}", error.toString());
                    settle.run();
                },
                settle);

        // 客户端断开（翻页、点了新局）时要顺手取消上游订阅，别让大模型白跑
        emitter.onCompletion(disposable::dispose);
        emitter.onError(e -> disposable.dispose());
        emitter.onTimeout(() -> {
            disposable.dispose();
            emitter.complete();
        });
        return emitter;
    }

    /** 不需要生成内容、只用 done 事件回一句话的流（比如问题为空） */
    private static SseEmitter doneOnly(Map<String, Object> payload, HttpServletResponse response) {
        disableProxyBuffering(response);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        send(emitter, "done", payload);
        emitter.complete();
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 客户端断开是常态，不是故障，让它自然结束
            emitter.complete();
        }
    }

    /**
     * 告诉中间的反向代理「别攒着」。
     * <p>
     * nginx 默认开 {@code proxy_buffering}，会把上游的响应攒够一块再转发 —— 流式就白做了，
     * 用户还是要等信息全部生成完才看到。这个响应头是 nginx 认可的关闭开关，
     * 代理那份配置里也同步写了 {@code proxy_buffering off}。
     */
    private static void disableProxyBuffering(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
    }

    /**
     * 某个位置的合法落点，前端选中棋子后高亮用
     */
    @GetMapping("/legal/{id}")
    public Map<String, Object> legalMoves(@PathVariable String id,
                                          @RequestParam(name = "r") int r,
                                          @RequestParam(name = "c") int c) {
        return gameService.legalMoves(id, r, c);
    }

    /**
     * 当前对局状态。前端进入页面时先调它：能拿到就恢复对局，拿不到才弹开局选边。
     */
    @GetMapping("/state/{id}")
    public Map<String, Object> state(@PathVariable String id) {
        return gameService.state(id);
    }

    /**
     * 引擎、教练、数据库的体检结果，排查环境问题用
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return gameService.systemStatus();
    }

    /**
     * 兜底异常处理，保证前端拿到的始终是 JSON
     */
    @ExceptionHandler(Exception.class)
    public Map<String, Object> onError(Exception e) {
        log.error("对局接口异常", e);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("result", "error");
        resp.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return resp;
    }

    private static String resolveId(String id) {
        return (id == null || id.isBlank()) ? DEFAULT_SESSION : id;
    }

    private static Side parseSide(String text) {
        return "BLACK".equalsIgnoreCase(text == null ? null : text.trim()) ? Side.BLACK : Side.RED;
    }

    /** 与 {@link #parseSide} 的区别：没写就是没写，返回 null，交给 FEN 去决定 */
    private static Side parseSideOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        return "BLACK".equalsIgnoreCase(text.trim()) ? Side.BLACK : Side.RED;
    }

    private static PositionSetup.Built buildPosition(String id, PositionSetupRequest request) {
        if (request == null) return PositionSetup.build(id, null, null, null);
        return PositionSetup.build(id, request.getMatrix(), request.getFen(),
                parseSideOrNull(request.getTurn()));
    }
}
