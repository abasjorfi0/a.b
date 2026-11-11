MY_PATH := $(call my-dir)

# ---------------------------------------------------------------------------
# Expected third-party sources (git submodules, see README):
#   app/src/main/jni/byedpi               https://github.com/hufrea/byedpi            (MIT)
#   app/src/main/jni/hev-socks5-tunnel    https://github.com/heiher/hev-socks5-tunnel (MIT)
# ---------------------------------------------------------------------------
ifeq ($(wildcard $(MY_PATH)/byedpi/main.c),)
$(error byedpi sources are missing: run `git submodule update --init --recursive`)
endif
ifeq ($(wildcard $(MY_PATH)/hev-socks5-tunnel/Android.mk),)
$(error hev-socks5-tunnel sources are missing: run `git submodule update --init --recursive`)
endif

# tun2socks (builds module "hev-socks5-tunnel" + its static deps)
include $(MY_PATH)/hev-socks5-tunnel/Android.mk

# DPI-bypass SOCKS proxy (byedpi) + our JNI glue, in one shared library.
#  - main()          is renamed to byedpi_main() so it can be run in a forked child
#  - getaddrinfo()   is redirected to zp_resolver.c, which asks the app's DNS engine
#                    (plain DNS / DoH / auto) on 127.0.0.1 instead of the system resolver
LOCAL_PATH := $(MY_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := zapretcore
LOCAL_SRC_FILES := \
    zp_core.c \
    zp_resolver.c \
    byedpi/packets.c \
    byedpi/main.c \
    byedpi/conev.c \
    byedpi/proxy.c \
    byedpi/desync.c \
    byedpi/mpool.c \
    byedpi/extend.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/byedpi
LOCAL_CFLAGS := -D_DEFAULT_SOURCE -Dmain=byedpi_main \
    -Dgetaddrinfo=zp_getaddrinfo -Dfreeaddrinfo=zp_freeaddrinfo -w
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
