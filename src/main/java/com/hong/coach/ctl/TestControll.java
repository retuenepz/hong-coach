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

    @GetMapping("weather")
    public String weatherAgent() throws GraphRunnerException {
        FunctionToolCallback<String, String> weatherTool = FunctionToolCallback.builder("查询天气", weatherToolService)
                .description("根据城市名称查询天气情况")
                .inputType(String.class)
                .build();
        ReactAgent agent = ReactAgent.builder()
                .name("天气专家")
                .model(chatModel)
                .tools(weatherTool)
                .systemPrompt("你是一个专业的旅行规划专家，可以帮用户规划旅行线路，指定旅行计划，包括交通方式，居住地点，根据用户的喜好来选择特色的经典，如果规划过程中有不清楚的信息，应该向用户主动提问。")
                .saver(new MemorySaver())
                .build();
        Optional<OverAllState> all = agent.invoke("明天好无聊啊，想出去玩，有什么推荐吗？");
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
