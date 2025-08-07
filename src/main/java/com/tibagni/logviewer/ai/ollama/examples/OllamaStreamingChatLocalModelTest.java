package com.tibagni.logviewer.ai.ollama.examples;

import com.tibagni.logviewer.ai.EnvironmentKt;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.concurrent.CompletableFuture;

/**
 * Example of using Ollama streaming chat model with LangChain4j.
 * This example demonstrates how to create an Ollama streaming chat model instance
 * and use it to generate a response for a given prompt in a streaming manner.
 */
class OllamaStreamingChatLocalModelTest {

  public static void main(String[] args) {

    String userMessage = "Write a 100-word poem about Java and AI";

    CompletableFuture<ChatResponse> futureResponse = new CompletableFuture<>();
    EnvironmentKt.getOllamaStreamingChatModel().chat(userMessage, new StreamingChatResponseHandler() {

      @Override
      public void onPartialResponse(String partialResponse) {
        System.out.print(partialResponse);
      }

      @Override
      public void onCompleteResponse(ChatResponse completeResponse) {
        futureResponse.complete(completeResponse);
      }

      @Override
      public void onError(Throwable error) {
        futureResponse.completeExceptionally(error);
      }
    });

    futureResponse.join();
  }
}
