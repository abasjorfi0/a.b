package app.zapret.mobile

object Native {
    init { System.loadLibrary("zapretcore") }

    /** Blocks until byedpi exits. args[0] is the program name. */
    @JvmStatic external fun runProxy(args: Array<String>): Int
}
