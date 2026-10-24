package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.C139AccountEntity
import com.yunx.app.data.repository.C139AccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 移动云盘账号 ViewModel：暴露登录态，供主页与登录页共享。
 */
class C139AccountViewModel(
    private val repository: C139AccountRepository
) : ViewModel() {

    val c139Account: StateFlow<C139AccountEntity?> = repository.observeAccount()
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

    /** 保存 139 Cookie；返回是否保存成功 */
    suspend fun saveC139Account(cookie: String): Boolean {
        val saved = repository.saveC139Account(cookie, newAccount = addNewAccount)
        if (saved) addNewAccount = false
        return saved
    }

    class Factory(
        private val repository: C139AccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(C139AccountViewModel::class.java))
            return C139AccountViewModel(repository) as T
        }
    }
}