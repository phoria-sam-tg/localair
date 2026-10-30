#pragma once

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct channel_crypto_s channel_crypto_t;

/**
 * Create an encrypted channel context from the pair-verify shared secret.
 *
 * Derives ChaCha20-Poly1305 keys via HKDF-SHA-512:
 *   decrypt key: salt="Control-Salt", info="Control-Write-Encryption-Key"
 *   encrypt key: salt="Control-Salt", info="Control-Read-Encryption-Key"
 *
 * @param shared_secret  32-byte ECDH shared secret from pair-verify
 * @return context, or NULL on failure
 */
channel_crypto_t *channel_crypto_init(const uint8_t *shared_secret, int secret_len);

/**
 * Decrypt a ChaCha20-Poly1305 framed block.
 *
 * Frame format: [2-byte LE length N][N encrypted bytes][16-byte Poly1305 tag]
 * AAD = the 2-byte length. Nonce = 4 zero bytes + 8-byte LE counter.
 *
 * @param ctx        context
 * @param in         raw bytes from socket (may contain partial or multiple blocks)
 * @param in_len     number of raw bytes available
 * @param out        output plaintext buffer (must be >= in_len)
 * @param out_len    [out] number of plaintext bytes produced
 * @param consumed   [out] number of raw bytes consumed from in
 * @return 0 on success, -1 on auth failure, 1 if need more data
 */
int channel_crypto_decrypt(channel_crypto_t *ctx,
                           const uint8_t *in, int in_len,
                           uint8_t *out, int *out_len, int *consumed);

/**
 * Encrypt plaintext into ChaCha20-Poly1305 framed blocks.
 *
 * @param ctx        context
 * @param in         plaintext
 * @param in_len     plaintext length
 * @param out        output buffer (must be >= in_len + 18*ceil(in_len/1024))
 * @param out_len    [out] number of ciphertext bytes produced
 * @return 0 on success, -1 on error
 */
int channel_crypto_encrypt(channel_crypto_t *ctx,
                           const uint8_t *in, int in_len,
                           uint8_t *out, int *out_len);

void channel_crypto_destroy(channel_crypto_t *ctx);

#ifdef __cplusplus
}
#endif
