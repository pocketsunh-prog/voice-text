package com.voicetext.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Converts an audio file to MP3 or AAC (M4A) format using Android's built-in MediaCodec API.
 *
 * Pipeline: MediaExtractor → decoder → PCM → encoder → output
 *
 * MP3 encoding is NOT mandatory in Android's CDD, so it falls back to AAC
 * which is supported on ALL Android devices.
 */
object Mp3Exporter {

    private const val TAG = "Mp3Exporter"
    private const val BITRATE = 128000 // 128 kbps
    private const val TIMEOUT_US = 10_000L

    enum class Format(val mime: String, val extension: String) {
        MP3(MediaFormat.MIMETYPE_AUDIO_MPEG, "mp3"),
        AAC(MediaFormat.MIMETYPE_AUDIO_AAC, "m4a")
    }

    sealed class Result {
        data class Success(val format: Format, val message: String = "Export complete") : Result()
        data class Error(val message: String, val exception: Throwable? = null) : Result()
    }

    /**
     * Export to the given directory. Tries MP3 first, falls back to AAC.
     */
    fun export(inputFile: File, outputDir: File): Result {
        if (!inputFile.exists()) {
            return Result.Error("Input file not found: ${inputFile.absolutePath}")
        }
        outputDir.mkdirs()

        val format = if (isEncoderAvailable(Format.MP3.mime)) Format.MP3 else Format.AAC
        val outputFile = File(outputDir, "${inputFile.nameWithoutExtension}.${format.extension}")

        return try {
            FileOutputStream(outputFile).use { fos ->
                transcode(inputFile, fos, format)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            outputFile.delete()
            Result.Error("Export failed: ${e.message}", e)
        }
    }

    /**
     * Export to the given OutputStream. Tries MP3 first, falls back to AAC.
     */
    fun export(inputFile: File, outputStream: OutputStream): Result {
        if (!inputFile.exists()) {
            return Result.Error("Input file not found: ${inputFile.absolutePath}")
        }

        val format = if (isEncoderAvailable(Format.MP3.mime)) Format.MP3 else Format.AAC
        return try {
            outputStream.use { os ->
                transcode(inputFile, os, format)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Result.Error("Export failed: ${e.message}", e)
        }
    }

    /**
     * Core transcoding logic.
     */
    private fun transcode(inputFile: File, outputStream: OutputStream, format: Format): Result {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null

        try {
            // ── Set up extractor ──
            extractor.setDataSource(inputFile.absolutePath)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                return Result.Error("No audio track found in ${inputFile.name}")
            }
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return Result.Error("Unknown audio format")
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }

            Log.d(TAG, "Input: mime=$mime sampleRate=$sampleRate channels=$channelCount → ${format.name}")

            // ── Set up decoder ──
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            // ── Set up encoder ──
            val encodeFormat = MediaFormat.createAudioFormat(format.mime, sampleRate, channelCount)
            encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            if (format == Format.AAC) {
                encodeFormat.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
            }

            encoder = MediaCodec.createEncoderByType(format.mime).apply {
                configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            val decodeBufferInfo = MediaCodec.BufferInfo()
            val encodeBufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawDecoderEOS = false
            var sawEncoderEOS = false

            // ── Transcoding loop ──
            while (!sawEncoderEOS) {
                // Feed data to decoder
                if (!sawInputEOS) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // Drain decoder output → feed to encoder
                if (!sawDecoderEOS) {
                    val decOutIndex = decoder.dequeueOutputBuffer(decodeBufferInfo, TIMEOUT_US)
                    if (decOutIndex >= 0) {
                        val decodedBuffer = decoder.getOutputBuffer(decOutIndex)!!

                        if ((decodeBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            decodeBufferInfo.size = 0
                        }

                        if (decodeBufferInfo.size > 0) {
                            val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInIndex >= 0) {
                                val encInBuffer = encoder.getInputBuffer(encInIndex)!!
                                encInBuffer.clear()

                                decodedBuffer.position(decodeBufferInfo.offset)
                                decodedBuffer.limit(decodeBufferInfo.offset + decodeBufferInfo.size)

                                if (encInBuffer.remaining() >= decodeBufferInfo.size) {
                                    encInBuffer.put(decodedBuffer)
                                    encoder.queueInputBuffer(
                                        encInIndex, 0, decodeBufferInfo.size,
                                        decodeBufferInfo.presentationTimeUs, 0
                                    )
                                } else {
                                    // Feed in chunks if encoder buffer is smaller
                                    val chunkSize = encInBuffer.remaining()
                                    if (chunkSize > 0) {
                                        decodedBuffer.limit(decodeBufferInfo.offset + chunkSize)
                                        encInBuffer.put(decodedBuffer)
                                        encoder.queueInputBuffer(
                                            encInIndex, 0, chunkSize,
                                            decodeBufferInfo.presentationTimeUs, 0
                                        )
                                    }
                                }
                            }
                        }

                        decoder.releaseOutputBuffer(decOutIndex, false)

                        if ((decodeBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawDecoderEOS = true
                            val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInIndex >= 0) {
                                encoder.queueInputBuffer(
                                    encInIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                            }
                        }
                    } else if (decOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Decoder output format changed: ${decoder.outputFormat}")
                    }
                }

                // Drain encoder output → write to stream
                val encOutIndex = encoder.dequeueOutputBuffer(encodeBufferInfo, TIMEOUT_US)
                if (encOutIndex >= 0) {
                    val encodedBuffer = encoder.getOutputBuffer(encOutIndex)!!

                    if ((encodeBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encodeBufferInfo.size = 0
                    }

                    if (encodeBufferInfo.size > 0) {
                        val encodedBytes = ByteArray(encodeBufferInfo.size)
                        encodedBuffer.position(encodeBufferInfo.offset)
                        encodedBuffer.limit(encodeBufferInfo.offset + encodeBufferInfo.size)
                        encodedBuffer.get(encodedBytes)
                        outputStream.write(encodedBytes)
                    }

                    encoder.releaseOutputBuffer(encOutIndex, false)

                    if ((encodeBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawEncoderEOS = true
                    }
                } else if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Encoder output format changed: ${encoder.outputFormat}")
                }
            }

            Log.d(TAG, "Export complete: ${format.name}")
            return Result.Success(format)

        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            return Result.Error("Export failed: ${e.message}", e)
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {}
            try {
                encoder?.stop()
                encoder?.release()
            } catch (_: Exception) {}
            try {
                extractor.release()
            } catch (_: Exception) {}
        }
    }

    private fun isEncoderAvailable(mime: String): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecList.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }
}
