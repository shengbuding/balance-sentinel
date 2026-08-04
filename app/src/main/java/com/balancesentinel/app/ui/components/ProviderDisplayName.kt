package com.balancesentinel.app.ui.components

import androidx.annotation.StringRes
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType

@StringRes
internal fun ProviderType.displayNameResource(): Int = when (this) {
    ProviderType.OPENAI -> R.string.provider_name_openai
    ProviderType.ANTHROPIC -> R.string.provider_name_anthropic
    ProviderType.GEMINI -> R.string.provider_name_gemini
    ProviderType.MISTRAL -> R.string.provider_name_mistral
    ProviderType.COHERE -> R.string.provider_name_cohere
    ProviderType.DEEPSEEK -> R.string.provider_name_deepseek
    ProviderType.QWEN -> R.string.provider_name_qwen
    ProviderType.WENXIN -> R.string.provider_name_wenxin
    ProviderType.ZHIPU -> R.string.provider_name_zhipu
    ProviderType.MOONSHOT -> R.string.provider_name_moonshot
    ProviderType.DOUBAO -> R.string.provider_name_doubao
    ProviderType.BAICHUAN -> R.string.provider_name_baichuan
    ProviderType.MODEL_ARK -> R.string.provider_name_model_ark
    ProviderType.CUSTOM -> R.string.provider_name_custom
}
