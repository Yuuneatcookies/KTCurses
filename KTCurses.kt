package ktcurses

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@JvmInline
value class Color private constructor(internal val raw: Int) {
    val isDefault get() = raw < 0
    val r get() = (raw shr 16) and 0xFF
    val g get() = (raw shr 8) and 0xFF
    val b get() = raw and 0xFF

    fun lerp(to: Color, t: Float): Color {
        if (isDefault || to.isDefault) return to
        val k = t.coerceIn(0f, 1f)
        return rgb(
            (r + (to.r - r) * k).roundToInt(),
            (g + (to.g - g) * k).roundToInt(),
            (b + (to.b - b) * k).roundToInt(),
        )
    }

    companion object {
        val Default = Color(-1)
        val Black = hex(0x000000)
        val White = hex(0xFFFFFF)
        val Gray = hex(0x808080)
        val Red = hex(0xE5484D)
        val Green = hex(0x46A758)
        val Blue = hex(0x3E63DD)
        val Yellow = hex(0xFFCC00)
        val Cyan = hex(0x00A2C7)
        val Magenta = hex(0xD6409F)

        fun rgb(r: Int, g: Int, b: Int) =
            Color((r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255))

        fun hex(rgb: Int) = Color(rgb and 0xFFFFFF)

                fun hsv(h: Float, s: Float = 1f, v: Float = 1f): Color {
            val sector = (((h % 360f) + 360f) % 360f) / 60f
            val c = v * s
            val x = c * (1f - abs(sector % 2f - 1f))
            val (r, g, b) = when (sector.toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = v - c
            return rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
        }
    }
}

@JvmInline
value class Attrs(val bits: Int) {
    infix fun or(other: Attrs) = Attrs(bits or other.bits)
    operator fun contains(other: Attrs) = (bits and other.bits) == other.bits

    companion object {
        val None = Attrs(0)
        val Bold = Attrs(1 shl 0)
        val Dim = Attrs(1 shl 1)
        val Italic = Attrs(1 shl 2)
        val Underline = Attrs(1 shl 3)
        val Blink = Attrs(1 shl 4)
        val Reverse = Attrs(1 shl 5)
        val Strike = Attrs(1 shl 6)
    }
}

data class Style(
    val fg: Color = Color.Default,
    val bg: Color = Color.Default,
    val attrs: Attrs = Attrs.None,
)

enum class ColorMode {
    None, Ansi256, TrueColor;

    companion object {
        fun detect(env: Map<String, String> = System.getenv()): ColorMode {
            if ("NO_COLOR" in env) return None
            val ct = env["COLORTERM"].orEmpty().lowercase()
            if ("truecolor" in ct || "24bit" in ct) return TrueColor
            return if (env["TERM"] == "dumb") None else Ansi256
        }
    }
}

class Border(val tl: Char, val tr: Char, val bl: Char, val br: Char, val h: Char, val v: Char) {
    companion object {
        val Single = Border('┌', '┐', '└', '┘', '─', '│')
        val Rounded = Border('╭', '╮', '╰', '╯', '─', '│')
        val Double = Border('╔', '╗', '╚', '╝', '═', '║')
        val Heavy = Border('┏', '┓', '┗', '┛', '━', '┃')
        val Ascii = Border('+', '+', '+', '+', '-', '|')
    }
}

data class Key(val name: String, val cp: Int = 0) {
    val char: Char? get() = if (cp in 1..0xFFFF) cp.toChar() else null

    override fun toString() = if (cp != 0) String(Character.toChars(cp)) else name

    companion object {
        val Enter = Key("Enter")
        val Tab = Key("Tab")
        val Backspace = Key("Backspace")
        val Escape = Key("Esc")
        val Up = Key("Up")
        val Down = Key("Down")
        val Left = Key("Left")
        val Right = Key("Right")
        val Home = Key("Home")
        val End = Key("End")
        val PageUp = Key("PageUp")
        val PageDown = Key("PageDown")
        val Insert = Key("Insert")
        val Delete = Key("Delete")
        val F1 = Key("F1")
        val F2 = Key("F2")
        val F3 = Key("F3")
        val F4 = Key("F4")
        val F5 = Key("F5")
        val F6 = Key("F6")
        val F7 = Key("F7")
        val F8 = Key("F8")
        val F9 = Key("F9")
        val F10 = Key("F10")
        val F11 = Key("F11")
        val F12 = Key("F12")

        fun rune(cp: Int) = Key("Char", cp)
    }
}

enum class MouseButton { Left, Middle, Right, WheelUp, WheelDown, None }
enum class MouseAction { Press, Release, Drag, Scroll }

sealed interface Event

data class KeyEvent(
    val key: Key,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) : Event {
    val char: Char? get() = key.char

    override fun toString() = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(key)
    }
}

data class MouseEvent(
    val x: Int,
    val y: Int,
    val button: MouseButton,
    val action: MouseAction,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) : Event

data class ResizeEvent(val width: Int, val height: Int) : Event

internal const val CONT = -1
internal const val INVALID = -2

internal class Grid(val w: Int, val h: Int) {
    val cp = IntArray(w * h)
    val fg = IntArray(w * h)
    val bg = IntArray(w * h)
    val at = IntArray(w * h)
    var curX = -1
    var curY = -1

    init { clear() }

    fun clear() {
        cp.fill(' '.code); fg.fill(-1); bg.fill(-1); at.fill(0)
        curX = -1; curY = -1
    }

    fun invalidate() = cp.fill(INVALID)

    fun copyFrom(o: Grid) {
        System.arraycopy(o.cp, 0, cp, 0, cp.size)
        System.arraycopy(o.fg, 0, fg, 0, fg.size)
        System.arraycopy(o.bg, 0, bg, 0, bg.size)
        System.arraycopy(o.at, 0, at, 0, at.size)
    }

    fun same(o: Grid, i: Int) = cp[i] == o.cp[i] && fg[i] == o.fg[i] && bg[i] == o.bg[i] && at[i] == o.at[i]
}

internal fun cpWidth(cp: Int): Int = when {
    cp < 32 || cp == 127 -> 0
    cp in 0x0300..0x036F || cp in 0x200B..0x200F || cp in 0xFE00..0xFE0F -> 0
    cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 ||
        cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE6F || cp in 0xFF00..0xFF60 ||
        cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1F64F || cp in 0x1F680..0x1F6FF ||
        cp in 0x1F900..0x1F9FF || cp in 0x20000..0x3FFFD -> 2
    else -> 1
}

fun textWidth(s: CharSequence): Int {
    var i = 0
    var w = 0
    while (i < s.length) {
        val cp = Character.codePointAt(s, i)
        i += Character.charCount(cp)
        w += cpWidth(cp)
    }
    return w
}

private const val PARTIAL_BLOCKS = "▏▎▍▌▋▊▉"

class Canvas internal constructor(
    private val g: Grid,
    private val ox: Int,
    private val oy: Int,
    val width: Int,
    val height: Int,
) {
    private fun plot(x: Int, y: Int, cp: Int, w: Int, s: Style, opaque: Boolean = false) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        if (w == 2 && x + 1 >= width) return plot(x, y, ' '.code, 1, s, opaque)

        val col = ox + x
        val i = (oy + y) * g.w + col
        if (g.cp[i] == CONT && col > 0) g.cp[i - 1] = ' '.code
        if (w == 1 && col + 1 < g.w && g.cp[i + 1] == CONT) g.cp[i + 1] = ' '.code

        cell(i, cp, s, opaque)
        if (w == 2) {
            if (col + 2 < g.w && g.cp[i + 2] == CONT) g.cp[i + 2] = ' '.code
            cell(i + 1, CONT, s, opaque)
        }
    }

    private fun cell(i: Int, cp: Int, s: Style, opaque: Boolean) {
        g.cp[i] = cp
        g.fg[i] = s.fg.raw
        if (opaque || !s.bg.isDefault) g.bg[i] = s.bg.raw
        g.at[i] = s.attrs.bits
    }

        fun text(x: Int, y: Int, s: CharSequence, style: Style = Style()): Int {
        var cx = x
        var i = 0
        while (i < s.length && cx < width) {
            val cp = Character.codePointAt(s, i)
            i += Character.charCount(cp)
            val w = cpWidth(cp)
            if (w == 0) continue
            plot(cx, y, cp, w, style)
            cx += w
        }
        return cx
    }

    fun center(y: Int, s: CharSequence, style: Style = Style()) =
        text((width - textWidth(s)) / 2, y, s, style)

        fun gradient(x: Int, y: Int, s: CharSequence, from: Color, to: Color, attrs: Attrs = Attrs.None): Int {
        val n = Character.codePointCount(s, 0, s.length)
        var cx = x
        var i = 0
        var k = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            i += Character.charCount(cp)
            val w = cpWidth(cp)
            if (w == 0) continue
            val t = if (n > 1) k / (n - 1f) else 0f
            plot(cx, y, cp, w, Style(from.lerp(to, t), Color.Default, attrs))
            cx += w
            k++
        }
        return cx
    }

    fun fill(x: Int, y: Int, w: Int, h: Int, ch: Char = ' ', style: Style = Style()) {
        for (yy in max(0, y) until min(height, y + h))
            for (xx in max(0, x) until min(width, x + w))
                plot(xx, yy, ch.code, 1, style, opaque = true)
    }

    fun clear(style: Style = Style()) = fill(0, 0, width, height, ' ', style)

    fun hline(x: Int, y: Int, len: Int, ch: Char = '─', style: Style = Style()) {
        for (i in 0 until len) plot(x + i, y, ch.code, 1, style)
    }

    fun vline(x: Int, y: Int, len: Int, ch: Char = '│', style: Style = Style()) {
        for (i in 0 until len) plot(x, y + i, ch.code, 1, style)
    }

    fun box(x: Int, y: Int, w: Int, h: Int, border: Border = Border.Single, style: Style = Style(), title: String? = null) {
        if (w < 2 || h < 2) return
        hline(x + 1, y, w - 2, border.h, style)
        hline(x + 1, y + h - 1, w - 2, border.h, style)
        vline(x, y + 1, h - 2, border.v, style)
        vline(x + w - 1, y + 1, h - 2, border.v, style)
        plot(x, y, border.tl.code, 1, style)
        plot(x + w - 1, y, border.tr.code, 1, style)
        plot(x, y + h - 1, border.bl.code, 1, style)
        plot(x + w - 1, y + h - 1, border.br.code, 1, style)
        if (title != null) window(x + 1, y, w - 2, 1) { text(1, 0, title, style) }
    }

        fun bar(x: Int, y: Int, w: Int, value: Float, filled: Style, empty: Style = Style()) {
        val eighths = (value.coerceIn(0f, 1f) * w * 8).roundToInt()
        val full = eighths / 8
        val part = eighths % 8
        for (i in 0 until w) {
            when {
                i < full -> plot(x + i, y, '█'.code, 1, filled, opaque = true)
                i == full && part > 0 ->
                    plot(x + i, y, PARTIAL_BLOCKS[part - 1].code, 1, filled.copy(bg = empty.bg), opaque = true)
                else -> plot(x + i, y, ' '.code, 1, empty, opaque = true)
            }
        }
    }

        fun cursor(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        g.curX = ox + x
        g.curY = oy + y
    }

        fun window(x: Int, y: Int, w: Int, h: Int, draw: Canvas.() -> Unit) {
        val x0 = max(0, x)
        val y0 = max(0, y)
        val x1 = min(width, x + w)
        val y1 = min(height, y + h)
        if (x1 <= x0 || y1 <= y0) return
        Canvas(g, ox + x0, oy + y0, x1 - x0, y1 - y0).draw()
    }
}

private object Tty {
        fun stty(vararg args: String): String? = try {
        val p = ProcessBuilder("sh", "-c", "stty ${args.joinToString(" ")} < /dev/tty 2>&1")
            .redirectErrorStream(true)
            .start()
        val text = p.inputStream.readBytes().decodeToString().trim()
        if (p.waitFor() == 0) text else null
    } catch (_: IOException) {
        null
    }
}

private class InputReader(private val ins: InputStream, private val sink: (Event) -> Unit) {
    fun run() {
        try {
            while (true) {
                val b = ins.read()
                if (b < 0) break
                dispatch(b)
            }
        } catch (_: IOException) {
        }
    }

        private fun next(ms: Long = 25): Int {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (ins.available() == 0) {
            if (System.nanoTime() >= deadline) return -1
            Thread.sleep(1)
        }
        return ins.read()
    }

    private fun dispatch(b: Int) {
        if (b != 0x1B) return sink(plain(b))
        when (val n = next()) {
            -1 -> sink(KeyEvent(Key.Escape))
            '['.code -> csi()
            'O'.code -> ss3()
            0x1B -> sink(KeyEvent(Key.Escape, alt = true))
            else -> sink(plain(n).copy(alt = true))
        }
    }

    private fun plain(b: Int): KeyEvent = when {
        b == 13 || b == 10 -> KeyEvent(Key.Enter)
        b == 9 -> KeyEvent(Key.Tab)
        b == 127 || b == 8 -> KeyEvent(Key.Backspace)
        b == 0 -> KeyEvent(Key.rune(' '.code), ctrl = true)
        b < 27 -> KeyEvent(Key.rune('a'.code + b - 1), ctrl = true)
        b < 32 -> KeyEvent(Key.rune('@'.code + b), ctrl = true)
        b < 128 -> KeyEvent(Key.rune(b))
        else -> KeyEvent(Key.rune(utf8(b)))
    }

    private fun utf8(lead: Int): Int {
        val extra = when {
            lead >= 0xF0 -> 3
            lead >= 0xE0 -> 2
            lead >= 0xC0 -> 1
            else -> return 0xFFFD
        }
        var cp = lead and (0x3F shr extra)
        repeat(extra) {
            val c = next(100)
            if (c < 0 || (c and 0xC0) != 0x80) return 0xFFFD
            cp = (cp shl 6) or (c and 0x3F)
        }
        return cp
    }

    private fun csi() {
        val params = StringBuilder()
        var final = -1
        while (final < 0) {
            val c = next(50)
            if (c < 0) return
            if (c in 0x40..0x7E) final = c else params.append(c.toChar())
        }
        val p = params.toString()
        if (p.startsWith("<") && (final == 'M'.code || final == 'm'.code)) {
            return mouse(p.substring(1), press = final == 'M'.code)
        }

        val n = p.split(';').map { it.toIntOrNull() }
        val mod = (n.getOrNull(1) ?: 1) - 1
        val key = when (final.toChar()) {
            'A' -> Key.Up
            'B' -> Key.Down
            'C' -> Key.Right
            'D' -> Key.Left
            'H' -> Key.Home
            'F' -> Key.End
            'P' -> Key.F1
            'Q' -> Key.F2
            'R' -> Key.F3
            'S' -> Key.F4
            'Z' -> return sink(KeyEvent(Key.Tab, shift = true))
            '~' -> when (n[0]) {
                1, 7 -> Key.Home
                2 -> Key.Insert
                3 -> Key.Delete
                4, 8 -> Key.End
                5 -> Key.PageUp
                6 -> Key.PageDown
                11 -> Key.F1
                12 -> Key.F2
                13 -> Key.F3
                14 -> Key.F4
                15 -> Key.F5
                17 -> Key.F6
                18 -> Key.F7
                19 -> Key.F8
                20 -> Key.F9
                21 -> Key.F10
                23 -> Key.F11
                24 -> Key.F12
                else -> return
            }
            else -> return
        }
        sink(KeyEvent(key, shift = (mod and 1) != 0, alt = (mod and 2) != 0, ctrl = (mod and 4) != 0))
    }

    private fun ss3() {
        val key = when (next(50).toChar()) {
            'A' -> Key.Up
            'B' -> Key.Down
            'C' -> Key.Right
            'D' -> Key.Left
            'H' -> Key.Home
            'F' -> Key.End
            'P' -> Key.F1
            'Q' -> Key.F2
            'R' -> Key.F3
            'S' -> Key.F4
            else -> return
        }
        sink(KeyEvent(key))
    }

    private fun mouse(s: String, press: Boolean) {
        val parts = s.split(';').map { it.toIntOrNull() ?: return }
        if (parts.size < 3) return
        val (b, x, y) = parts

        val wheel = (b and 64) != 0
        val low = b and 3
        val button = when {
            wheel -> if (low == 0) MouseButton.WheelUp else MouseButton.WheelDown
            low == 0 -> MouseButton.Left
            low == 1 -> MouseButton.Middle
            low == 2 -> MouseButton.Right
            else -> MouseButton.None
        }
        val action = when {
            wheel -> MouseAction.Scroll
            !press -> MouseAction.Release
            (b and 32) != 0 -> MouseAction.Drag
            else -> MouseAction.Press
        }
        sink(
            MouseEvent(
                x - 1, y - 1, button, action,
                shift = (b and 4) != 0, alt = (b and 8) != 0, ctrl = (b and 16) != 0,
            )
        )
    }
}

class Screen private constructor(val mouse: Boolean, val colors: ColorMode) : AutoCloseable {
    var width = 0
        private set
    var height = 0
        private set

    private val saved = Tty.stty("-g") ?: error("KTCurses: not running in a terminal")
    private val out = FileOutputStream(FileDescriptor.out)
    private val queue = LinkedBlockingQueue<Event>()
    private val closed = AtomicBoolean(false)
    private val hook = Thread { restore() }
    private val winch: Boolean

    private var front = Grid(0, 0)
    private var back = Grid(0, 0)
    private var clearNext = true
    private var lastMeasure = 0L

    init {
        Tty.stty("raw", "-echo")
        Runtime.getRuntime().addShutdownHook(hook)
        winch = hookResize()
        measure()
        write(
            "\u001B[?1049h\u001B[?25l\u001B[2J" +
                if (mouse) "\u001B[?1000h\u001B[?1002h\u001B[?1006h" else ""
        )
        val reader = InputReader(FileInputStream(FileDescriptor.`in`)) { queue.offer(it) }
        thread(isDaemon = true, name = "ktcurses-input") { reader.run() }
    }

        fun render(draw: Canvas.() -> Unit) {
        back.clear()
        Canvas(back, 0, 0, width, height).draw()
        flush()
    }

        fun poll(timeoutMs: Long = 0): Event? {
        val e = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (e is ResizeEvent) {
            measure()
            return ResizeEvent(width, height)
        }
        if (e == null && !winch) {
            val now = System.nanoTime()
            if (now - lastMeasure > 500_000_000L) {
                lastMeasure = now
                if (measure()) return ResizeEvent(width, height)
            }
        }
        return e
    }

    fun waitEvent(): Event = poll(Long.MAX_VALUE)!!

    fun title(text: String) = write("\u001B]0;$text\u0007")

    fun bell() = write("\u0007")

        fun copyToClipboard(text: String) =
        write("\u001B]52;c;${Base64.getEncoder().encodeToString(text.toByteArray())}\u0007")

    override fun close() {
        restore()
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
        }
    }

    private fun restore() {
        if (!closed.compareAndSet(false, true)) return
        write(
            "\u001B[0m\u001B[?25h\u001B[?2026l" +
                (if (mouse) "\u001B[?1006l\u001B[?1002l\u001B[?1000l" else "") +
                "\u001B[?1049l"
        )
        Tty.stty(saved)
    }

    private fun write(s: String) {
        out.write(s.toByteArray())
        out.flush()
    }

    private fun hookResize(): Boolean = try {
        sun.misc.Signal.handle(sun.misc.Signal("WINCH")) { queue.offer(ResizeEvent(0, 0)) }
        true
    } catch (_: Throwable) {
        false
    }

    private fun measure(): Boolean {
        val size = (Tty.stty("size") ?: return false).split(' ').mapNotNull { it.toIntOrNull() }
        if (size.size != 2) return false
        val (h, w) = size
        if (w <= 0 || h <= 0 || (w == front.w && h == front.h)) return false
        width = w
        height = h
        front = Grid(w, h).also { it.invalidate() }
        back = Grid(w, h)
        clearNext = true
        return true
    }

    private fun flush() {
        val sb = StringBuilder(8192)
        sb.append("\u001B[?2026h")
        if (clearNext) {
            sb.append("\u001B[0m\u001B[2J")
            front.invalidate()
            clearNext = false
        }

        var sFg = -2
        var sBg = -2
        var sAt = -1
        var cx = -1
        var cy = -1

        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                val i = y * width + x
                val c = back.cp[i]
                if (c == CONT) {
                    x++
                    continue
                }
                val wide = x + 1 < width && back.cp[i + 1] == CONT
                val step = if (wide) 2 else 1
                val dirty = !back.same(front, i) || (wide && !back.same(front, i + 1))
                if (!dirty) {
                    x += step
                    continue
                }

                if (cx != x || cy != y) {
                    sb.append("\u001B[").append(y + 1).append(';').append(x + 1).append('H')
                }
                val f = back.fg[i]
                val b = back.bg[i]
                val a = back.at[i]
                if (f != sFg || b != sBg || a != sAt) {
                    sgr(sb, f, b, a)
                    sFg = f; sBg = b; sAt = a
                }
                sb.appendCodePoint(c)

                x += step
                cx = x
                cy = y
                if (cx >= width) cx = -1
            }
        }

        if (back.curX >= 0) {
            sb.append("\u001B[").append(back.curY + 1).append(';').append(back.curX + 1).append("H\u001B[?25h")
        } else {
            sb.append("\u001B[?25l")
        }
        sb.append("\u001B[?2026l")

        write(sb.toString())
        front.copyFrom(back)
    }

    private fun sgr(sb: StringBuilder, fg: Int, bg: Int, at: Int) {
        sb.append("\u001B[0")
        for (k in SGR_ATTRS.indices) {
            if (((at shr k) and 1) != 0) sb.append(';').append(SGR_ATTRS[k])
        }
        color(sb, fg, 38)
        color(sb, bg, 48)
        sb.append('m')
    }

    private fun color(sb: StringBuilder, raw: Int, base: Int) {
        if (raw < 0) return
        val c = Color.hex(raw)
        when (colors) {
            ColorMode.TrueColor ->
                sb.append(';').append(base).append(";2;").append(c.r).append(';').append(c.g).append(';').append(c.b)
            ColorMode.Ansi256 -> sb.append(';').append(base).append(";5;").append(to256(c))
            ColorMode.None -> {}
        }
    }

    companion object {
        private val SGR_ATTRS = intArrayOf(1, 2, 3, 4, 5, 7, 9)

        fun open(mouse: Boolean = false, colors: ColorMode = ColorMode.detect()) = Screen(mouse, colors)

        private fun to256(c: Color): Int {
            if (c.r == c.g && c.g == c.b) {
                return when {
                    c.r < 8 -> 16
                    c.r > 248 -> 231
                    else -> 232 + (c.r - 8) * 24 / 247
                }
            }
            fun level(v: Int) = if (v < 48) 0 else if (v < 115) 1 else (v - 35) / 40
            return 16 + 36 * level(c.r) + 6 * level(c.g) + level(c.b)
        }
    }
}

inline fun ktcurses(
    mouse: Boolean = false,
    colors: ColorMode = ColorMode.detect(),
    block: (Screen) -> Unit,
) {
    Screen.open(mouse, colors).use(block)
}
