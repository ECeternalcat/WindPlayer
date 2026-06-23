package dev.windplayer

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal const val GWL_STYLE = -16
internal const val WS_CAPTION = 0x00C00000
internal const val WS_THICKFRAME = 0x00040000
internal const val WS_SYSMENU = 0x00080000
internal const val WS_MAXIMIZEBOX = 0x00010000
internal const val WS_MINIMIZEBOX = 0x00020000
internal const val SWP_NOSIZE = 0x0001
internal const val SWP_NOMOVE = 0x0002
internal const val SWP_FRAMECHANGED = 0x0020

internal interface Win32Api : Library {
    companion object {
        val INSTANCE: Win32Api by lazy { Native.load("user32", Win32Api::class.java) }
        val HWND_TOPMOST: Pointer = Pointer(-1L)
        val HWND_NOTOPMOST: Pointer = Pointer(-2L)
    }

    fun GetWindowLongW(hWnd: Pointer, nIndex: Int): Int
    fun SetWindowLongW(hWnd: Pointer, nIndex: Int, dwNewLong: Int): Int
    fun SetWindowPos(
        hWnd: Pointer,
        hWndInsertAfter: Pointer?,
        x: Int,
        y: Int,
        cx: Int,
        cy: Int,
        flags: Int
    ): Boolean
}
