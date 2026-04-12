#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*airplay_play_url_cb)(void *cls, const char *url, double start_pos);
typedef void (*airplay_play_stop_cb)(void *cls);
typedef void (*airplay_play_rate_cb)(void *cls, float rate);
typedef void (*airplay_play_info_cb)(void *cls, double *duration, double *position, float *rate);

void airplay_video_set_callbacks(
    void *cls,
    airplay_play_url_cb play_url,
    airplay_play_stop_cb play_stop,
    airplay_play_rate_cb play_rate,
    airplay_play_info_cb play_info
);

/* Called from patched raop.c for unhandled /play, /stop, /rate, /playback-info */
int airplay_video_handle(const char *method, const char *url,
                         const char *body, int body_len,
                         char **response_data, int *response_datalen);

#ifdef __cplusplus
}
#endif
