// Socket helpers for ffi_http.kt: TCP on 127.0.0.1 (an ephemeral port), byte I/O on Kotlin ByteArrays.
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static void loopback(struct sockaddr_in *a, int port) {
    memset(a, 0, sizeof *a);
    a->sin_family = AF_INET; a->sin_port = htons((uint16_t)port); a->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
}
int32_t kx_tcp_listen(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0); if (fd < 0) return -1;
    int one = 1; setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in a; loopback(&a, 0);
    if (bind(fd, (struct sockaddr *)&a, sizeof a) != 0 || listen(fd, 8) != 0) { close(fd); return -1; }
    return fd;
}
int32_t kx_tcp_port(int32_t fd) {
    struct sockaddr_in a; socklen_t n = sizeof a;
    if (getsockname(fd, (struct sockaddr *)&a, &n) != 0) return -1;
    return ntohs(a.sin_port);
}
int32_t kx_tcp_connect(int32_t port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0); if (fd < 0) return -1;
    struct sockaddr_in a; loopback(&a, port);
    if (connect(fd, (struct sockaddr *)&a, sizeof a) != 0) { close(fd); return -1; }
    return fd;
}
int32_t kx_tcp_accept(int32_t fd) { return accept(fd, NULL, NULL); }
int64_t kx_send(int32_t fd, const void *p, int64_t n) { return (int64_t)write(fd, p, (size_t)n); }
int64_t kx_recv(int32_t fd, void *p, int64_t n) { return (int64_t)read(fd, p, (size_t)n); }
void kx_shutdown_write(int32_t fd) { shutdown(fd, SHUT_WR); }
void kx_close(int32_t fd) { close(fd); }
