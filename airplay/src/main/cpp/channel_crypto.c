/**
 * AirPlay 2 encrypted HTTP channel — ChaCha20-Poly1305 with HKDF-SHA-512.
 */

#include "channel_crypto.h"
#include <stdlib.h>
#include <string.h>
#include <openssl/evp.h>
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
    uint8_t  alt_decrypt_key[KEY_LEN];
    uint8_t  alt_encrypt_key[KEY_LEN];
    uint64_t decrypt_counter;
    uint64_t encrypt_counter;
    int use_alt_keys;  /* set to 1 if alt keys succeed */
};

static int hkdf_sha512(const uint8_t *ikm, int ikm_len,
                       const uint8_t *salt, int salt_len,
                       const uint8_t *info, int info_len,
                       uint8_t *out, int out_len)
{
    uint8_t prk[64];
    unsigned int prk_len = sizeof(prk);
    if (!HMAC(EVP_sha512(), salt, salt_len, ikm, ikm_len, prk, &prk_len))
        return -1;

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
    memcpy(nonce + 4, &counter, sizeof(counter));
}

static int chacha_decrypt(const uint8_t *key, const uint8_t *nonce,
                          const uint8_t *aad, int aad_len,
                          const uint8_t *ct, int ct_len,
                          const uint8_t *tag, uint8_t *pt)
{
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (!ctx) return -1;
    int ret = -1, len = 0;
    if (EVP_DecryptInit_ex(ctx, EVP_chacha20_poly1305(), NULL, NULL, NULL) != 1) goto out;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, NONCE_LEN, NULL) != 1) goto out;
    if (EVP_DecryptInit_ex(ctx, NULL, NULL, key, nonce) != 1) goto out;
    if (aad_len > 0 && EVP_DecryptUpdate(ctx, NULL, &len, aad, aad_len) != 1) goto out;
    if (ct_len > 0 && EVP_DecryptUpdate(ctx, pt, &len, ct, ct_len) != 1) goto out;
    if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_TAG, TAG_LEN, (void *)tag) != 1) goto out;
    if (EVP_DecryptFinal_ex(ctx, pt + len, &len) != 1) goto out;
    ret = 0;
out:
    EVP_CIPHER_CTX_free(ctx);
    return ret;
}

static int chacha_encrypt(const uint8_t *key, const uint8_t *nonce,
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
    if (aad_len > 0 && EVP_EncryptUpdate(ctx, NULL, &len, aad, aad_len) != 1) goto out;
    if (pt_len > 0 && EVP_EncryptUpdate(ctx, ct, &len, pt, pt_len) != 1) goto out;
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

    /* Some AirPlay 2 implementations derive a session key first:
     *   session_key = HKDF(ikm=ecdh_secret, salt="Pair-Verify-Encrypt-Salt",
     *                       info="Pair-Verify-Encrypt-Info", len=32)
     * Then use session_key as IKM for control channel keys.
     * Try this if direct derivation fails. Store both for fallback. */
    uint8_t session_key[KEY_LEN];
    if (hkdf_sha512(shared_secret, secret_len,
                    (const uint8_t *)"Pair-Verify-Encrypt-Salt", 24,
                    (const uint8_t *)"Pair-Verify-Encrypt-Info", 24,
                    session_key, KEY_LEN) < 0) {
        LOGE("HKDF session key failed"); free(ctx); return NULL;
    }

    /* Try direct derivation first (raw ECDH → control keys) */
    if (hkdf_sha512(shared_secret, secret_len,
                    (const uint8_t *)"Control-Salt", 12,
                    (const uint8_t *)"Control-Write-Encryption-Key", 28,
                    ctx->decrypt_key, KEY_LEN) < 0) {
        LOGE("HKDF decrypt key failed"); free(ctx); return NULL;
    }
    if (hkdf_sha512(shared_secret, secret_len,
                    (const uint8_t *)"Control-Salt", 12,
                    (const uint8_t *)"Control-Read-Encryption-Key", 27,
                    ctx->encrypt_key, KEY_LEN) < 0) {
        LOGE("HKDF encrypt key failed"); free(ctx); return NULL;
    }

    /* Also derive from session_key (double-HKDF path) for fallback */
    uint8_t alt_dk[KEY_LEN], alt_ek[KEY_LEN];
    hkdf_sha512(session_key, KEY_LEN,
                (const uint8_t *)"Control-Salt", 12,
                (const uint8_t *)"Control-Write-Encryption-Key", 28,
                alt_dk, KEY_LEN);
    hkdf_sha512(session_key, KEY_LEN,
                (const uint8_t *)"Control-Salt", 12,
                (const uint8_t *)"Control-Read-Encryption-Key", 27,
                alt_ek, KEY_LEN);

    LOGI("alt keys (via session_key): dk[0:4]=%02x%02x%02x%02x ek[0:4]=%02x%02x%02x%02x",
         alt_dk[0], alt_dk[1], alt_dk[2], alt_dk[3],
         alt_ek[0], alt_ek[1], alt_ek[2], alt_ek[3]);

    /* Store alt keys in a second set for try_decrypt_block to try */
    memcpy(ctx->alt_decrypt_key, alt_dk, KEY_LEN);
    memcpy(ctx->alt_encrypt_key, alt_ek, KEY_LEN);
    ctx->decrypt_counter = 0;
    ctx->encrypt_counter = 0;

    LOGI("initialized — dk[0:4]=%02x%02x%02x%02x ek[0:4]=%02x%02x%02x%02x secret[0:4]=%02x%02x%02x%02x",
         ctx->decrypt_key[0], ctx->decrypt_key[1], ctx->decrypt_key[2], ctx->decrypt_key[3],
         ctx->encrypt_key[0], ctx->encrypt_key[1], ctx->encrypt_key[2], ctx->encrypt_key[3],
         shared_secret[0], shared_secret[1], shared_secret[2], shared_secret[3]);
    return ctx;
}

/**
 * Try to decrypt one block from the buffer at different header offsets.
 * Returns: bytes consumed on success (sets *pt_len), -1 on auth fail, 0 if need more data.
 */
static int try_decrypt_block(channel_crypto_t *ctx,
                             const uint8_t *buf, int buf_len,
                             int hdr_skip,  /* 0 for standard, 2 for prefixed */
                             int big_endian,
                             uint8_t *pt, int *pt_len)
{
    if (buf_len < hdr_skip + 2) return 0;

    uint16_t block_len;
    if (big_endian)
        block_len = (buf[hdr_skip] << 8) | buf[hdr_skip + 1];
    else
        block_len = buf[hdr_skip] | (buf[hdr_skip + 1] << 8);

    if (block_len > 4096) return -1;

    int frame_size = hdr_skip + 2 + block_len + TAG_LEN;
    if (buf_len < frame_size) return 0;

    const uint8_t *aad = buf + hdr_skip;            /* 2-byte length as AAD */
    const uint8_t *ct  = buf + hdr_skip + 2;
    const uint8_t *tag = ct + block_len;

    uint8_t nonce[NONCE_LEN];
    make_nonce(ctx->decrypt_counter, nonce);

    if (chacha_decrypt(ctx->decrypt_key, nonce, aad, 2, ct, block_len, tag, pt) < 0)
        return -1;

    *pt_len = block_len;
    return frame_size;
}

int channel_crypto_decrypt(channel_crypto_t *ctx,
                           const uint8_t *in, int in_len,
                           uint8_t *out, int *out_len, int *consumed)
{
    *out_len = 0;
    *consumed = 0;

    LOGI("decrypt: %d bytes, first 8: %02x %02x %02x %02x %02x %02x %02x %02x, counter=%llu",
         in_len,
         in_len > 0 ? in[0] : 0, in_len > 1 ? in[1] : 0,
         in_len > 2 ? in[2] : 0, in_len > 3 ? in[3] : 0,
         in_len > 4 ? in[4] : 0, in_len > 5 ? in[5] : 0,
         in_len > 6 ? in[6] : 0, in_len > 7 ? in[7] : 0,
         (unsigned long long)ctx->decrypt_counter);

    /* Try multiple frame format interpretations. Once one succeeds,
     * stick with it for subsequent blocks in this call. */
    static int format = -1;  /* -1 = unknown, 0 = standard 2-byte LE,
                                 1 = 4-byte with LE, 2 = 4-byte with BE */

    while (*consumed < in_len) {
        int pt_len = 0;
        int rc;

        if (format >= 0) {
            /* Known format */
            int skip = (format >= 1) ? 2 : 0;
            int be = (format == 2) ? 1 : 0;
            rc = try_decrypt_block(ctx, in + *consumed, in_len - *consumed,
                                   skip, be, out + *out_len, &pt_len);
            if (rc > 0) {
                *out_len += pt_len;
                *consumed += rc;
                ctx->decrypt_counter++;
                continue;
            }
            if (rc == 0) return 1;  /* need more data */
            LOGE("decrypt failed with format %d, resetting", format);
            format = -1;
            return -1;
        }

        /* Try all three formats × two key sets (primary + alt) */
        for (int k = 0; k <= 1; k++) {
            if (k == 1) {
                /* Swap to alt keys for this attempt */
                uint8_t tmp[KEY_LEN];
                memcpy(tmp, ctx->decrypt_key, KEY_LEN);
                memcpy(ctx->decrypt_key, ctx->alt_decrypt_key, KEY_LEN);
                memcpy(ctx->alt_decrypt_key, tmp, KEY_LEN);
            }
            for (int f = 0; f <= 2; f++) {
                int skip = (f >= 1) ? 2 : 0;
                int be = (f == 2) ? 1 : 0;
                uint64_t saved = ctx->decrypt_counter;
                rc = try_decrypt_block(ctx, in + *consumed, in_len - *consumed,
                                       skip, be, out + *out_len, &pt_len);
                if (rc > 0) {
                    format = f;
                    ctx->use_alt_keys = k;
                    LOGI("SUCCESS: format=%s keys=%s, decrypted %d bytes",
                         f == 0 ? "standard-2B-LE" :
                         f == 1 ? "4B-prefix-LE" : "4B-prefix-BE",
                         k == 0 ? "primary" : "alt(session_key)",
                         pt_len);
                    if (k == 1) {
                        /* Keep alt keys as primary (they worked) */
                        uint8_t t2[KEY_LEN];
                        memcpy(t2, ctx->encrypt_key, KEY_LEN);
                        memcpy(ctx->encrypt_key, ctx->alt_encrypt_key, KEY_LEN);
                        memcpy(ctx->alt_encrypt_key, t2, KEY_LEN);
                    }
                    *out_len += pt_len;
                    *consumed += rc;
                    ctx->decrypt_counter++;
                    goto next_block;
                }
                ctx->decrypt_counter = saved;
            }
            if (k == 1) {
                /* Swap back if alt didn't work either */
                uint8_t tmp[KEY_LEN];
                memcpy(tmp, ctx->decrypt_key, KEY_LEN);
                memcpy(ctx->decrypt_key, ctx->alt_decrypt_key, KEY_LEN);
                memcpy(ctx->alt_decrypt_key, tmp, KEY_LEN);
            }
        }
        LOGE("all format+key attempts failed at offset %d", *consumed);
        return -1;

        next_block: ;
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

        out[*out_len]     = block_len & 0xFF;
        out[*out_len + 1] = (block_len >> 8) & 0xFF;

        uint8_t nonce[NONCE_LEN];
        make_nonce(ctx->encrypt_counter, nonce);

        uint8_t tag[TAG_LEN];
        if (chacha_encrypt(ctx->encrypt_key, nonce,
                           out + *out_len, 2,
                           in + pos, block_len,
                           out + *out_len + 2, tag) < 0) {
            LOGE("encrypt failed"); return -1;
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
    if (ctx) { memset(ctx, 0, sizeof(*ctx)); free(ctx); }
}
