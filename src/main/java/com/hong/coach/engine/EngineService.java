package com.hong.coach.engine;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;

/**
 * 象棋引擎（Pikafish）对接服务。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>进程池：每个 {@link EngineWorker} 独占一个引擎进程与一对标准输入输出流，
 *       借用期间不与任何其他请求交错，因此多用户可以并发对局而不会串流。</li>
 *   <li>结构化分析：不止返回 bestmove，还解析 {@code info ... score ... pv ...}，
 *       得到评分、主变，供 AI 教练讲解使用。</li>
 *   <li>路径兜底：配置的目录不可用时，自动从 classpath 的 {@code resources/engine}
 *       释放引擎到临时目录再启动。</li>
 *   <li>超时保护：单次分析有硬超时，超时就把该实例的进程杀掉并重建，
 *       避免整条请求链被卡死。</li>
 * </ul>
 */
@Slf4j
@Service
public class EngineService {

    private final EngineProperties props;

    /** 全部存活的实例 */
    private final List<EngineWorker> workers = new CopyOnWriteArrayList<>();

    /** 空闲实例队列，借出/归还 */
    private final BlockingQueue<EngineWorker> idle = new LinkedBlockingQueue<>();

    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "engine-io");
        t.setDaemon(true);
        return t;
    });

    private volatile File engineDir;
    private volatile String nnuePath;
    private volatile String unavailableReason = "尚未初始化";

    public EngineService(EngineProperties props) {
        this.props = props;
    }

    // ==================== 生命周期 ====================

    @PostConstruct
    public void start() {
        try {
            engineDir = resolveEngineDir();
            if (engineDir == null) {
                unavailableReason = "找不到引擎可执行文件，请检查 engine.path 或 resources/engine";
                log.warn("引擎启动失败：{}", unavailableReason);
                return;
            }
            File nnue = new File(engineDir, props.getNnueFileName());
            nnuePath = nnue.isFile() ? nnue.getAbsolutePath() : null;

            int size = Math.max(1, props.getPoolSize());
            for (int i = 0; i < size; i++) {
                EngineWorker w = new EngineWorker(i);
                try {
                    w.init();
                    workers.add(w);
                    idle.offer(w);
                } catch (Exception e) {
                    log.error("引擎实例 #{} 启动失败", i, e);
                }
            }
            if (workers.isEmpty()) {
                unavailableReason = "所有引擎实例均启动失败";
                log.error("引擎不可用：{}", unavailableReason);
            } else {
                unavailableReason = null;
                log.info("引擎就绪：{} 个实例，可执行文件 {}", workers.size(), w0Path());
            }
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
        } catch (Exception e) {
            unavailableReason = "引擎初始化异常: " + e.getMessage();
            log.error("引擎初始化异常", e);
        }
    }

    @PreDestroy
    public void stop() {
        for (EngineWorker w : workers) {
            try {
                w.quit();
            } catch (Exception ignored) {
            }
        }
        workers.clear();
        idle.clear();
        ioPool.shutdownNow();
    }

    private String w0Path() {
        return workers.isEmpty() ? "-" : workers.get(0).exePath;
    }

    // ==================== 状态 ====================

    public boolean isAvailable() {
        return !workers.isEmpty();
    }

    public String statusText() {
        if (!isAvailable()) {
            return "不可用：" + (unavailableReason == null ? "未知原因" : unavailableReason);
        }
        return String.format("可用 · %d 个实例 · 空闲 %d · %s",
                workers.size(), idle.size(), w0Path());
    }

    // ==================== 对外能力 ====================

    /**
     * 分析局面。返回结果中的评分是「行棋方视角」。
     */
    public EngineAnalysis analyze(String fen, int movetime, int multiPv) {
        if (!isAvailable()) {
            throw new IllegalStateException("象棋引擎不可用：" + (unavailableReason == null ? "未知原因" : unavailableReason));
        }
        EngineWorker worker = null;
        try {
            worker = idle.poll(props.getBorrowTimeoutMs(), TimeUnit.MILLISECONDS);
            if (worker == null) {
                throw new IllegalStateException("引擎正忙，请稍后重试");
            }
            EngineAnalysis analysis = worker.analyze(fen, Math.max(1, movetime), Math.max(1, multiPv));
            analysis.setFen(fen);
            return analysis;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待引擎被中断", ie);
        } catch (Exception e) {
            if (worker != null) {
                worker.recoverQuietly();
            }
            throw new IllegalStateException("引擎分析失败：" + e.getMessage(), e);
        } finally {
            if (worker != null) {
                if (worker.isHealthy()) {
                    idle.offer(worker);
                } else {
                    workers.remove(worker);
                    log.warn("引擎实例 #{} 已不可用，从池中移除", worker.index);
                }
            }
        }
    }

    /**
     * 让引擎落子：只要最优着法
     */
    public String bestMove(String fen) {
        EngineAnalysis a = analyze(fen, props.getPlayMovetime(), 1);
        return a.getBestMove();
    }

    /**
     * 对局用时（快）
     */
    public EngineAnalysis analyzeForPlaying(String fen) {
        return analyze(fen, props.getPlayMovetime(), 1);
    }

    /**
     * 复盘/讲解用时（慢一点，带多路主变）
     */
    public EngineAnalysis analyzeForReview(String fen) {
        return analyze(fen, props.getAnalyzeMovetime(), Math.max(1, props.getMultiPv()));
    }

    // ==================== 引擎定位 ====================

    /**
     * 优先使用配置目录，不可用时回退到 classpath 内置引擎
     */
    private File resolveEngineDir() throws IOException {
        String configured = props.getPath();
        if (configured != null && !configured.isBlank()) {
            File dir = new File(configured.trim());
            if (new File(dir, props.getExeFileName()).isFile()) {
                log.info("使用配置目录中的引擎：{}", dir.getAbsolutePath());
                return dir;
            }
            log.warn("配置的引擎目录不可用：{}，尝试使用内置引擎", configured);
        }
        ClassPathResource exe = new ClassPathResource("engine/" + props.getExeFileName());
        if (!exe.exists()) {
            log.warn("classpath 中也没有内置引擎：engine/{}", props.getExeFileName());
            return null;
        }
        File dir = new File(System.getProperty("java.io.tmpdir"), "hong-coach-engine");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建引擎临时目录: " + dir);
        }
        boolean copied = false;
        copied |= extract("engine/" + props.getExeFileName(), new File(dir, props.getExeFileName()));
        copied |= extract("engine/" + props.getNnueFileName(), new File(dir, props.getNnueFileName()));
        File target = new File(dir, props.getExeFileName());
        if (!target.isFile()) return null;
        target.setExecutable(true);
        if (copied) {
            log.info("已从 classpath 释放内置引擎到 {}", dir.getAbsolutePath());
        } else {
            log.info("复用已释放的内置引擎 {}", dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * 把 classpath 资源复制到目标位置，已存在且大小一致则跳过
     */
    private boolean extract(String classpathLocation, File target) throws IOException {
        ClassPathResource res = new ClassPathResource(classpathLocation);
        if (!res.exists()) return false;
        long expected = res.contentLength();
        if (target.isFile() && expected > 0 && target.length() == expected) return false;
        try (InputStream in = res.getInputStream()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    // ==================== 引擎实例 ====================

    /**
     * 一个引擎进程。所有方法内部串行（synchronized），且同一时刻只被一个请求借用。
     */
    private final class EngineWorker {

        private final int index;
        private final String exePath;
        private Process process;
        private BufferedWriter stdin;
        private BufferedReader stdout;
        /** 当前引擎上生效的 MultiPV，避免每次都 setoption */
        private int multiPvSet = -1;

        EngineWorker(int index) {
            this.index = index;
            this.exePath = new File(engineDir, props.getExeFileName()).getAbsolutePath();
        }

        synchronized void init() throws Exception {
            ProcessBuilder pb = new ProcessBuilder(exePath);
            pb.directory(engineDir);
            pb.redirectErrorStream(true);
            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            send("uci");
            awaitToken("uciok", 10_000);
            if (nnuePath != null) {
                send("setoption name EvalFile value " + nnuePath);
            }
            send("isready");
            awaitToken("readyok", 15_000);
            send("ucinewgame");
            log.info("引擎实例 #{} 启动完成", index);
        }

        synchronized void quit() {
            try {
                if (process != null && process.isAlive()) {
                    send("quit");
                }
            } catch (Exception ignored) {
            }
            closeQuietly();
            if (process != null) {
                process.destroy();
            }
            process = null;
        }

        /**
         * 异常后尝试重启，失败就保持不可用
         */
        synchronized void recoverQuietly() {
            if (isHealthy()) return;
            log.warn("引擎实例 #{} 异常，尝试重启", index);
            closeQuietly();
            if (process != null) process.destroy();
            process = null;
            multiPvSet = -1;
            try {
                init();
            } catch (Exception e) {
                log.error("引擎实例 #{} 重启失败", index, e);
            }
        }

        boolean isHealthy() {
            Process p = process;
            return p != null && p.isAlive();
        }

        synchronized EngineAnalysis analyze(String fen, int movetime, int multiPv) throws Exception {
            if (!isHealthy()) throw new IOException("引擎进程已退出");

            if (multiPv != multiPvSet) {
                send("setoption name MultiPV value " + multiPv);
                send("isready");
                awaitToken("readyok", 15_000);
                multiPvSet = multiPv;
            }

            send("position fen " + fen);
            long t0 = System.currentTimeMillis();
            send("go movetime " + movetime);

            long hardTimeout = movetime + 20_000L;
            Future<EngineAnalysis> future = ioPool.submit(() -> readSearchResult(multiPv));
            EngineAnalysis analysis;
            try {
                analysis = future.get(hardTimeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                // 引擎可能已卡死，直接杀掉，由上层重建
                if (process != null) process.destroyForcibly();
                throw new IOException("引擎响应超时（" + hardTimeout + "ms）");
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                throw new IOException("读取引擎输出失败：" + cause.getMessage(), cause);
            }
            analysis.setCostMs(System.currentTimeMillis() - t0);
            return analysis;
        }

        /**
         * 阻塞读取直到 bestmove。该方法运行在 ioPool 线程中，便于加硬超时。
         */
        private EngineAnalysis readSearchResult(int multiPv) throws IOException {
            Map<Integer, EngineAnalysis.PvLine> lines = new TreeMap<>();
            int maxDepth = 0;
            String bestMove = null;
            String ponder = null;

            String line;
            while ((line = stdout.readLine()) != null) {
                if (line.startsWith("bestmove")) {
                    String[] sp = line.trim().split("\\s+");
                    if (sp.length >= 2) bestMove = sp[1];
                    if (sp.length >= 4 && "ponder".equals(sp[2])) ponder = sp[3];
                    break;
                }
                if (line.startsWith("info ")) {
                    maxDepth = Math.max(maxDepth, parseInfo(line, lines));
                }
            }
            if (line == null) {
                throw new IOException("引擎输出流已关闭");
            }

            EngineAnalysis analysis = new EngineAnalysis();
            analysis.setBestMove(bestMove);
            analysis.setPonder(ponder);
            analysis.setDepth(maxDepth);
            List<EngineAnalysis.PvLine> ordered = new ArrayList<>(lines.values());
            analysis.setPvLines(ordered);
            EngineAnalysis.PvLine first = ordered.isEmpty() ? null : ordered.get(0);
            if (first != null) {
                analysis.setScoreCp(first.getScoreCp());
                analysis.setScoreMate(first.getScoreMate());
            }
            return analysis;
        }

        /**
         * 解析 info 行：depnet / multipv / score cp|mate / pv
         *
         * @return 该行的 depth
         */
        private int parseInfo(String line, Map<Integer, EngineAnalysis.PvLine> out) {
            String[] tok = line.trim().split("\\s+");
            int multipv = 1;
            int depth = 0;
            Integer cp = null;
            Integer mate = null;
            List<String> pv = null;

            for (int i = 0; i < tok.length; i++) {
                String t = tok[i];
                if ("depth".equals(t) && i + 1 < tok.length) {
                    depth = parseInt(tok[i + 1], depth);
                } else if ("multipv".equals(t) && i + 1 < tok.length) {
                    multipv = parseInt(tok[i + 1], 1);
                } else if ("score".equals(t) && i + 2 < tok.length) {
                    String kind = tok[i + 1];
                    Integer v = parseIntOrNull(tok[i + 2]);
                    if (v != null) {
                        if ("cp".equals(kind)) cp = v;
                        else if ("mate".equals(kind)) mate = v;
                    }
                } else if ("pv".equals(t)) {
                    pv = new ArrayList<>(Arrays.asList(tok).subList(i + 1, tok.length));
                    break;
                }
            }
            // 没有评分的是搜索刚开始的中间行，忽略
            if (cp == null && mate == null) return depth;

            EngineAnalysis.PvLine pl = out.computeIfAbsent(multipv, k -> new EngineAnalysis.PvLine());
            pl.setMultipv(multipv);
            pl.setDepth(depth);
            if (mate != null) {
                pl.setScoreMate(mate);
                pl.setScoreCp(null);
            } else {
                pl.setScoreCp(cp);
                pl.setScoreMate(null);
            }
            if (pv != null && !pv.isEmpty()) {
                pl.setMoves(pv);
            }
            return depth;
        }

        private void send(String cmd) throws IOException {
            if (stdin == null) throw new IOException("引擎输入流未就绪");
            if (log.isDebugEnabled()) log.debug("engine#{} << {}", index, cmd);
            stdin.write(cmd);
            stdin.write("\n");
            stdin.flush();
        }

        /**
         * 阻塞读取直到出现指定 token，带硬超时（超时即杀进程，由上层重建）
         */
        private void awaitToken(String token, long timeoutMs) throws IOException {
            BufferedReader reader = stdout;
            Future<Boolean> future = ioPool.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (log.isDebugEnabled()) log.debug("engine#{} >> {}", index, line);
                    if (line.contains(token)) return true;
                }
                return false;
            });
            try {
                Boolean ok = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (ok == null || !ok) throw new IOException("等待引擎响应 " + token + " 超时");
            } catch (TimeoutException e) {
                future.cancel(true);
                if (process != null) process.destroyForcibly();
                throw new IOException("等待引擎响应 " + token + " 超时", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待 " + token + " 时被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IOException("等待 " + token + " 失败：" + cause.getMessage(), cause);
            }
        }

        private void closeQuietly() {
            try {
                if (stdin != null) stdin.close();
            } catch (Exception ignored) {
            }
            try {
                if (stdout != null) stdout.close();
            } catch (Exception ignored) {
            }
            stdin = null;
            stdout = null;
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }
}
