package com.calm.inbox.features.brief

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.calm.inbox.core.database.entity.BriefEntity

@Composable
fun BriefScreen(viewModel: BriefViewModel = hiltViewModel()) {
    val briefs by viewModel.briefs.collectAsState()

    if (briefs.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("还没有简报", style = MaterialTheme.typography.titleMedium)
            Text(
                "每天 22:00 会自动汇总当日通知生成简报",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(briefs, key = { it.id }) { brief ->
            BriefCard(brief)
        }
    }
}

@Composable
private fun BriefCard(brief: BriefEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(brief.date, style = MaterialTheme.typography.titleMedium)
            Text(brief.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
