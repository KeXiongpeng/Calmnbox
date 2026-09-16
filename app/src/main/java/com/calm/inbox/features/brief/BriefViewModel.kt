package com.calm.inbox.features.brief

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calm.inbox.core.database.dao.BriefDao
import com.calm.inbox.core.database.entity.BriefEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BriefViewModel @Inject constructor(
    briefDao: BriefDao,
) : ViewModel() {

    /** 简报列表，Room 已按 date 降序返回。 */
    val briefs: StateFlow<List<BriefEntity>> = briefDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
