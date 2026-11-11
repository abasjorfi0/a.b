package hev.htproxy

// Must keep this exact package/class name: hev-socks5-tunnel registers its JNI natives against it.
class TProxyService {
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray

    companion object {
        init { System.loadLibrary("hev-socks5-tunnel") }
    }
}
