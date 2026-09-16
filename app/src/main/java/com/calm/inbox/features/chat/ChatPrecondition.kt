package com.calm.inbox.features.chat

sealed interface ChatPrecondition {
    data object Ready : ChatPrecondition
    data object NeedsModel : ChatPrecondition
    data object EngineError : ChatPrecondition
}
