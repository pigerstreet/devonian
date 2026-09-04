package com.github.synnerz.devonian.utils

import org.lwjgl.system.APIUtil
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.windows.User32

/**
 * reads top level window captions straight out of user32
 *
 * sandboxed launchers (pandora) run the game inside a windows appcontainer whose token cannot
 * enumerate processes outside the container - tasklist fails there with "the user name or password
 * is incorrect" and every managed process listing comes back empty. the window station is shared
 * though, so walking windows returns exactly the same thing sandboxed or not.
 *
 * touching anything in here resolves user32, so only use it when [isWindows]
 */
object WindowUtils {
    val isWindows = System.getProperty("os.name").startsWith("Windows")

    /** longest caption we will read, in utf-16 chars */
    private const val MAX_TITLE = 512
    private const val TITLE_BYTES = MAX_TITLE * 2L
    /** the caption buffer with a 4 byte dword out param for the pid glued on the end */
    private const val SCRATCH_BYTES = TITLE_BYTES + 4L

    private val findWindowExW by lazy { APIUtil.apiGetFunctionAddress(User32.getLibrary(), "FindWindowExW") }
    private val getWindowTextW by lazy { APIUtil.apiGetFunctionAddress(User32.getLibrary(), "GetWindowTextW") }
    private val getWindowThreadProcessId by lazy { APIUtil.apiGetFunctionAddress(User32.getLibrary(), "GetWindowThreadProcessId") }

    private val ownPid = ProcessHandle.current().pid().toInt()

    /**
     * calls [cb] once per top level window that has a caption and belongs to another process,
     * returns how many windows were walked in total - 0 means the desktop could not be read
     *
     * FindWindowEx is used instead of EnumWindows so no native callback has to be allocated and
     * pinned, it is one call per window either way. it also skips message only windows, which is
     * what kept feeding us captions like AngleHiddenWindow and OleMainThreadWndName
     */
    fun walkTitledWindows(cb: (hwnd: Long, pid: Int, title: String) -> Unit): Int {
        val find = findWindowExW
        val text = getWindowTextW
        val procId = getWindowThreadProcessId

        val scratch = MemoryUtil.nmemAllocChecked(SCRATCH_BYTES)
        val pidOut = scratch + TITLE_BYTES
        var walked = 0

        try {
            var hwnd = 0L
            while (true) {
                hwnd = JNI.invokePPPPP(0L, hwnd, 0L, 0L, find)
                if (hwnd == 0L) break
                walked++

                // GetWindowTextW only reads the cached caption when the window belongs to another
                // process. on one of our own it posts WM_GETTEXT and blocks until the render thread
                // pumps messages, so never ask about ourselves from the poll thread
                JNI.invokePPI(hwnd, pidOut, procId)
                val pid = MemoryUtil.memGetInt(pidOut)
                if (pid == ownPid) continue

                val len = JNI.invokePPI(hwnd, scratch, MAX_TITLE, text)
                if (len <= 0) continue

                cb(hwnd, pid, MemoryUtil.memUTF16(scratch, len))
            }
        } finally {
            MemoryUtil.nmemFree(scratch)
        }

        return walked
    }

    /** null when the handle is gone or the window has no caption */
    fun titleOf(hwnd: Long): String? {
        val text = getWindowTextW
        val scratch = MemoryUtil.nmemAllocChecked(TITLE_BYTES)
        try {
            val len = JNI.invokePPI(hwnd, scratch, MAX_TITLE, text)
            return if (len <= 0) null else MemoryUtil.memUTF16(scratch, len)
        } finally {
            MemoryUtil.nmemFree(scratch)
        }
    }

    /** 0 when the handle is gone */
    fun pidOf(hwnd: Long): Int {
        val procId = getWindowThreadProcessId
        val pidOut = MemoryUtil.nmemAllocChecked(4L)
        try {
            MemoryUtil.memPutInt(pidOut, 0)
            JNI.invokePPI(hwnd, pidOut, procId)
            return MemoryUtil.memGetInt(pidOut)
        } finally {
            MemoryUtil.nmemFree(pidOut)
        }
    }
}
