package com.xhs.assistant.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping({"/", "/tasks/{taskId}", "/tasks/{taskId}/"})
    public String index(Model model) {
        model.addAttribute("appName", "小红书话题研究助手");
        return "index";
    }
}
