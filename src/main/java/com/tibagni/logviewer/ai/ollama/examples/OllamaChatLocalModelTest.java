package com.tibagni.logviewer.ai.ollama.examples;

import com.tibagni.logviewer.ai.EnvironmentKt;

/**
 * Example of using Ollama chat model with LangChain4j.
 * This example demonstrates how to create an Ollama chat model instance
 * and use it to generate a response for a given prompt.
 */
class OllamaChatLocalModelTest {

  public static void main(String[] args) {

    String answer = EnvironmentKt.getOllamaChatModel().chat("write hello world in python");
    System.out.println(answer);
  }
}
