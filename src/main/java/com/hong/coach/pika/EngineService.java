package com.hong.coach.pika;

import com.hong.coach.pika.config.EngineProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Service
@Slf4j
public class EngineService {
    @Autowired
    private EngineProperties engineProperties;

    private Process engine;
    private BufferedWriter stdin;
    private BufferedReader stdout;

    private final ExecutorService ioPool = Executors.newFixedThreadPool(2);

    /**
     * 启动
     * @throws Exception
     */
    @PostConstruct
    public void start() throws Exception {

        ProcessBuilder pb = new ProcessBuilder(new File(engineProperties.getPath(), engineProperties.getExeFileName()).getAbsolutePath());
        pb.directory(new File(engineProperties.getPath()));
        pb.redirectErrorStream(true);
        engine = pb.start();

        stdin  = new BufferedWriter(new OutputStreamWriter(engine.getOutputStream()));
        stdout = new BufferedReader(new InputStreamReader(engine.getInputStream()));

        // 3) Enter UCI mode
        send("uci");
        waitUntilContains("uciok", Duration.ofSeconds(5));

        File nnue = new File(engineProperties.getPath(), engineProperties.getNnueFileName());
        if (nnue.exists()) {
            send("setoption name EvalFile value " + nnue.getAbsolutePath());
        }

        send("isready");
        waitUntilContains("readyok", Duration.ofSeconds(5));

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    /**
     * 进程销毁
     */
    @PreDestroy
    public void stop() {
        try { send("quit"); } catch (Exception ignored) {}
        try { if (stdin  != null) stdin.close(); } catch (Exception ignored) {}
        try { if (stdout != null) stdout.close(); } catch (Exception ignored) {}
        if (engine != null) engine.destroy();
        ioPool.shutdownNow();
    }

    /** Calculate best move: fen can be null, playedMoves is UCI move sequence (spaces/commas both acceptable) */
    public String bestMove(String fen, List<String> playedMoves) throws Exception {
        if (fen == null || fen.trim().isEmpty()) {
            send("ucinewgame");
            send("position startpos" + buildMovesSuffix(playedMoves));
        } else {
            send("ucinewgame");
            send("position fen " + fen + buildMovesSuffix(playedMoves));
        }
        send("go movetime " + engineProperties.getMovetime());
        return waitBestMove(Duration.ofSeconds(8));
    }

    private void send(String cmd) throws IOException {
        log.info("send to pika:" + cmd);
        stdin.write(cmd);
        stdin.write("\n");
        stdin.flush();
    }

    private String waitBestMove(Duration timeout) throws Exception {
        final BlockingQueue<String> q = new ArrayBlockingQueue<>(1);
        Future<?> fu = ioPool.submit(() -> {
            String line;
            try {
                while ((line = stdout.readLine()) != null) {
                    log.info("pikaout:" + line);
                    if (line.startsWith("bestmove")) {
                        q.offer(line);
                        break;
                    }
                }
            } catch (IOException ignored) {}
        });
        String best = q.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (best == null) {
            fu.cancel(true);
            throw new TimeoutException("Engine did not return bestmove in time");
        }
        String[] sp = best.split("\\s+");
        return sp.length >= 2 ? sp[1] : "";
    }

    private void waitUntilContains(String token, Duration timeout) throws Exception {
        Future<Boolean> fu = ioPool.submit(() -> {
            String line;
            try {
                while ((line = stdout.readLine()) != null) {
                    if (line.contains(token)) return true;
                }
            } catch (IOException ignored) {}
            return false;
        });
        Boolean ok;
        try {
            ok = fu.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fu.cancel(true);
            throw e;
        }
        if (ok == null || !ok) throw new TimeoutException("Waiting for token failed: " + token);
    }

    private String buildMovesSuffix(List<String> moves) {
        if (moves == null || moves.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" moves");
        for (String m : moves) {
            if (m != null && !m.trim().isEmpty()) {
                sb.append(' ').append(m.trim());
            }
        }
        return sb.toString();
    }

    /* Convenience overload */
    public String bestMove(String fen) throws Exception {
        return bestMove(fen, new ArrayList<>());
    }
}