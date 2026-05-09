/*
 * pcm_write — read raw PCM16 mono from stdin, write to ALSA PCM device.
 *
 * Usage: pcm_write <card> <device> <rate> <channels>
 *   e.g. pcm_write 0 19 8000 1   (VoiceMMode2 TX at 8kHz mono)
 *        pcm_write 0 2  8000 1   (VoiceMMode1 TX at 8kHz mono)
 *
 * The voice HAL opens pcmC0D19p at 8kHz mono during a call — we must match
 * exactly. We open the same device in non-exclusive mode and write PCM frames.
 *
 * If the HAL has the device open exclusively, we get EBUSY. In that case the
 * Incall_Music DSP mixer is the only viable path (handled by set_mixer_ctl).
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <stdint.h>
#include <signal.h>

/* ---- minimal ALSA PCM kernel ABI (sound/asound.h) --------------------- */

#define SNDRV_PCM_FORMAT_S16_LE  2

typedef struct {
    uint32_t access;
    int      format;
    uint32_t subformat;
    uint32_t rate;
    uint32_t channels;
    uint32_t period_time;
    uint32_t period_size;
    uint64_t period_bytes;
    uint32_t periods;
    uint64_t buffer_time;
    uint64_t buffer_size;
    uint64_t buffer_bytes;
    uint32_t tick_time;
} snd_pcm_hw_params_t;  /* simplified — real struct is much larger */

/* Use the full hw_params structure size that the kernel expects */
#define HW_PARAMS_SIZE 608

typedef struct {
    uint32_t avail_min;
    uint32_t start_threshold;
    uint32_t stop_threshold;
    uint32_t silence_threshold;
    uint32_t silence_size;
    uint32_t boundary;
    uint32_t proto;
    uint32_t tstamp_type;
    uint32_t period_step;
    uint32_t sleep_min;
    uint32_t xfer_align;
    uint32_t reserved[4];
} snd_pcm_sw_params_t;

#define SNDRV_PCM_IOCTL_HW_PARAMS  _IOWR('A', 0x11, unsigned char[HW_PARAMS_SIZE])
#define SNDRV_PCM_IOCTL_PREPARE    _IO('A', 0x40)
#define SNDRV_PCM_IOCTL_WRITEI_FRAMES _IOW('A', 0x50, void*)

typedef struct {
    void   *buf;
    ssize_t frames;
} snd_xferi_t;

int main(int argc, char *argv[])
{
    if (argc < 5) {
        fprintf(stderr, "Usage: %s <card> <device> <rate> <channels>\n"
                        "  e.g. %s 0 19 8000 1\n", argv[0], argv[0]);
        return 1;
    }
    int card     = atoi(argv[1]);
    int device   = atoi(argv[2]);
    unsigned int rate     = (unsigned int)atoi(argv[3]);
    unsigned int channels = (unsigned int)atoi(argv[4]);

    char path[64];
    snprintf(path, sizeof(path), "/dev/snd/pcmC%dD%dp", card, device);

    int fd = open(path, O_WRONLY);
    if (fd < 0) {
        fprintf(stderr, "open %s: %s\n", path, strerror(errno));
        return 1;
    }
    fprintf(stderr, "opened %s  rate=%u channels=%u\n", path, rate, channels);

    /* Set hw params via tinyalsa-compatible ioctl.
     * Rather than reconstructing the full interval-based hw_params negotiation,
     * just call tinyalsa's pcm_open if available, or fall back to direct write. */

    /* For simplicity: use tinyalsa's C API via dlopen if present,
     * otherwise just write raw frames and let the kernel use defaults. */

    /* Actually the simplest approach: just write raw PCM to the fd directly.
     * The voice HAL already configured the device; we're a second writer.
     * This will fail with EBUSY if HAL holds it exclusively — expected on MIUI. */

    const int CHUNK_FRAMES = 160; /* 20ms at 8kHz */
    const int BYTES_PER_FRAME = channels * 2; /* PCM16 */
    const int CHUNK_BYTES = CHUNK_FRAMES * BYTES_PER_FRAME;

    uint8_t buf[CHUNK_BYTES];
    ssize_t n;
    long chunks = 0;

    /* stdin is raw PCM16 little-endian at <rate> Hz <channels> ch */
    while ((n = fread(buf, 1, CHUNK_BYTES, stdin)) > 0) {
        ssize_t written = write(fd, buf, n);
        if (written < 0) {
            fprintf(stderr, "write error after %ld chunks: %s\n", chunks, strerror(errno));
            close(fd);
            return 1;
        }
        chunks++;
        if (chunks % 500 == 0) fprintf(stderr, "pcm_write: %ld chunks written\n", chunks);
    }

    fprintf(stderr, "pcm_write: EOF after %ld chunks\n", chunks);
    close(fd);
    return 0;
}
