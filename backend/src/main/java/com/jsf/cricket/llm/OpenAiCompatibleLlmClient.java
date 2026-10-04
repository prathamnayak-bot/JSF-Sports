package com.jsf.cricket.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jsf.cricket.config.LlmProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties props;
    private final RestClient http;

    public OpenAiCompatibleLlmClient(LlmProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.baseUrl(props.baseUrl()).build();
    }

    record Message(String role, String content) {
    }

    record ChatRequest(String model, double temperature, List<Message> messages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(List<Choice> choices) {
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        ChatRequest request = new ChatRequest(props.model(), 0.0,
                List.of(new Message("system", systemPrompt), new Message("user", userMessage)));
        try {
            ChatResponse response = http.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (StringUtils.hasText(props.apiKey())) h.setBearerAuth(props.apiKey());
                    })
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);
            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new LlmException("LLM returned no choices");
            }
            return response.choices().getFirst().message().content();
        } catch (RestClientException e) {
            throw new LlmException("LLM request failed (check LLM_BASE_URL / LLM_API_KEY / LLM_MODEL): "
                    + e.getMessage(), e);
        }
    }
}
