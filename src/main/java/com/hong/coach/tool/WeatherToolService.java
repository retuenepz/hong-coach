package com.hong.coach.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;

import java.util.function.BiFunction;

@Service
public class WeatherToolService implements BiFunction<String, ToolContext,String> {
    @Override
    public String apply(String city, ToolContext toolContext) {
        return city+ "每天都是阳光明媚，30摄氏度。";
    }
}
