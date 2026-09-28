package com.hong.coach.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面入口。
 * <p>
 * 应用只有两个页面（纯静态资源）：{@code /play.html} 下棋、{@code /setup.html} 摆棋。
 * 因此这里只做重定向，不引入模板引擎。
 */
@Controller
public class PageController {

    @GetMapping({"/", "/play", "/coach"})
    public String index() {
        return "redirect:/play.html";
    }

    @GetMapping({"/setup", "/board"})
    public String setup() {
        return "redirect:/setup.html";
    }
}
