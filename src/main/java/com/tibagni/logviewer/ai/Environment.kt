package com.tibagni.logviewer.ai

import com.tibagni.logviewer.ServiceLocator
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.ollama.OllamaStreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel

/**
 * Configuration for Open AI chat models.
 * This includes the model name, base URL, and API key for OpenAI.
 */
var OPENAI_MODEL_NAME = "gpt-4o-mini" // try other OpenAI model names like "gpt-3.5-turbo" or "gpt-4o";
var OPENAI_BASE_URL = "http://langchain4j.dev/demo/openai/v1" // OpenAI demo base URL
var OPENAI_API_KEY = "demo"

val openAiChatModel: ChatModel = OpenAiChatModel.builder()
  .baseUrl(OPENAI_BASE_URL)
  .modelName(OPENAI_MODEL_NAME)
  .apiKey(OPENAI_API_KEY)
  .build()

val openAIStreamingChatModel: StreamingChatModel = OpenAiStreamingChatModel.builder()
  .baseUrl(OPENAI_BASE_URL)
  .modelName(OPENAI_MODEL_NAME)
  .apiKey(OPENAI_API_KEY)
  .build()

/**
 * Configuration for Ollama chat models.
 * This includes the model name and base URL for local Ollama server.
 */
var OLLAMA_MODEL_NAME = ServiceLocator.logViewerPrefs.aiModel // try other local ollama model names
var OLLAMA_BASE_URL = ServiceLocator.logViewerPrefs.aiHost // local ollama base url

val ollamaChatModel: ChatModel = OllamaChatModel.builder()
  .baseUrl(OLLAMA_BASE_URL)
  .modelName(OLLAMA_MODEL_NAME)
  .build()

val ollamaStreamingChatModel: StreamingChatModel = OllamaStreamingChatModel.builder()
  .baseUrl(OLLAMA_BASE_URL)
  .modelName(OLLAMA_MODEL_NAME)
  .build()