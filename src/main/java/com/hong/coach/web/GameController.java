package com.hong.coach.web;

import com.hong.coach.game.PositionMove;
import com.hong.coach.game.PositionSetup;
import com.hong.coach.game.Side;
import com.hong.coach.service.GameService;
import com.hong.coach.web.dto.ChatRequest;
import com.hong.coach.web.dto.NewGameRequest;
import com.hong.coach.web.dto.PositionSetupRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
