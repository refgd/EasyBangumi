package com.heyanle.easybangumi4.utils.permission

interface OnPermissionsResultCallback {

    fun onPermissionsGranted()

    fun onPermissionsDenied(deniedPermissions: Array<String>?)

    fun onError(e: Exception)

}