/*
 * set_mixer_ctl — set individual BOOL/INTEGER elements of an ALSA mixer control
 * by element index, bypassing mixer_ctl_get_array (broken on some MIUI tinyalsa).
 *
 * Usage: set_mixer_ctl <card> <control_name> <elem_idx> <value>
 *   e.g. set_mixer_ctl 0 "Incall_Music Audio Mixer MultiMedia1" 1 1
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sound/asound.h>

static int find_numid(int fd, const char *name,
                      unsigned int *out_numid, unsigned int *out_count)
{
    struct snd_ctl_elem_list list;

    memset(&list, 0, sizeof(list));
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) {
        perror("ELEM_LIST count"); return -1;
    }
    unsigned int total = list.count;
    if (!total) { fprintf(stderr, "no controls\n"); return -1; }

    struct snd_ctl_elem_id *ids = calloc(total, sizeof(*ids));
    if (!ids) { perror("calloc"); return -1; }

    memset(&list, 0, sizeof(list));
    list.space = total;
    list.pids  = ids;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) {
        perror("ELEM_LIST fill"); free(ids); return -1;
    }

    for (unsigned int i = 0; i < list.used; i++) {
        if (strcmp((char *)ids[i].name, name) == 0) {
            *out_numid = ids[i].numid;
            struct snd_ctl_elem_info info;
            memset(&info, 0, sizeof(info));
            info.id = ids[i];
            *out_count = (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) == 0)
                         ? info.count : 0;
            free(ids); return 0;
        }
    }
    fprintf(stderr, "control '%s' not found\n", name);
    free(ids); return -1;
}

int main(int argc, char *argv[])
{
    if (argc < 5) {
        fprintf(stderr, "Usage: %s <card> <ctrl_name> <elem_idx> <value>\n", argv[0]);
        return 1;
    }
    int card = atoi(argv[1]);
    const char *name = argv[2];
    unsigned int idx = (unsigned int)atoi(argv[3]);
    long value = atol(argv[4]);

    char path[64];
    snprintf(path, sizeof(path), "/dev/snd/controlC%d", card);

    int fd = open(path, O_RDWR);
    if (fd < 0) { fprintf(stderr, "open %s: %s\n", path, strerror(errno)); return 1; }

    unsigned int numid = 0, count = 0;
    if (find_numid(fd, name, &numid, &count) < 0) { close(fd); return 1; }

    if (!count) count = idx + 1;
    if (idx >= count) {
        fprintf(stderr, "elem_idx %u >= count %u\n", idx, count);
        close(fd); return 1;
    }

    struct snd_ctl_elem_value val;
    memset(&val, 0, sizeof(val));
    val.id.numid = numid;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &val) < 0)
        fprintf(stderr, "warn: READ failed (%s), writing blind\n", strerror(errno));

    val.id.numid = numid;
    val.value.integer.value[idx] = value;

    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_WRITE, &val) < 0) {
        fprintf(stderr, "WRITE '%s'[%u]=%ld: %s\n", name, idx, value, strerror(errno));
        close(fd); return 1;
    }

    printf("OK: '%s'[%u] = %ld  (numid=%u count=%u)\n", name, idx, value, numid, count);
    close(fd); return 0;
}
