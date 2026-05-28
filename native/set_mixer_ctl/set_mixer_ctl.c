/*
 * set_mixer_ctl — sets a single BOOL element slot via SNDRV_CTL_IOCTL_ELEM_WRITE.
 * Bypasses tinymix's broken mixer_ctl_get_array on MIUI sdm660 (CAF kernel 4.4).
 *
 * Usage: set_mixer_ctl <card> '<control name>' <slot_index> <0|1>
 * Example: set_mixer_ctl 0 'Incall_Music Audio Mixer MultiMedia1' 1 1
 *
 * IMPORTANT — CAF sdm660 struct layout note:
 * The snd_ctl_elem_value_t struct on this kernel is 1224 bytes, not 1160.
 * The value union uses 8-byte integers with additional padding. Verified by brute-force
 * ioctl size scan on device (kernel 4.4.205-Predator-Stormbreaker-10.0-CAF).
 */
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>

#define SNDRV_CTL_ELEM_ID_NAME_MAXLEN 44

typedef struct {
    unsigned int  numid;
    int           iface;
    unsigned int  device;
    unsigned int  subdevice;
    unsigned char name[SNDRV_CTL_ELEM_ID_NAME_MAXLEN];
    unsigned int  index;
} elem_id_t;  /* 64 bytes */

typedef struct {
    unsigned int  offset;
    unsigned int  space;
    unsigned int  used;
    unsigned int  count;
    uint64_t      pids;      /* pointer — must be 64-bit on aarch64 */
    unsigned char reserved[50];
} elem_list_t; /* 80 bytes */

/* CAF 4.4: elem_value is 1224 bytes — value union is 1096 bytes, not 1032.
   Determined empirically: brute-forced all sizes 512–2048 until ELEM_READ succeeded. */
typedef struct {
    elem_id_t     id;        /* 64 */
    unsigned int  type;      /* 4  */
    unsigned int  count;     /* 4  */
    union {
        int32_t   integer[128]; /* 1024 bytes — matches CAF layout */
        unsigned char bytes[512];
        unsigned char _pad[1096]; /* ensure correct union size */
    } value;
    unsigned char reserved[56]; /* pad to 1224 total */
} elem_val_t;  /* must be 1224 bytes */

#define ELEM_LIST  _IOWR('U', 0x10, elem_list_t)
#define ELEM_READ  _IOWR('U', 0x12, elem_val_t)
#define ELEM_WRITE _IOWR('U', 0x13, elem_val_t)

int main(int argc, char *argv[]) {
    if (argc != 5) {
        fprintf(stderr, "Usage: %s <card> '<control>' <slot> <value>\n", argv[0]);
        fprintf(stderr, "  Example: %s 0 'Incall_Music Audio Mixer MultiMedia1' 1 1\n", argv[0]);
        return 1;
    }

    int card   = atoi(argv[1]);
    const char *name  = argv[2];
    int slot   = atoi(argv[3]);
    int value  = atoi(argv[4]);

    /* Verify struct size at runtime */
    if (sizeof(elem_val_t) != 1224) {
        fprintf(stderr, "BUG: elem_val_t=%zu (expected 1224)\n", sizeof(elem_val_t));
        return 1;
    }

    char dev[32];
    snprintf(dev, sizeof(dev), "/dev/snd/controlC%d", card);

    int fd = open(dev, O_RDWR);
    if (fd < 0) { perror(dev); return 1; }

    /* Get element count */
    elem_list_t list;
    memset(&list, 0, sizeof(list));
    if (ioctl(fd, ELEM_LIST, &list) < 0) {
        perror("ELEM_LIST count"); close(fd); return 1;
    }

    unsigned int total = list.count;
    elem_id_t *ids = calloc(total, sizeof(elem_id_t));
    if (!ids) { fprintf(stderr, "calloc\n"); close(fd); return 1; }

    list.space = total;
    list.pids  = (uint64_t)(uintptr_t)ids;
    if (ioctl(fd, ELEM_LIST, &list) < 0) {
        perror("ELEM_LIST fetch"); free(ids); close(fd); return 1;
    }

    unsigned int numid = 0;
    for (unsigned int i = 0; i < list.used; i++) {
        if (strncmp((char*)ids[i].name, name, SNDRV_CTL_ELEM_ID_NAME_MAXLEN) == 0) {
            numid = ids[i].numid;
            break;
        }
    }
    free(ids);

    if (numid == 0) {
        fprintf(stderr, "Control '%s' not found on card %d\n", name, card);
        close(fd); return 1;
    }

    /* Read current value */
    elem_val_t val;
    memset(&val, 0, sizeof(val));
    val.id.numid = numid;
    if (ioctl(fd, ELEM_READ, &val) < 0) {
        perror("ELEM_READ"); close(fd); return 1;
    }

    /* Set target slot, preserve others */
    val.id.numid = numid;
    val.value.integer[slot] = value;

    if (ioctl(fd, ELEM_WRITE, &val) < 0) {
        perror("ELEM_WRITE"); close(fd); return 1;
    }

    printf("OK: '%s'[%d] = %d  (numid=%u count=%u)\n",
           name, slot, value, numid, val.count);
    close(fd);
    return 0;
}
