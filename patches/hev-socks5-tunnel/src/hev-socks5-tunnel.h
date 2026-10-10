/*
 ============================================================================
 Name        : hev-socks5-tunnel.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
 Description : Socks5 Tunnel
 ============================================================================
 */

#ifndef __HEV_SOCKS5_TUNNEL_H__
#define __HEV_SOCKS5_TUNNEL_H__

#include "hev-list.h"

int hev_socks5_tunnel_init (int tun_fd);
void hev_socks5_tunnel_fini (void);

int hev_socks5_tunnel_run (void);
void hev_socks5_tunnel_stop (void);

void hev_socks5_tunnel_stats (size_t *tx_packets, size_t *tx_bytes,
                              size_t *rx_packets, size_t *rx_bytes);

/**
 * 导出当前全部 TCP 会话文本，每行：
 * proto|srcIp|srcPort|dstIp|dstPort|upload|download|createdMs[|domain]。
 * 返回 malloc 分配的字符串（可能为空串），调用方负责 free。
 */
char *hev_socks5_tunnel_sessions (void);

void hev_socks5_tunnel_update_session (HevListNode *node);

#endif /* __HEV_SOCKS5_TUNNEL_H__ */
