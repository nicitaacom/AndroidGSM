/*
 * pcm_play — write raw PCM16 mono from stdin to an ALSA PCM playback device.
 * Used to inject browser mic audio directly into MultiMedia1 (pcmC0D0p) so the
 * Incall_Music DSP mixer can route it into the GSM voice TX uplink.
 *
 * Usage: pcm_play [-D card] [-d device] [-r rate] [-c channels]
 *   e.g. pcm_play -D 0 -d 0 -r 16000 -c 1   (MultiMedia1, 16kHz mono)
 *
 * Reads raw PCM16-LE frames from stdin until EOF or error.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <stdint.h>
#include <sound/asound.h>

static void param_set_mask(struct snd_pcm_hw_params *p, int n, unsigned int bit)
{
    int idx = n - SNDRV_PCM_HW_PARAM_FIRST_MASK;
    memset(&p->masks[idx], 0, sizeof(p->masks[idx]));
    p->masks[idx].bits[bit >> 5] = 1u << (bit & 31);
}

static void param_set_int(struct snd_pcm_hw_params *p, int n, unsigned int val)
{
    int idx = n - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL;
    p->intervals[idx].min = p->intervals[idx].max = val;
    p->intervals[idx].integer = 1;
}

static void param_init(struct snd_pcm_hw_params *p)
{
    int i;
    memset(p, 0, sizeof(*p));
    for (i = SNDRV_PCM_HW_PARAM_FIRST_MASK; i <= SNDRV_PCM_HW_PARAM_LAST_MASK; i++) {
        int idx = i - SNDRV_PCM_HW_PARAM_FIRST_MASK;
        memset(p->masks[idx].bits, 0xff, sizeof(p->masks[idx].bits));
    }
    for (i = SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; i <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL; i++) {
        int idx = i - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL;
        p->intervals[idx].min = 0;
        p->intervals[idx].max = ~0U;
    }
    p->rmask = ~0U;
    p->cmask = 0;
}

int main(int argc, char *argv[])
{
    int card = 0, device = 0;
    unsigned int rate = 16000, channels = 1;
    unsigned int period_size = 320; /* 20ms at 16kHz */
    unsigned int period_count = 4;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-D") && i+1 < argc) card         = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-d") && i+1 < argc) device  = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-r") && i+1 < argc) rate     = (unsigned int)atoi(argv[++i]);
        else if (!strcmp(argv[i], "-c") && i+1 < argc) channels = (unsigned int)atoi(argv[++i]);
        else if (!strcmp(argv[i], "-p") && i+1 < argc) period_size  = (unsigned int)atoi(argv[++i]);
        else if (!strcmp(argv[i], "-n") && i+1 < argc) period_count = (unsigned int)atoi(argv[++i]);
    }

    char path[64];
    snprintf(path, sizeof(path), "/dev/snd/pcmC%dD%dp", card, device);

    int fd = open(path, O_WRONLY);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s\n", path, strerror(errno));
        return 1;
    }
    fprintf(stderr, "pcm_play: opened %s rate=%u ch=%u period=%u count=%u\n",
            path, rate, channels, period_size, period_count);

    struct snd_pcm_hw_params params;
    param_init(&params);
    param_set_mask(&params, SNDRV_PCM_HW_PARAM_ACCESS,    SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    param_set_mask(&params, SNDRV_PCM_HW_PARAM_FORMAT,    SNDRV_PCM_FORMAT_S16_LE);
    param_set_int(&params,  SNDRV_PCM_HW_PARAM_CHANNELS,  channels);
    param_set_int(&params,  SNDRV_PCM_HW_PARAM_RATE,      rate);
    param_set_int(&params,  SNDRV_PCM_HW_PARAM_PERIOD_SIZE,  period_size);
    param_set_int(&params,  SNDRV_PCM_HW_PARAM_PERIODS,      period_count);

    if (ioctl(fd, SNDRV_PCM_IOCTL_HW_PARAMS, &params) < 0) {
        fprintf(stderr, "HW_PARAMS failed: %s\n", strerror(errno));
        close(fd); return 1;
    }

    struct snd_pcm_sw_params sw;
    memset(&sw, 0, sizeof(sw));
    sw.start_threshold  = period_size;
    sw.stop_threshold   = period_size * period_count;
    sw.avail_min        = period_size;
    if (ioctl(fd, SNDRV_PCM_IOCTL_SW_PARAMS, &sw) < 0) {
        fprintf(stderr, "SW_PARAMS failed: %s\n", strerror(errno));
        /* non-fatal */
    }

    if (ioctl(fd, SNDRV_PCM_IOCTL_PREPARE) < 0) {
        fprintf(stderr, "PREPARE failed: %s\n", strerror(errno));
        close(fd); return 1;
    }

    const int frame_bytes = (int)channels * 2; /* PCM16 */
    const int buf_bytes   = (int)period_size * frame_bytes;
    uint8_t *buf = malloc((size_t)buf_bytes);
    if (!buf) { perror("malloc"); close(fd); return 1; }

    long periods = 0;
    size_t n;
    while ((n = fread(buf, 1, (size_t)buf_bytes, stdin)) > 0) {
        if ((int)n < buf_bytes) memset(buf + n, 0, (size_t)(buf_bytes - (int)n));

        struct snd_xferi xfer;
        memset(&xfer, 0, sizeof(xfer));
        xfer.buf    = buf;
        xfer.frames = period_size;

        if (ioctl(fd, SNDRV_PCM_IOCTL_WRITEI_FRAMES, &xfer) < 0) {
            if (errno == EPIPE) {
                ioctl(fd, SNDRV_PCM_IOCTL_PREPARE);
                continue;
            }
            fprintf(stderr, "WRITEI after %ld periods: %s\n", periods, strerror(errno));
            break;
        }
        periods++;
        if (periods % 500 == 0)
            fprintf(stderr, "pcm_play: %ld periods written\n", periods);
    }

    free(buf);
    ioctl(fd, SNDRV_PCM_IOCTL_DROP);
    close(fd);
    fprintf(stderr, "pcm_play: done (%ld periods)\n", periods);
    return 0;
}
