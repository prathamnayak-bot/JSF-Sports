package com.jsf.cricket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for any OpenAI-compatible chat-completions API
 * (OpenAI, Groq, OpenRouter, a local Ollama server, ...).
 */
@ConfigurationProperties("app.llm")
public record LlmProperties(String baseUrl, String apiKey, String model) {
}
