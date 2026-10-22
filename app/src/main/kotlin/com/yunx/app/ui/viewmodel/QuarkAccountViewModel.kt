package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.QuarkAccountEntity
import com.yunx.app.data.repository.QuarkAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 夸克账号 ViewModel：暴露登录态，供主页与登录页共享。
 */
class QuarkAccountViewModel(
    private val repository: QuarkAccountRepository
) : ViewModel() {

    val quarkAccount: StateFlow<QuarkAccountEntity?> = repository.observeAccount()
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

    /** 保存夸克 Cookie；返回是否保存成功 */
    suspend fun saveQuarkAccount(cookie: String): Boolean {
        val saved = repository.saveQuarkAccount(cookie, newAccount = addNewAccount)
        if (saved) addNewAccount = false
        return saved
    }

    class Factory(
        private val repository: QuarkAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(QuarkAccountViewModel::class.java))
            return QuarkAccountViewModel(repository) as T
        }
    }
}