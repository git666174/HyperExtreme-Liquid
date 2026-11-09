package io.github.libxposed.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import com.frederic22.hyperextreme.data.ActivationState

/**
 * 类名必须保持 `io.github.libxposed.service.XposedProvider` —— LSPosed 按这个名字扫描已安装模块，
 * 模块被启用后会把服务 binder 通过 `call("SendBinder", ...)` 送进来。
 *
 * 我们只关心"收到了 binder"这一事实（= 已激活），binder 本身不用。
 */
class XposedProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // 注意：Android 在 App 启动时会自动实例化 manifest 里声明的所有 Provider，
        // 所以这里**不能**当作"框架已连接"的证据，只有下面的 call("SendBinder") 才算。
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val binder = extras?.getBinder(KEY_BINDER)
        if (method == METHOD_SEND_BINDER && binder != null) {
            frameworkBinder = binder
            context?.let { ActivationState.recordFrameworkConnected(it) }
        }
        return Bundle()
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val METHOD_SEND_BINDER = "SendBinder"
        const val KEY_BINDER = "binder"
        const val AUTHORITY = "com.frederic22.hyperextreme.liquid.XposedService"

        @Volatile
        private var frameworkBinder: IBinder? = null

        /**
         * 框架的服务 binder 是否还活着。
         *
         * 用户在 LSPosed 里取消勾选模块后，框架会销毁发给我们的这个服务，`pingBinder()` 随之失败 ——
         * 界面回到前台时调一次，就能把"已激活"及时翻回"未激活"（不用等应用重启）。
         */
        fun isFrameworkAlive(): Boolean {
            val binder = frameworkBinder ?: return false
            return binder.isBinderAlive && runCatching { binder.pingBinder() }.getOrDefault(false)
        }
    }
}
