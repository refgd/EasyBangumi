package com.heyanle.easybangumi4

import android.content.Context
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Created by HeYanLe on 2023/10/29 15:08.
 * https://github.com/heyanLE
 */
object Migrate {

    private val _isMigrating = MutableStateFlow<Boolean>(true)
    val isMigrating = _isMigrating.asStateFlow()

    object CartoonDB {
        fun getDBMigration() = listOf<Migration>()
    }

    object CacheDB {
        fun getDBMigration() = emptyList<Migration>()
    }

    fun update(context: Context) {
        _isMigrating.update {
            false
        }
    }
}


