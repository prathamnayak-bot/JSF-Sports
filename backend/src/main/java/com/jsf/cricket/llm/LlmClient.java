package com.jsf.cricket.llm;

/** A chat model that turns a system prompt + user message into a reply. */
public interface LlmClient {
    String complete(String systemPrompt, String userMessage);
}
