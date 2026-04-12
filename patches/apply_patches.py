#!/usr/bin/env python3
"""
Patches RPiPlay for AirPlay 2 encrypted HTTP channel + video streaming.

Modifies: httpd.c, httpd.h, raop.c, raop_handlers.h
Creates the encrypted channel (ChaCha20-Poly1305) after pair-verify on
HTTP connections, enabling /play URL streaming for YouTube etc.
"""
import sys

def patch(path, replacements):
    with open(path) as f:
        src = f.read()
    for old, new in replacements:
        if old in src:
            src = src.replace(old, new, 1)
        else:
            marker = new.split('\n')[1].strip()[:40] if '\n' in new else new[:40]
            if marker in src:
                continue  # already patched
            print(f"  WARN: target not found in {path}: {old[:60]}...")
    with open(path, 'w') as f:
        f.write(src)


def main():
    raop_c = sys.argv[1]
    raop_h = sys.argv[2]
    httpd_c = sys.argv[3]
    httpd_h = sys.argv[4]
    raop_handlers_h = sys.argv[5]

    # ── httpd.h ──
    patch(httpd_h, [(
        'void httpd_destroy(httpd_t *httpd);',
        '''void httpd_destroy(httpd_t *httpd);

struct channel_crypto_s;
typedef struct channel_crypto_s channel_crypto_t;
void httpd_set_encryption(httpd_t *httpd, void *user_data, channel_crypto_t *crypto);'''
    )])

    # ── httpd.c ──
    patch(httpd_c, [
        # Include
        ('#include <assert.h>',
         '#include <assert.h>\n#include "channel_crypto.h"'),

        # Add crypto + decrypt_buf to connection struct
        ('    http_request_t *request;\n};',
         '    http_request_t *request;\n    channel_crypto_t *crypto;\n    uint8_t decrypt_buf[16384];\n    int decrypt_buf_len;\n};'),

        # Init new fields on accept
        ('    connection->user_data = httpd->callbacks.conn_init(httpd->callbacks.opaque);',
         '    connection->user_data = httpd->callbacks.conn_init(httpd->callbacks.opaque);\n'
         '    connection->crypto = NULL;\n'
         '    connection->decrypt_buf_len = 0;'),
    ])

    # Decrypt recv / encrypt send + PTTH upgrade + httpd_set_encryption
    with open(httpd_c) as f:
        src = f.read()

    # Wrap the parse section with decrypt support
    old_parse = '''            /* Parse HTTP request from data read from connection */
            http_request_add_data(connection->request, buffer, ret);
            if (http_request_has_error(connection->request)) {'''

    new_parse = '''            /* Decrypt if encrypted channel is active */
            char *parse_buf = buffer;
            int parse_len = ret;
            uint8_t plaintext_buf[16384];
            if (connection->crypto) {
                if (connection->decrypt_buf_len + ret <= (int)sizeof(connection->decrypt_buf)) {
                    memcpy(connection->decrypt_buf + connection->decrypt_buf_len, buffer, ret);
                    connection->decrypt_buf_len += ret;
                }
                int out_len = 0, consumed = 0;
                int rc = channel_crypto_decrypt(connection->crypto,
                    connection->decrypt_buf, connection->decrypt_buf_len,
                    plaintext_buf, &out_len, &consumed);
                if (rc < 0) {
                    logger_log(httpd->logger, LOGGER_ERR, "httpd: channel decrypt failed");
                    httpd_remove_connection(httpd, connection);
                    continue;
                }
                if (consumed > 0) {
                    memmove(connection->decrypt_buf,
                            connection->decrypt_buf + consumed,
                            connection->decrypt_buf_len - consumed);
                    connection->decrypt_buf_len -= consumed;
                }
                if (out_len == 0) {
                    logger_log(httpd->logger, LOGGER_DEBUG, "httpd: need more encrypted data");
                    continue;
                }
                parse_buf = (char *)plaintext_buf;
                parse_len = out_len;
                logger_log(httpd->logger, LOGGER_DEBUG,
                    "httpd: decrypted %d bytes from %d raw", out_len, ret);
            }

            /* Parse HTTP request from data */
            http_request_add_data(connection->request, parse_buf, parse_len);
            if (http_request_has_error(connection->request)) {
                /* Handle PTTH reverse channel upgrade */
                const char *upgrade = http_request_get_header(connection->request, "Upgrade");
                if (upgrade && strstr(upgrade, "PTTH")) {
                    logger_log(httpd->logger, LOGGER_INFO,
                        "httpd: PTTH reverse channel on sock %d", connection->socket_fd);
                    const char *resp101 = "HTTP/1.1 101 Switching Protocols\\r\\nUpgrade: PTTH/1.0\\r\\nConnection: Upgrade\\r\\n\\r\\n";
                    if (connection->crypto) {
                        uint8_t enc[2048]; int enc_len = 0;
                        channel_crypto_encrypt(connection->crypto,
                            (const uint8_t *)resp101, strlen(resp101), enc, &enc_len);
                        send(connection->socket_fd, enc, enc_len, 0);
                    } else {
                        send(connection->socket_fd, resp101, strlen(resp101), 0);
                    }
                    http_request_destroy(connection->request);
                    connection->request = NULL;
                    continue;
                }'''

    if old_parse in src:
        src = src.replace(old_parse, new_parse, 1)

    # Encrypt send
    old_send = '''                    while (written < datalen) {
                        ret = send(connection->socket_fd, data+written, datalen-written, 0);'''
    new_send = '''                    const char *send_data = data;
                    int send_len = datalen;
                    uint8_t encrypted_response[32768];
                    if (connection->crypto && datalen > 0) {
                        int enc_len = 0;
                        if (channel_crypto_encrypt(connection->crypto,
                                (const uint8_t *)data, datalen,
                                encrypted_response, &enc_len) == 0) {
                            send_data = (const char *)encrypted_response;
                            send_len = enc_len;
                        }
                    }

                    while (written < send_len) {
                        ret = send(connection->socket_fd, send_data+written, send_len-written, 0);'''

    if old_send in src:
        src = src.replace(old_send, new_send, 1)

    # Add httpd_set_encryption function
    if 'httpd_set_encryption' not in src:
        src = src.replace(
            'void\nhttpd_destroy(httpd_t *httpd)',
            '''void
httpd_set_encryption(httpd_t *httpd, void *user_data, channel_crypto_t *crypto)
{
    if (!httpd) return;
    for (int i = 0; i < httpd->max_connections; i++) {
        if (httpd->connections[i].connected &&
            httpd->connections[i].user_data == user_data) {
            httpd->connections[i].crypto = crypto;
            httpd->connections[i].decrypt_buf_len = 0;
            logger_log(httpd->logger, LOGGER_INFO,
                "httpd: encryption enabled on socket %d",
                httpd->connections[i].socket_fd);
            return;
        }
    }
}

void
httpd_destroy(httpd_t *httpd)''')

    with open(httpd_c, 'w') as f:
        f.write(src)

    # ── raop.c ──
    patch(raop_c, [
        # Includes
        ('#include "raop_handlers.h"',
         '#include "raop_handlers.h"\n#include "airplay_video.h"\n#include "channel_crypto.h"'),

        # /server-info alias
        ('if (!strcmp(method, "GET") && !strcmp(url, "/info")) {',
         'if (!strcmp(method, "GET") && (!strcmp(url, "/info") || !strcmp(url, "/server-info"))) {'),

        # Add is_http flag to raop_conn_s
        ('    int remotelen;',
         '    int remotelen;\n    int is_http;\n    channel_crypto_t *crypto;'),
    ])

    # CSeq-less handling + catch-all
    with open(raop_c) as f:
        src = f.read()

    # Set is_http flag + handle CSeq-less requests
    old_cseq = '''    if (!method || !cseq) {
        return;
    }'''
    new_cseq = '''    if (!method) {
        return;
    }

    if (!cseq) {
        conn->is_http = 1;
        int datalen_v = 0;
        const char *data_v = http_request_get_data(request, &datalen_v);
        char *resp_data = NULL; int resp_len = 0;
        if (airplay_video_handle(method, url, data_v, datalen_v, &resp_data, &resp_len)) {
            *response = http_response_init("HTTP/1.1", 200, "OK");
            http_response_add_header(*response, "Server", "AirTunes/220.68");
            http_response_finish(*response, resp_data, resp_len);
            if (resp_data) free(resp_data);
            return;
        }
        cseq = "0";
        logger_log(conn->raop->logger, LOGGER_DEBUG,
            "HTTP fallthrough %s %s (synthetic CSeq)", method, url);
    }'''
    if old_cseq in src:
        src = src.replace(old_cseq, new_cseq, 1)

    # Catch-all before handler dispatch
    old_handler_check = '    if (handler != NULL) {'
    new_handler_check = '''    } else {
        int datalen_v = 0;
        const char *data_v = http_request_get_data(request, &datalen_v);
        airplay_video_handle(method, url, data_v, datalen_v, &response_data, &response_datalen);
    }
    if (handler != NULL) {'''
    idx = src.rfind(old_handler_check)
    if idx >= 0 and 'airplay_video_handle' not in src[idx-200:idx]:
        src = src[:idx] + new_handler_check + src[idx + len(old_handler_check):]

    # Init new fields in conn_init
    old_conn_init = '    return conn;'
    new_conn_init = '    conn->is_http = 0;\n    conn->crypto = NULL;\n    return conn;'
    # Find the one in conn_init function (first occurrence)
    first_return = src.find(old_conn_init)
    if first_return >= 0 and 'is_http' not in src[first_return-50:first_return]:
        src = src[:first_return] + new_conn_init + src[first_return + len(old_conn_init):]

    with open(raop_c, 'w') as f:
        f.write(src)

    # ── raop_handlers.h: activate encryption after pair-verify on HTTP connections ──
    patch(raop_handlers_h, [(
        '            http_response_add_header(response, "Content-Type", "application/octet-stream");\n            break;\n    }\n}',

        '''            http_response_add_header(response, "Content-Type", "application/octet-stream");

            /* Activate encrypted channel on HTTP connections */
            if (conn->is_http && !conn->crypto) {
                unsigned char ecdh_secret[32];
                pairing_get_ecdh_secret_key(conn->pairing, ecdh_secret);
                conn->crypto = channel_crypto_init(ecdh_secret, 32);
                if (conn->crypto) {
                    httpd_set_encryption(conn->raop->httpd, conn, conn->crypto);
                    logger_log(conn->raop->logger, LOGGER_INFO,
                        "Encrypted channel activated after pair-verify");
                }
            }

            break;
    }
}'''
    )])

    print("  all patches applied")


if __name__ == '__main__':
    main()
