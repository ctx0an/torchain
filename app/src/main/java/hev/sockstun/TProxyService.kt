package hev.sockstun

import com.torchain.android.util.Logger

class TProxyService {
    companion object {
        private var isLibLoaded = false

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
                isLibLoaded = true
                Logger.i("TProxyService", "libhev-socks5-tunnel loaded successfully")
            } catch (e: Throwable) {
                isLibLoaded = false
                Logger.e("TProxyService", "Failed to load libhev-socks5-tunnel: ${e.message}", e)
            }
        }

        fun isAvailable(): Boolean {
            return isLibLoaded
        }

        @JvmStatic
        external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        external fun TProxyStopService()

        @JvmStatic
        external fun TProxyGetStats(): LongArray
    }
}
