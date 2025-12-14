package com.example.hazarddetectionapp

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream

class HazardModel(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val inputWidth = 512
    private val inputHeight = 512

    data class PredictionResult(
        val hazardDetected: Boolean,
        val label: String,
        val potholeProb: Float,
        val speedbumpProb: Float,
        val maxProb: Float
    )

    init {
        val modelBytes = loadModelFile(context, "hazard_model.onnx")
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    private fun loadModelFile(context: Context, fileName: String): ByteArray {
        context.assets.open(fileName).use { inputStream ->
            val buffer = ByteArrayOutputStream()
            val data = ByteArray(4096)
            var count: Int

            while (true) {
                count = inputStream.read(data)
                if (count == -1) break
                buffer.write(data, 0, count)
            }

            return buffer.toByteArray()
        }
    }

    fun predict(bitmap: Bitmap): PredictionResult {
        // 1. Resize
        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val inputData = bitmapToFloatNCHW(resized)

        val inputTensor = OnnxTensor.createTensor(env, inputData)
        val inputName = session.inputNames.iterator().next()
        val inputs = mapOf(inputName to inputTensor)

        val result = session.run(inputs)

        // ---- NEW: flexible output handling ----
        val outputValue = result[0].value

        // Try to extract probabilities as FloatArray of length >= 2
        val probs: FloatArray = when (outputValue) {
            is FloatArray -> {
                // shape [2]
                outputValue
            }
            is Array<*> -> {
                // Could be [1, 2] or [1, 1, 2]
                val first = outputValue.firstOrNull()
                    ?: throw IllegalStateException("Empty output array")

                when (first) {
                    is FloatArray -> {
                        // shape [1, 2]
                        first
                    }
                    is Array<*> -> {
                        // shape [1, 1, 2] maybe
                        val innerFirst = first.firstOrNull()
                            ?: throw IllegalStateException("Empty inner array")
                        if (innerFirst is FloatArray) {
                            innerFirst
                        } else {
                            throw IllegalStateException("Unexpected inner output type: ${innerFirst!!::class.java.name}")
                        }
                    }
                    else -> {
                        throw IllegalStateException("Unexpected array element type: ${first!!::class.java.name}")
                    }
                }
            }
            else -> {
                throw IllegalStateException("Unexpected output type: ${outputValue::class.java.name}")
            }
        }

        if (probs.size < 2) {
            throw IllegalStateException("Output has less than 2 values: size=${probs.size}")
        }

        // Decide which hazard class is more likely
        val potholeProb = probs[0]
        val speedbumpProb = probs[1]

// If you’re applying softmax, use those values instead:
// val potholeProb = ...
// val speedbumpProb = ...

        val (maxProb, hazardLabel) = if (potholeProb >= speedbumpProb) {
            potholeProb to "pothole"
        } else {
            speedbumpProb to "speedbump"
        }

// ✅ No threshold, always hazard
        val hazardDetected = true
        val finalLabel = hazardLabel

        inputTensor.close()
        result.close()

        return PredictionResult(
            hazardDetected = hazardDetected,
            label = finalLabel,
            potholeProb = potholeProb,
            speedbumpProb = speedbumpProb,
            maxProb = maxProb
        )


    }


    private fun bitmapToFloatNCHW(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val width = bitmap.width
        val height = bitmap.height

        val input = Array(1) {
            Array(3) {
                Array(height) {
                    FloatArray(width)
                }
            }
        }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var pixelIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[pixelIndex++]

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val rf = r / 255.0f
                val gf = g / 255.0f
                val bf = b / 255.0f

                input[0][0][y][x] = rf
                input[0][1][y][x] = gf
                input[0][2][y][x] = bf
            }
        }

        return input
    }

    fun close() {
        session.close()
        env.close()
    }
}
