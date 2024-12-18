package com.heyanle.easybangumi4.ui.source_manage.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heyanle.easybangumi4.LauncherBus
import com.heyanle.easybangumi4.plugin.extension.ExtensionController
import com.heyanle.easybangumi4.plugin.extension.ExtensionInfo
import com.heyanle.easybangumi4.plugin.extension.push.ExtensionPushController
import com.heyanle.easybangumi4.ui.common.moeDialogAlert
import com.heyanle.easybangumi4.ui.common.moeSnackBar
import com.heyanle.easybangumi4.utils.stringRes
import com.heyanle.inject.core.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


/**
 * Created by HeYanLe on 2024/10/27 14:27.
 * https://github.com/heyanLE
 */

class SelectViewModel: ViewModel() {

    companion object {

    }

    private val extensionController: ExtensionController by Inject.injectLazy()
    private val extensionPushController: ExtensionPushController by Inject.injectLazy()
    data class State (
        val dialog: Dialog? = null
    ){
    }
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    sealed class Dialog {
        data class Loading(val msg: String): Dialog()
        data class ErrorOrCompletely(val msg: String): Dialog()
    }

    init {
        viewModelScope.launch {
            extensionPushController.state.collectLatest { pushState ->
                _state.update {
                    it.copy(
                        dialog =  if (pushState.isDoing) {
                            Dialog.Loading(pushState.loadingMsg)
                        } else if (pushState.isError){
                            Dialog.ErrorOrCompletely(pushState.errorMsg)
                        } else if (pushState.isCompletely){
                            Dialog.ErrorOrCompletely(pushState.completelyMsg)
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun push(url: String){
        extensionPushController.push(url)
    }

    fun chooseJSFile(){
        LauncherBus.current?.getJsFile { uri ->
            if (uri == null) {
                stringRes(com.heyanle.easy_i18n.R.string.no_document).moeDialogAlert()
                return@getJsFile
            }

            viewModelScope.launch {
                val ex = extensionController.appendExtensionUri(uri, ExtensionInfo.TYPE_JS_FILE)
                if (ex == null) {
                    delay(500)
                    extensionController.scanFolder()
                    stringRes(com.heyanle.easy_i18n.R.string.extension_push_completely).moeSnackBar()
                } else {
                    (ex.message?: stringRes(com.heyanle.easy_i18n.R.string.load_error)).moeDialogAlert(
                        title = stringRes(com.heyanle.easy_i18n.R.string.extension_push_error)
                    )
                }
            }
        }
    }

    fun cleanErrorOrCompletely(){
        extensionPushController.cleanErrorOrCompletely()
    }

    fun cancelCurrent(){
        extensionPushController.cancelCurrent()
    }



}