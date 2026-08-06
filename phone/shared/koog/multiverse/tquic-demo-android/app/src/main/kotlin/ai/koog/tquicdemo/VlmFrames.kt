package ai.koog.tquicdemo

import java.io.ByteArrayOutputStream

/**
 * TLV framing for `tquic-vlm-server-interface`'s `/v1/infer` body -- mirrors
 * `frames.rs::write_frames` exactly: 1-byte type + 8-byte big-endian length +
 * payload, image frame then text frame, back to back. See
 * `tquic-vlm-server-interface/docs/interface-guide.md` §3.3 for the full spec.
 */
object VlmFrames {
    private const val TYPE_IMAGE = 0x01
    private const val TYPE_TEXT = 0x02

    fun writeFrames(jpeg: ByteArray, prompt: String): ByteArray {
        val text = prompt.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(9 + jpeg.size + 9 + text.size)
        writeFrame(out, TYPE_IMAGE, jpeg)
        writeFrame(out, TYPE_TEXT, text)
        return out.toByteArray()
    }

    private fun writeFrame(out: ByteArrayOutputStream, type: Int, payload: ByteArray) {
        out.write(type)
        val len = payload.size.toLong()
        for (shift in 56 downTo 0 step 8) out.write(((len ushr shift) and 0xFF).toInt())
        out.write(payload)
    }
}
