// getaddrinfo() replacement used only by byedpi (see -Dgetaddrinfo=zp_getaddrinfo in Android.mk).
// Domain names arrive from hev-socks5-tunnel (mapped DNS). They are resolved by asking the
// app's own DNS engine (plain DNS / DoH / auto) listening on 127.0.0.1:ZP_DNS_PORT.
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>

#define ZP_DNS_PORT 5300

static int build_query(const char *host, uint8_t *buf, size_t cap, uint16_t id)
{
    size_t o = 0;
    if (cap < 512) return -1;
    buf[o++] = id >> 8; buf[o++] = id & 0xff;
    buf[o++] = 0x01; buf[o++] = 0x00;           // RD
    buf[o++] = 0; buf[o++] = 1;                  // QDCOUNT
    buf[o++] = 0; buf[o++] = 0; buf[o++] = 0; buf[o++] = 0; buf[o++] = 0; buf[o++] = 0;
    const char *p = host;
    while (*p) {
        const char *dot = strchr(p, '.');
        size_t len = dot ? (size_t)(dot - p) : strlen(p);
        if (len == 0 || len > 63 || o + len + 6 > cap) return -1;
        buf[o++] = (uint8_t)len;
        memcpy(buf + o, p, len); o += len;
        p += len;
        if (*p == '.') p++;
    }
    buf[o++] = 0;
    buf[o++] = 0; buf[o++] = 1;                  // QTYPE A
    buf[o++] = 0; buf[o++] = 1;                  // QCLASS IN
    return (int)o;
}

static int skip_name(const uint8_t *b, int len, int o)
{
    while (o < len) {
        uint8_t c = b[o];
        if (c == 0) return o + 1;
        if ((c & 0xC0) == 0xC0) return o + 2;
        o += 1 + c;
    }
    return -1;
}

static int parse_first_a(const uint8_t *b, int len, uint32_t *out)
{
    if (len < 12) return -1;
    if ((b[3] & 0x0F) != 0) return -1;           // RCODE
    int qd = (b[4] << 8) | b[5], an = (b[6] << 8) | b[7];
    int o = 12;
    for (int i = 0; i < qd; i++) {
        o = skip_name(b, len, o);
        if (o < 0) return -1;
        o += 4;
    }
    for (int i = 0; i < an; i++) {
        o = skip_name(b, len, o);
        if (o < 0 || o + 10 > len) return -1;
        int type = (b[o] << 8) | b[o + 1];
        int rdlen = (b[o + 8] << 8) | b[o + 9];
        o += 10;
        if (o + rdlen > len) return -1;
        if (type == 1 && rdlen == 4) {
            memcpy(out, b + o, 4);
            return 0;
        }
        o += rdlen;
    }
    return -1;
}

struct zp_ai {
    struct addrinfo ai;
    struct sockaddr_in sin;
};

int zp_getaddrinfo(const char *node, const char *service,
                   const struct addrinfo *hints, struct addrinfo **res)
{
    struct in_addr ip4;
    uint32_t addr = 0;
    int family = hints ? hints->ai_family : AF_UNSPEC;

    if (!node || !res) return EAI_NONAME;
    if (family == AF_INET6) {
        struct in6_addr t;
        if (inet_pton(AF_INET6, node, &t) != 1) return EAI_NONAME;
        return EAI_FAMILY;                        // numeric v6 is handled by byedpi elsewhere
    }

    if (inet_pton(AF_INET, node, &ip4) == 1) {
        addr = ip4.s_addr;
    } else {
        if (hints && (hints->ai_flags & AI_NUMERICHOST)) return EAI_NONAME;
        uint8_t q[512], r[1500];
        uint16_t id = (uint16_t)(rand() & 0xffff);
        int ql = build_query(node, q, sizeof q, id);
        if (ql < 0) return EAI_NONAME;
        int fd = socket(AF_INET, SOCK_DGRAM, 0);
        if (fd < 0) return EAI_SYSTEM;
        struct timeval tv = { .tv_sec = 6, .tv_usec = 0 };
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
        struct sockaddr_in dst = {0};
        dst.sin_family = AF_INET;
        dst.sin_port = htons(ZP_DNS_PORT);
        inet_pton(AF_INET, "127.0.0.1", &dst.sin_addr);
        int ok = sendto(fd, q, (size_t)ql, 0, (struct sockaddr *)&dst, sizeof dst) == ql;
        int n = ok ? (int)recv(fd, r, sizeof r, 0) : -1;
        close(fd);
        if (n < 12 || r[0] != q[0] || r[1] != q[1]) return EAI_AGAIN;
        if (parse_first_a(r, n, &addr) != 0) return EAI_NONAME;
    }

    struct zp_ai *z = calloc(1, sizeof *z);
    if (!z) return EAI_MEMORY;
    z->sin.sin_family = AF_INET;
    z->sin.sin_addr.s_addr = addr;
    if (service) z->sin.sin_port = htons((uint16_t)atoi(service));
    z->ai.ai_family = AF_INET;
    z->ai.ai_socktype = hints ? hints->ai_socktype : SOCK_STREAM;
    z->ai.ai_addrlen = sizeof z->sin;
    z->ai.ai_addr = (struct sockaddr *)&z->sin;
    *res = &z->ai;
    return 0;
}

void zp_freeaddrinfo(struct addrinfo *ai)
{
    free(ai);                                     // ai is the first member of struct zp_ai
}
