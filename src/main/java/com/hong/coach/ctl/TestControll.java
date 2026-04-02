package com.hong.coach.ctl;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.parser.tika.TikaDocumentParser;
import com.hong.coach.tool.WeatherToolService;
import org.apache.commons.io.FileUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/agent")
public class TestControll {
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private WeatherToolService weatherToolService;
    @Autowired
    private EmbeddingModel embeddingModel;

    @GetMapping("chess")
    public String chessAgent() throws GraphRunnerException {
        String instruction = """
                # 角色
                你是一位资深国际象棋教练，擅长用通俗易懂的语言向棋手讲解局面分析和走法思路。
                
                # 重要规则
                **你收到的 FEN 码和引擎输出是仅供你内部分析使用的技术数据，绝对不要在回答中直接展示或复述这些原始数据。**
                你的回答应该只包含分析后的结论、解释和建议，让用户看不到这些“幕后信息”。
                你的回答要尽量简短和清晰。
                不要暴露引擎的存在，把引擎的输出当作是你的思考结果。
                                
                # 输入信息
                我将提供以下两样信息：
                1. **FEN 码**：当前局面的 Forsyth–Edwards Notation 表示。
                2. **引擎分析输出**：PikaFish（Stockfish 系引擎）的 UCI 日志，包含引擎的思考过程、推荐走法、评估分数等。
                                
                # 任务
                请你根据以上信息，完成以下分析：
                                
                1. **局面概况**：用一句话概括当前局面（如开局、中局、残局；谁占优；局势是否紧张等）。
                                
                2. **引擎推荐走法解读**：
                   - 引擎推荐的 bestmove 是什么？
                   - 这个走法的**战术意图**是什么（进攻、防守、兑子、腾挪、压制等）？
                   - 如果引擎还提供了 ponder（对手应招），解释一下为什么引擎认为对手会这样回应。
                                
                3. **评分含义**：
                   - 解释引擎给出的 `score cp xxx` 代表什么（领先/落后多少兵）。
                   - 如果提供了 `wdl`（胜/和/负概率），也说明一下当前局面的胜负倾向。
                                
                4. **关键变例说明**：
                   - 从引擎的 `pv`（主变）中摘取前 3-5 步，用自然语言描述这条变化会如何发展。
                   - 指出这条变化里哪一步是关键手，为什么。
                                
                5. **给人类棋手的建议**：
                   - 如果你要手动走出引擎推荐的这步棋，需要注意什么？
                   - 如果引擎评分显示局势接近（cp 绝对值 < 30），给出一些保持局面平衡的建议。
                   - 如果引擎评分显示明显优势（cp > 100），建议如何扩大优势；如果劣势，建议如何防守或寻找反击机会。
                                
                # 输出格式
                请用清晰的中文分段输出，避免直接复制引擎的技术术语而不加解释。适当使用棋盘坐标（如 e4、Nf3）配合文字说明，让棋手能对照棋盘理解。
                                
                # 输入数据
                请将以下内容替换为实际数据：
                                
                **FEN**：
                rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C4C2/9/RNBAKABNR b - - 1 1
                                
                **引擎输出**：
                info depth 1 seldepth 4 multipv 1 score cp 5 wdl 18 977 5 nodes 52 nps 52000 hashfull 0 tbhits 0 time 1 pv b7e7
                info depth 2 seldepth 3 multipv 1 score cp 5 wdl 18 977 5 nodes 100 nps 100000 hashfull 0 tbhits 0 time 1 pv b7e7 b0c2
                info depth 3 seldepth 5 multipv 1 score cp 5 wdl 18 977 5 nodes 150 nps 150000 hashfull 0 tbhits 0 time 1 pv b7e7 b0c2 b9c7
                info depth 4 seldepth 5 multipv 1 score cp 5 wdl 18 977 5 nodes 202 nps 202000 hashfull 0 tbhits 0 time 1 pv b7e7 b0c2 b9c7 a0b0
                info depth 5 seldepth 7 multipv 1 score cp 5 wdl 18 977 5 nodes 267 nps 267000 hashfull 0 tbhits 0 time 1 pv b7e7 b2e2
                info depth 6 seldepth 13 multipv 1 score cp 6 wdl 21 974 5 nodes 388 nps 388000 hashfull 0 tbhits 0 time 1 pv b7e7 b2e2 b9c7 b0c2 a9b9 h0i2
                info depth 7 seldepth 13 multipv 1 score cp 5 wdl 19 976 5 nodes 503 nps 503000 hashfull 0 tbhits 0 time 1 pv b7e7 b0c2 b9c7 a0b0 a9b9 h0i2 h7h2 g0e2 h9g7 c3c4 b9b3
                info depth 8 seldepth 9 multipv 1 score cp 5 wdl 19 976 5 nodes 605 nps 605000 hashfull 0 tbhits 0 time 1 pv b7e7 b0c2 b9c7 a0b0 a9b9 h0i2 h7h2 g0e2
                info depth 9 seldepth 13 multipv 1 score cp 5 wdl 19 976 5 nodes 722 nps 722000 hashfull 0 tbhits 0 time 1 pv b7e7 b0c2 b9c7 a0b0 a9b9 h0i2 h7h2 g0e2 h9g7 c3c4 b9b3
                info depth 10 seldepth 22 multipv 1 score cp 5 wdl 19 976 5 nodes 2117 nps 705666 hashfull 1 tbhits 0 time 3 pv b7e7 b0c2 b9c7 h0i2 h9i7 i0h0 i9h9 a0b0 a9b9 h0h4 i6i5 i3i4 i5i4
                info depth 11 seldepth 19 multipv 1 score cp 5 wdl 19 976 5 nodes 3038 nps 759500 hashfull 1 tbhits 0 time 4 pv b7e7 b0c2 b9c7 h0i2 h9i7 i0h0 i9h9 a0b0 a9b9 h0h4 i6i5 i3i4 i5i4 h4i4 c6c5 d0e1 h7h2 c0e2
                info depth 12 seldepth 23 multipv 1 score cp 4 wdl 18 977 5 nodes 4064 nps 1016000 hashfull 1 tbhits 0 time 4 pv b7e7 b0c2 b9c7 h0i2 h9i7 i0h0 i9h9 h0h4 i6i5 b2b4 a9b9 i3i4 h7g7 h4h9 i7h9 c3c4 b9b5 c0e2
                info depth 13 seldepth 32 multipv 1 score cp 4 wdl 17 977 6 nodes 14943 nps 996200 hashfull 2 tbhits 0 time 15 pv b7e7 b0c2 b9c7 h0i2 h9i7 a0b0 a9b9 i0h0 i9h9 h0h4 i6i5 i3i4 i5i4 h4i4 c6c5 d0e1
                info depth 14 seldepth 21 multipv 1 score cp 4 wdl 16 978 6 nodes 15587 nps 974187 hashfull 2 tbhits 0 time 16 pv b7e7 b0c2 b9c7 h0i2 h9i7 i0h0 i9h9 a0b0 a9b9 h0h4 i6i5 i3i4 i5i4 h4i4 h7h1
                info depth 15 seldepth 38 multipv 1 score cp 4 wdl 17 977 6 nodes 43312 nps 734101 hashfull 12 tbhits 0 time 59 pv b7e7 b0c2 b9c7 h0i2 h9i7 i0h0 i9h9 a0b0 a9b9 h0h4 i6i5 i3i4 i5i4 h4i4 h7h3 d0e1 h9h5 b2b6 c6c5 c0e2 h3e3 c2e3 e7e3 c3c4 h5e5 i4d4 c5c4 d4c4 c7b5 c4c6 b5d4 c6d6
                info depth 16 seldepth 31 multipv 1 score cp 5 wdl 19 976 5 nodes 71178 nps 711780 hashfull 23 tbhits 0 time 100 pv b7e7 b0c2 b9c7 h0i2 a9b9 i0h0 h9g7 h0h6 i9h9 g3g4 b9b5 b2a2 h7i7 h6h9 g7h9 a0a1 i7i3
                info depth 17 seldepth 36 multipv 1 score cp 4 wdl 17 977 6 nodes 100069 nps 719920 hashfull 34 tbhits 0 time 139 pv b7e7 b0c2 b9c7 h0i2 h9i7 i0h0 i9h9 a0b0 a9b9 h0h4 i6i5 i3i4 i5i4 h4i4 h7h3 c0e2 c6c5
                info depth 18 seldepth 30 multipv 1 score cp 4 wdl 17 977 6 nodes 114845 nps 722295 hashfull 38 tbhits 0 time 159 pv b7e7 b0c2 b9c7 h0i2 h9i7 i0h0 i9h9 a0b0 a9b9 h0h4 i6i5 i3i4 i5i4 h4i4 h7h3 c0e2 c6c5 d0e1 h9h5 b2b8 h3e3 c2e3 e7e3 c3c4 h5e5 i4d4 c5c4 d4c4 c7b5
                info depth 19 seldepth 35 multipv 1 score cp 5 wdl 19 976 5 nodes 147979 nps 747368 hashfull 51 tbhits 0 time 198 pv b7e7 b0c2 b9c7 h0i2 h9i7 i0h0 i9h9 a0b0 a9b9 h0h4 i6i5 i3i4 i5i4 h4i4 h7h3 b2b4 h9h5 g3g4 f9e8 c0e2 c6c5 g4g5 h5g5 b4g4
                info depth 20 seldepth 35 multipv 1 score cp 5 wdl 19 976 5 nodes 149491 nps 747455 hashfull 51 tbhits 0 time 200 pv b7e7 b0c2 b9c7 h0i2 h9i7 i0h0 i9h9 a0b0 a9b9 h0h4 i6i5 i3i4 i5i4 h4i4 h7h3 b2b4 h9h5 g3g4 f9e8 c0e2 c6c5 g4g5 h5g5 b4g4
                bestmove b7e7 ponder b0c2
                """;
        ReactAgent agent = ReactAgent.builder()
                .name("象棋大师")
                .model(chatModel)
                .instruction(instruction)
                .saver(new MemorySaver())
                .build();
        Optional<OverAllState> all = agent.invoke("帮我分析一下盘面");
        return all.get().toString();
    }

    @GetMapping("pdf")
    public String parsePdf() throws IOException {
        TikaDocumentParser parser = new TikaDocumentParser();
        List<Document> docs = parser.parse(FileUtils.openInputStream(new File("C:\\Users\\test\\Desktop\\翻译测试\\2022 Transcription factor protein interactomes reveal genetic determinants in heart disease.pdf")));
        for (Document doc : docs) {
            System.out.println(doc.getText());
        }
        return null;
    }
    @GetMapping("/embed")
    public float[] embed(@RequestParam(name="str") String str){
        return embeddingModel.embed(str);
    }


}
