package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.UCAccountEntity
import com.yunx.app.data.repository.UCAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * UC 账号 ViewModel。
 */
class UCAccountViewModel(
    private val repository: UCAccountRepository
) : ViewModel() {

    val ucAccount: StateFlow<UCAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /**
     * 「添加账号」标记：网盘页账号弹窗点「添加账号」时置位，登录页关闭时清掉。
     * 置位后这一次登录会落成新的账号行，而不是覆盖当前主账号（见 [resolveAccountSlot]）。
     */
    private var addNewAccount = false

    fun beginAddAccount() {
        addNewAccount = true
    }

    fun cancelAddAccount() {
        addNewAccount = false
    }

    /** 保存 UC Cookie；返回是否保存成功 */
    suspend fun saveUCAccount(cookie: String): Boolean {
        val saved = repository.saveUCAccount(cookie, newAccount = addNewAccount)
        if (saved) addNewAccount = false
        return saved
    }

    class Factory(
        private val repository: UCAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(UCAccountViewModel::class.java))
            return UCAccountViewModel(repository) as T
        }
    }
}