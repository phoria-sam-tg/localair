#include "airplay_video.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <plist/plist.h>

static void *g_cls = NULL;
static airplay_play_url_cb  g_play_url  = NULL;
static airplay_play_stop_cb g_play_stop = NULL;
static airplay_play_rate_cb g_play_rate = NULL;
static airplay_play_info_cb g_play_info = NULL;

void airplay_video_set_callbacks(
    void *cls,
    airplay_play_url_cb play_url,
    airplay_play_stop_cb play_stop,
    airplay_play_rate_cb play_rate,
    airplay_play_info_cb play_info)
{
    g_cls = cls;
    g_play_url  = play_url;
    g_play_stop = play_stop;
    g_play_rate = play_rate;
    g_play_info = play_info;
}

static int handle_play(const char *body, int body_len) {
    if (!body || body_len <= 0 || !g_play_url) return 0;

    /* Try binary plist first, then text */
    plist_t root = NULL;
    plist_from_memory(body, body_len, &root, NULL);

    if (root) {
        plist_t loc = plist_dict_get_item(root, "Content-Location");
        plist_t pos = plist_dict_get_item(root, "Start-Position");
        char *url = NULL;
        double start = 0.0;
        if (loc) plist_get_string_val(loc, &url);
        if (pos) plist_get_real_val(pos, &start);
        if (url) {
            g_play_url(g_cls, url, start);
            free(url);
        }
        plist_free(root);
        return 1;
    }

    /* Fallback: text format "Content-Location: URL\nStart-Position: 0.0\n" */
    const char *loc = strstr(body, "Content-Location: ");
    if (loc) {
        loc += 18;
        const char *end = strstr(loc, "\n");
        if (!end) end = body + body_len;
        int len = (int)(end - loc);
        char *url = (char *)malloc(len + 1);
        memcpy(url, loc, len);
        url[len] = '\0';
        /* trim \r */
        if (len > 0 && url[len - 1] == '\r') url[len - 1] = '\0';
        g_play_url(g_cls, url, 0.0);
        free(url);
        return 1;
    }
    return 0;
}

static int handle_playback_info(char **response_data, int *response_datalen) {
    double duration = 0, position = 0;
    float rate = 1.0f;
    if (g_play_info) g_play_info(g_cls, &duration, &position, &rate);

    char buf[2048];
    int n = snprintf(buf, sizeof(buf),
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
        "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
        "<plist version=\"1.0\">\n<dict>\n"
        "<key>duration</key><real>%f</real>\n"
        "<key>position</key><real>%f</real>\n"
        "<key>rate</key><real>%f</real>\n"
        "<key>readyToPlay</key><true/>\n"
        "<key>playbackBufferEmpty</key><false/>\n"
        "<key>playbackBufferFull</key><true/>\n"
        "<key>playbackLikelyToKeepUp</key><true/>\n"
        "<key>loadedTimeRanges</key><array><dict>"
        "<key>start</key><real>0.0</real>"
        "<key>duration</key><real>%f</real>"
        "</dict></array>\n"
        "<key>seekableTimeRanges</key><array><dict>"
        "<key>start</key><real>0.0</real>"
        "<key>duration</key><real>%f</real>"
        "</dict></array>\n"
        "</dict>\n</plist>\n",
        duration, position, (double)rate, duration, duration);

    *response_data = (char *)malloc(n + 1);
    memcpy(*response_data, buf, n + 1);
    *response_datalen = n;
    return 1;
}

int airplay_video_handle(const char *method, const char *url,
                         const char *body, int body_len,
                         char **response_data, int *response_datalen)
{
    if (!strcmp(method, "POST") && !strcmp(url, "/play")) {
        return handle_play(body, body_len);
    }
    if (!strcmp(method, "POST") && !strcmp(url, "/stop")) {
        if (g_play_stop) g_play_stop(g_cls);
        return 1;
    }
    if (!strcmp(method, "POST") && strstr(url, "/rate")) {
        const char *q = strstr(url, "value=");
        float rate = 1.0f;
        if (q) rate = (float)atof(q + 6);
        if (g_play_rate) g_play_rate(g_cls, rate);
        return 1;
    }
    if (!strcmp(method, "GET") && !strcmp(url, "/playback-info")) {
        return handle_playback_info(response_data, response_datalen);
    }
    if (!strcmp(method, "POST") && !strcmp(url, "/scrub")) {
        /* TODO: seek support */
        return 1;
    }
    return 0;
}
