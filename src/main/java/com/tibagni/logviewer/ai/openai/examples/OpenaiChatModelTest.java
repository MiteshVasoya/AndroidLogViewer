package com.tibagni.logviewer.ai.openai.examples;

import com.tibagni.logviewer.ai.EnvironmentKt;

/**
 * Example of using OpenAI chat model with LangChain4j.
 * This example demonstrates how to create an OpenAI chat model instance
 * and use it to generate a response for a given prompt.
 */
class OpenaiChatModelTest {

  public static void main(String[] args) {
    String answer = EnvironmentKt.getOpenAiChatModel().chat("write hello world in python");
    System.out.println(answer);
  }
}
