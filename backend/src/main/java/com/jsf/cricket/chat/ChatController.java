package com.jsf.cricket.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    public record ChatRequest(@NotBlank @Size(max = 500) String question) {
    }

    @PostMapping
    public ChatService.ChatAnswer ask(@RequestBody @Validated ChatRequest request) {
        return chat.ask(request.question());
    }
}
