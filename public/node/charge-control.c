#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define CAPACITY_PATH "/sys/class/power_supply/battery/capacity"
#define ENABLED_PATH "/sys/class/power_supply/battery/charging_enabled"
#define LOW_PERCENT 50
#define HIGH_PERCENT 70
#define POLL_SECONDS 60

static volatile sig_atomic_t stopping;

static void log_message(const char *format, ...) {
    va_list values;
    va_start(values, format);
    fputs("v480-charge: ", stderr);
    vfprintf(stderr, format, values);
    fputc('\n', stderr);
    va_end(values);
}

static int read_int(const char *path, int *value) {
    char buffer[32];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }

    ssize_t count = read(fd, buffer, sizeof(buffer) - 1);
    int saved_errno = errno;
    close(fd);
    errno = saved_errno;
    if (count <= 0) {
        return -1;
    }

    buffer[count] = '\0';
    char *end = NULL;
    long parsed = strtol(buffer, &end, 10);
    if (end == buffer) {
        errno = EINVAL;
        return -1;
    }

    *value = (int)parsed;
    return 0;
}

static int write_int(const char *path, int value) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }

    char buffer[8];
    int length = snprintf(buffer, sizeof(buffer), "%d\n", value);
    ssize_t count = write(fd, buffer, (size_t)length);
    int saved_errno = errno;
    close(fd);
    errno = saved_errno;
    return count == length ? 0 : -1;
}

static void control_once(void) {
    int capacity;
    int enabled;

    if (read_int(CAPACITY_PATH, &capacity) != 0 || capacity < 0 || capacity > 100) {
        log_message("no se pudo leer una capacidad valida: %s", strerror(errno));
        return;
    }
    if (read_int(ENABLED_PATH, &enabled) != 0 || (enabled != 0 && enabled != 1)) {
        log_message("no se pudo leer el estado de carga: %s", strerror(errno));
        return;
    }

    int desired = enabled;
    if (capacity >= HIGH_PERCENT) {
        desired = 0;
    } else if (capacity <= LOW_PERCENT) {
        desired = 1;
    }

    if (desired == enabled) {
        return;
    }
    if (write_int(ENABLED_PATH, desired) != 0) {
        log_message("no se pudo cambiar el estado de carga: %s", strerror(errno));
        return;
    }

    log_message("carga %s con bateria al %d%% (franja %d-%d%%)",
                desired ? "habilitada" : "detenida", capacity,
                LOW_PERCENT, HIGH_PERCENT);
}

static void handle_signal(int signal_number) {
    (void)signal_number;
    stopping = 1;
}

int main(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = handle_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGHUP, &action, NULL);

    log_message("control iniciado: carga %d-%d%%, revision cada %ds",
                LOW_PERCENT, HIGH_PERCENT, POLL_SECONDS);

    while (!stopping) {
        control_once();
        for (int second = 0; second < POLL_SECONDS && !stopping; ++second) {
            sleep(1);
        }
    }

    if (write_int(ENABLED_PATH, 1) != 0) {
        log_message("no se pudo habilitar la carga al detener el control: %s",
                    strerror(errno));
    } else {
        log_message("control detenido; carga habilitada por seguridad");
    }
    return 0;
}
