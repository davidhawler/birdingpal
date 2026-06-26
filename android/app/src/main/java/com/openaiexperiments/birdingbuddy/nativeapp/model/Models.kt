package com.openaiexperiments.birdingbuddy.nativeapp.model


enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatMessage(
    val role: ChatRole,
    val text: String
)

