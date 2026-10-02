package com.esrrhs.spp.client.util

import android.content.Context
import com.esrrhs.spp.client.R
import com.esrrhs.spp.client.spp.ValidationError

/** 校验错误码的本地化文案。 */
object ValidationMessages {

    fun text(context: Context, error: ValidationError): String = context.getString(
        when (error) {
            ValidationError.NAME_REQUIRED -> R.string.err_name_required
            ValidationError.APPS_REQUIRED -> R.string.err_apps_required
            ValidationError.HOST_REQUIRED -> R.string.err_host_required
            ValidationError.PORT_RANGE -> R.string.err_port_range
            ValidationError.KEY_REQUIRED -> R.string.err_key_required
        },
    )
}
