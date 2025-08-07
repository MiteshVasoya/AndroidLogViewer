package com.tibagni.logviewer.ai

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import java.util.concurrent.CompletableFuture
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/*
 * This class handles the interaction between the AI assist model and the view.
 * It sends user messages to the Ollama server and updates the chat view with AI responses.
 */
class AIAssistController(
  private val model: AIAssistModel,
  private val view: AIAssistView
) {
  private val parser = Parser.builder().build()
  private val renderer = HtmlRenderer.builder().build()
  // Use a streaming chat model for AI responses
  private val aiStreamingChatModel = ollamaStreamingChatModel

  // Initializes the controller with the view and sets up action listeners
  init {
    view.sendButton.addActionListener { sendMessage() }
    view.inputField.addActionListener { sendMessage() }
  }

  // Sends the user's message to the Ollama server and updates the chat view
  private fun sendMessage() {
    val userMessage = view.inputField.text.trim()
    if (userMessage.isEmpty()) return

    // Append the user's message to the chat history and clear the input field
    appendMarkdown("**$userMessage**")
    view.inputField.text = ""
    updateAIMessage("...")
    updateInputStatus(false)

    // Start a new thread to handle the AI response
    Thread {
      val futureResponse = CompletableFuture<ChatResponse>()

      var partialResponseHistory = ""
      aiStreamingChatModel.chat(
        userMessage,
        object : StreamingChatResponseHandler {
          override fun onPartialResponse(partialResponse: String?) {
            SwingUtilities.invokeLater {
              partialResponseHistory += partialResponse
              updateAIMessage(partialResponseHistory)
            }
          }

          override fun onCompleteResponse(completeResponse: ChatResponse?) {
            futureResponse.complete(completeResponse)
            SwingUtilities.invokeLater {
              appendMarkdown("${completeResponse?.aiMessage()?.text()}")
              appendBreak()
              updateInputStatus(true)
            }
          }

          override fun onError(error: Throwable) {
            futureResponse.completeExceptionally(error)
            SwingUtilities.invokeLater {
              JOptionPane.showMessageDialog(view, "AI server is not reachable.", "Error", JOptionPane.ERROR_MESSAGE)
              appendMarkdown("[Error: ${error.message}]")
              appendBreak()
              updateInputStatus(true)
            }
          }
        }
      )

      futureResponse.join()
    }.start()
  }

  // Updates the AI message in the chat view
  private fun updateAIMessage(aiText: String) {
    val tempHistory = model.cloneHistory()
    val document = parser.parse("$aiText\n")
    val html = renderer.render(document)
    view.setChatText("$tempHistory$html")
  }

  // Appends a message to the chat history
  private fun appendMarkdown(markdown: String) {
    val document = parser.parse(markdown)
    val html = renderer.render(document)
    model.append(html)
    view.setChatText(model.getHistory())
  }

  // Appends a break in the chat history
  private fun appendBreak() {
    appendMarkdown("<br>")
    appendMarkdown("---")
  }

  // Updates the input field and send button status
  private fun updateInputStatus(status: Boolean) {
    view.inputField.isEnabled = status
    view.sendButton.isEnabled = status
  }
}