/**
 * AirPlay 2 encrypted HTTP channel — ChaCha20-Poly1305 with HKDF-SHA-512.
 *
 * After pair-verify, the control channel switches to encrypted framing.
 * Each block: [2-byte LE length][encrypted payload (≤1024)][16-byte tag].
 * Keys derived via HKDF-SHA-512 from the ECDH shared secret.
 *
 * References:
 *   - ejurgensen/pair_ap (pair_homekit.c)
 *   - mikebrady/shairport-sync (rtsp.c, player.h)
 *   - openairplay.github.io/airplay-spec/pairing/hkp.html
 */

#include "channel_crypto.h"
#include <stdlib.h>
#include <string.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/hmac.h>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "channel_crypto", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "channel_crypto", __VA_ARGS__)

#define BLOCK_MAX 1024
#define TAG_LEN   16
#define NONCE_LEN 12
#define KEY_LEN   32

struct channel_crypto_s {
    uint8_t  decrypt_key[KEY_LEN];
    uint8_t  encrypt_key[KEY_LEN];
    uint64_t decrypt_counter;
    uint64_t encrypt_counter;
};

/* HKDF-SHA-512 extract-then-expand (single output ≤ 64 bytes) */
static int hkdf_sha512(const uint8_t *ikm, int ikm_len,
                       const uint8_t *salt, int salt_len,
                       const uint8_t *info, int info_len,
                       uint8_t *out, int out_len)
{
    /* Extract: PRK = HMAC-SHA-512(salt, ikm) */
    uint8_t prk[64];
    unsigned int prk_len = sizeof(prk);
    if (!HMAC(EVP_sha512(), salt, salt_len, ikm, ikm_len, prk, &prk_len))
        return -1;

    /* Expand: T(1) = HMAC-SHA-512(PRK, info || 0x01) */
    uint8_t t[64];
    unsigned int t_len = sizeof(t);
    unsigned char expand_input[256];
    if (info_len + 1 > (int)sizeof(expand_input)) return -1;
    memcpy(expand_input, info, info_len);
    expand_input[info_len] = 0x01;
    if (!HMAC(EVP_sha512(), prk, prk_len, expand_input, info_len + 1, t, &t_len))
        return -1;

    if (out_len > (int)t_len) return -1;
    memcpy(out, t, out_len);
    return 0;
}

static void make_nonce(uint64_t counter, uint8_t nonce[NONCE_LEN])
{
    memset(nonce, 0, NONCE_LEN);
    /* Counter at bytes [4:12], little-endian */
    memcpy(nonce + 4, &counter, sizeof(counter));
}

static int chacha20_poly1305_decrypt(const uint8_t *key,
                                     const uint8_t *nonce,
                                     const uint8_t *aad, int aad_len,
                                     const uint8_t *ct, int ct_len,
                                     const uint8_t *tag,
                                     uint8_t *pt)
{
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;

    int ret = -1, len = 0;

    if (EVP_DecryptInit_ex(ctx, EVP_chacha20_poly1305(), NULL, NULL, NULL) != 1) goto out;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, NONCE_LEN, NULL) != 1) goto out;
    if (EVP_DecryptInit_ex(ctx, NULL, NULL, key, nonce) != 1) goto out;

    /* AAD */
    if (aad_len > 0) {
        if (EVP_DecryptUpdate(ctx, NULL, &len, aad, aad_len) != 1) goto out;
    }

    /* Decrypt ciphertext */
    if (EVP_DecryptUpdate(ctx, pt, &len, ct, ct_len) != 1) goto out;

    /* Set expected tag */
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_TAG, TAG_LEN, (void *)tag) != 1) goto out;

    /* Verify tag */
    if (EVP_DecryptFinal_ex(ctx, pt + len, &len) != 1) {
        LOGE("Poly1305 tag verification failed");
        goto out;
    }

    ret = 0;
out:
    EVP_CIPHER_CTX_free(ctx);
    return ret;
}

static int chacha20_poly1305_encrypt(const uint8_t *key,
                                     const uint8_t *nonce,
                                     const uint8_t *aad, int aad_len,
                                     const uint8_t *pt, int pt_len,
                                     uint8_t *ct, uint8_t *tag)
{
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;

    int ret = -1, len = 0;

    if (EVP_EncryptInit_ex(ctx, EVP_chacha20_poly1305(), NULL, NULL, NULL) != 1) goto out;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, NONCE_LEN, NULL) != 1) goto out;
    if (EVP_EncryptInit_ex(ctx, NULL, NULL, key, nonce) != 1) goto out;

    if (aad_len > 0) {
        if (EVP_EncryptUpdate(ctx, NULL, &len, aad, aad_len) != 1) goto out;
    }
    if (EVP_EncryptUpdate(ctx, ct, &len, pt, pt_len) != 1) goto out;
    if (EVP_EncryptFinal_ex(ctx, ct + len, &len) != 1) goto out;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_GET_TAG, TAG_LEN, tag) != 1) goto out;

    ret = 0;
out:
    EVP_CIPHER_CTX_free(ctx);
    return ret;
}

channel_crypto_t *channel_crypto_init(const uint8_t *shared_secret, int secret_len)
{
    channel_crypto_t *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return NULL;

    /* Server reads = client writes → "Control-Write-Encryption-Key" */
    if (hkdf_sha512(shared_secret, secret_len,
                    (const uint8_t *)"Control-Salt", 12,
                    (const uint8_t *)"Control-Write-Encryption-Key", 28,
                    ctx->decrypt_key, KEY_LEN) < 0) {
        LOGE("HKDF decrypt key derivation failed");
        free(ctx); return NULL;
    }

    /* Server writes = client reads → "Control-Read-Encryption-Key" */
    if (hkdf_sha512(shared_secret, secret_len,
                    (const uint8_t *)"Control-Salt", 12,
                    (const uint8_t *)"Control-Read-Encryption-Key", 27,
                    ctx->encrypt_key, KEY_LEN) < 0) {
        LOGE("HKDF encrypt key derivation failed");
        free(ctx); return NULL;
    }

    ctx->decrypt_counter = 0;
    ctx->encrypt_counter = 0;

    LOGI("encrypted channel initialized");
    return ctx;
}

int channel_crypto_decrypt(channel_crypto_t *ctx,
                           const uint8_t *in, int in_len,
                           uint8_t *out, int *out_len, int *consumed)
{
    *out_len = 0;
    *consumed = 0;

    while (*consumed + 2 <= in_len) {
        /* Read 2-byte LE block length */
        uint16_t block_len = in[*consumed] | (in[*consumed + 1] << 8);

        int frame_size = 2 + block_len + TAG_LEN;
        if (*consumed + frame_size > in_len) {
            return 1;  /* need more data */
        }

        const uint8_t *aad = in + *consumed;           /* 2-byte length */
        const uint8_t *ct  = in + *consumed + 2;        /* encrypted payload */
        const uint8_t *tag = ct + block_len;             /* Poly1305 tag */

        uint8_t nonce[NONCE_LEN];
        make_nonce(ctx->decrypt_counter, nonce);

        if (chacha20_poly1305_decrypt(ctx->decrypt_key, nonce,
                                      aad, 2, ct, block_len, tag,
                                      out + *out_len) < 0) {
            LOGE("decrypt failed at counter %llu, block_len %d",
                 (unsigned long long)ctx->decrypt_counter, block_len);
            return -1;
        }

        *out_len += block_len;
        *consumed += frame_size;
        ctx->decrypt_counter++;
    }

    return 0;
}

int channel_crypto_encrypt(channel_crypto_t *ctx,
                           const uint8_t *in, int in_len,
                           uint8_t *out, int *out_len)
{
    *out_len = 0;
    int pos = 0;

    while (pos < in_len) {
        int block_len = in_len - pos;
        if (block_len > BLOCK_MAX) block_len = BLOCK_MAX;

        /* 2-byte LE length */
        out[*out_len]     = block_len & 0xFF;
        out[*out_len + 1] = (block_len >> 8) & 0xFF;

        uint8_t nonce[NONCE_LEN];
        make_nonce(ctx->encrypt_counter, nonce);

        uint8_t tag[TAG_LEN];
        if (chacha20_poly1305_encrypt(ctx->encrypt_key, nonce,
                                      out + *out_len, 2,  /* AAD = 2-byte length */
                                      in + pos, block_len,
                                      out + *out_len + 2, tag) < 0) {
            LOGE("encrypt failed at counter %llu", (unsigned long long)ctx->encrypt_counter);
            return -1;
        }

        memcpy(out + *out_len + 2 + block_len, tag, TAG_LEN);

        *out_len += 2 + block_len + TAG_LEN;
        pos += block_len;
        ctx->encrypt_counter++;
    }

    return 0;
}

void channel_crypto_destroy(channel_crypto_t *ctx)
{
    if (ctx) {
        memset(ctx, 0, sizeof(*ctx));
        free(ctx);
    }
}
