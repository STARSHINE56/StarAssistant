package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.BaiduAccountEntity
import com.yunx.app.data.repository.BaiduAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 百度账号 ViewModel：暴露登录态，供主页与登录页共享。
 */
class BaiduAccountViewModel(
    private val repository: BaiduAccountRepository
) : ViewModel() {

    val baiduAccount: StateFlow<BaiduAccountEntity?> = repository.observeAccount()
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

    /** 保存百度 Cookie；返回是否保存成功 */
    suspend fun saveBaiduAccount(cookie: String): Boolean {
        val saved = repository.saveBaiduAccount(cookie, newAccount = addNewAccount)
        if (saved) addNewAccount = false
        return saved
    }

    class Factory(
        private val repository: BaiduAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BaiduAccountViewModel::class.java))
            return BaiduAccountViewModel(repository) as T
        }
    }
}