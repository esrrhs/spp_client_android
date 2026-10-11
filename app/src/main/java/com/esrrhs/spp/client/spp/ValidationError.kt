package com.esrrhs.spp.client.spp

/** 配置校验错误码；具体文案由资源文件按语言提供。 */
enum class ValidationError {
    NAME_REQUIRED,
    APPS_REQUIRED,
    HOST_REQUIRED,
    PORT_RANGE,
    KEY_REQUIRED,
    /** kind 既不是 spp 也不是 socks5（可能来自损坏/伪造的导入配置）。 */
    KIND_UNSUPPORTED,
    /** SPP 传输协议不在 spp 支持列表内，原样传给子进程只会得到模糊的建链失败。 */
    PROTO_UNSUPPORTED,
}
