package com.calm.inbox.features.brief

import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.BriefEntity
import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.model.EngineState
import com.calm.inbox.core.model.LlmEngine
import kotlinx.coroutines.flow.toList
import java.time.Clock
import java.time.LocalDate

class BriefGenerator(
    private val engine: LlmEngine?,
    private val dao: NotificationDao,
    private val clock: Clock,     // 必须可注入
) {
    suspend fun generateFor(date: LocalDate): BriefEntity {
        val start = date.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val items = dao.getByDateRange(start, end)
        val content = generateContent(date, items)
        return BriefEntity(date = date.toString(), content = content, createdAt = clock.millis())
        // 模型未就绪 → 纯统计模板降级简报（Top5 重要度排序 + 分类计数），仍入库存推送（由 BriefWorker 执行）
    }

    private suspend fun generateContent(date: LocalDate, items: List<NotificationEntity>): String {
        if (items.isEmpty()) return BriefPrompts.buildFallbackBrief(date, items)
        val activeEngine = engine?.takeIf { it.state.value == EngineState.READY }
        if (activeEngine != null) {
            try {
                val prompt = BriefPrompts.buildBriefPrompt(date, items)
                val raw = activeEngine.generateStream(prompt).toList().joinToString(separator = "")
                if (raw.isNotBlank()) return raw.trim()
            } catch (e: Exception) {
                // 引擎异常 → 降级模板
            }
        }
        return BriefPrompts.buildFallbackBrief(date, items)
    }
}

