package com.meetnote.recorder.capture

/**
 * OpenCV native 库加载，幂等且失败缓存。
 *
 * 用 System.loadLibrary 而非 OpenCVLoader.initLocal()：AAR 内已确认含
 * jni/arm64-v8a/libopencv_java4.so（见计划 Task 1 的依赖核实记录），
 * 直接加载该库即可让 org.opencv 的 Java 绑定生效，且不依赖 OpenCV 内部 API 演进。
 *
 * 加载失败（缺 ABI、被裁剪的包等）不是致命错误：DocumentCorrector 会降级为整帧存储。
 */
object OpenCvLoader {

    private const val LIB = "opencv_java4"

    @Volatile
    private var available: Boolean? = null   // null = 尚未尝试

    @Volatile
    private var loadAction: () -> Boolean = { loadNatively() }

    fun ensure(): Boolean {
        available?.let { return it }
        synchronized(this) {
            available?.let { return it }
            val ok = try {
                loadAction()
            } catch (t: Throwable) {
                false
            }
            available = ok
            return ok
        }
    }

    fun isAvailable(): Boolean = ensure()

    /** 仅供测试注入加载结果 */
    fun setLoadActionForTest(action: () -> Boolean) {
        loadAction = action
        available = null
    }

    fun resetForTest() {
        available = null
        loadAction = { loadNatively() }
    }

    private fun loadNatively(): Boolean = try {
        System.loadLibrary(LIB)
        true
    } catch (t: Throwable) {
        false
    }
}
