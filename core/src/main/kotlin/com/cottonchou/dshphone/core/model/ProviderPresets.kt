package com.cottonchou.dshphone.core.model

/** Curated provider presets shown in onboarding. Keys live only on the device. */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val protocol: Protocol,
)

object ProviderPresets {
    val all = listOf(
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat", Protocol.OPENAI_COMPATIBLE),
        ProviderPreset("zhipu", "智谱", "https://open.bigmodel.cn/api/paas", "glm-4-plus", Protocol.OPENAI_COMPATIBLE),
        ProviderPreset("moonshot", "Kimi", "https://api.moonshot.cn", "moonshot-v1-32k", Protocol.OPENAI_COMPATIBLE),
        ProviderPreset("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode", "qwen-plus", Protocol.OPENAI_COMPATIBLE),
        ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api", "openai/gpt-4o-mini", Protocol.OPENAI_COMPATIBLE),
        ProviderPreset("anthropic", "Anthropic", "https://api.anthropic.com", "claude-sonnet-4-20250514", Protocol.ANTHROPIC),
        ProviderPreset("gemini", "Gemini", "https://generativelanguage.googleapis.com", "gemini-2.0-flash", Protocol.GEMINI),
        ProviderPreset("custom", "自定义", "https://", "custom-model", Protocol.OPENAI_COMPATIBLE),
    )
}
