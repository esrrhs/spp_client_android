package com.esrrhs.spp.client.spp

/** 配置校验错误码；具体文案由资源文件按语言提供。 */
enum class ValidationError {
    NAME_REQUIRED,
    APPS_REQUIRED,
    HOST_REQUIRED,
    PORT_RANGE,
    KEY_REQUIRED,
}
