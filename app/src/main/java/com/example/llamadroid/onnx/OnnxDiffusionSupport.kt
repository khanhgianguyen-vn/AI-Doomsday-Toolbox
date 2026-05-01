package com.example.llamadroid.onnx

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt
import java.util.Random

private const val ONNX_EULER_TRAIN_TIMESTEPS = 1000
private const val ONNX_EULER_BETA_START = 0.00085f
private const val ONNX_EULER_BETA_END = 0.012f

internal enum class OnnxPredictionType {
    EPSILON,
    V_PREDICTION
}

internal data class OnnxImg2ImgSchedule(
    val startIndex: Int,
    val timesteps: IntArray
) {
    val activeStepCount: Int
        get() = timesteps.size
}

internal fun computeOnnxImg2ImgStartIndex(totalSteps: Int, strength: Float): Int {
    require(totalSteps > 0) { "totalSteps must be greater than 0" }
    val clampedStrength = strength.coerceIn(0.1f, 0.95f)
    val initTimestep = (totalSteps.toFloat() * clampedStrength)
        .toInt()
        .coerceIn(1, totalSteps)
    return (totalSteps - initTimestep).coerceIn(0, totalSteps - 1)
}

internal fun computeOnnxImg2ImgEffectiveSteps(totalSteps: Int, strength: Float): Int {
    val startIndex = computeOnnxImg2ImgStartIndex(totalSteps, strength)
    return (totalSteps - startIndex).coerceAtLeast(1)
}

internal fun createOnnxDiffusionTimesteps(
    inferenceSteps: Int,
    trainTimesteps: Int = ONNX_EULER_TRAIN_TIMESTEPS
): IntArray {
    require(inferenceSteps > 0) { "inferenceSteps must be greater than 0" }
    val scheduled = lineSpace(0.0, (trainTimesteps - 1).toDouble(), inferenceSteps)
    return IntArray(scheduled.size) { index ->
        scheduled[scheduled.lastIndex - index].toInt()
    }
}

internal class OnnxEulerAncestralScheduler(
    inferenceSteps: Int,
    seed: Long,
    trainTimesteps: Int = ONNX_EULER_TRAIN_TIMESTEPS,
    private val predictionType: OnnxPredictionType = OnnxPredictionType.EPSILON
) {
    private val random = Random(seed)
    private val sigmas: FloatArray
    val timesteps: IntArray
    val initNoiseSigma: Float

    init {
        val baseSigmas = buildBaseSigmas(trainTimesteps)
        timesteps = createOnnxDiffusionTimesteps(
            inferenceSteps = inferenceSteps,
            trainTimesteps = trainTimesteps
        )
        val interpolated = interpolate(
            positions = timesteps.map(Int::toDouble).toDoubleArray(),
            values = baseSigmas
        )
        sigmas = FloatArray(interpolated.size + 1)
        interpolated.forEachIndexed { index, value ->
            sigmas[index] = value.toFloat()
        }
        sigmas[sigmas.lastIndex] = 0f
        initNoiseSigma = sigmas.maxOrNull() ?: 1f
    }

    fun resolveImg2ImgSchedule(strength: Float): OnnxImg2ImgSchedule {
        val startIndex = computeOnnxImg2ImgStartIndex(timesteps.size, strength)
        return OnnxImg2ImgSchedule(
            startIndex = startIndex,
            timesteps = timesteps.copyOfRange(startIndex, timesteps.size)
        )
    }

    fun sigmaAt(stepIndex: Int): Float = sigmas[stepIndex]

    fun scaleModelInput(sample: FloatArray, stepIndex: Int): FloatArray {
        val sigma = sigmas[stepIndex].toDouble()
        val divisor = sqrt((sigma * sigma) + 1.0).toFloat()
        return FloatArray(sample.size) { index -> sample[index] / divisor }
    }

    fun addNoise(sample: FloatArray, noise: FloatArray, stepIndex: Int): FloatArray {
        val sigma = sigmas[stepIndex]
        return FloatArray(sample.size) { index ->
            sample[index] + (noise[index] * sigma)
        }
    }

    fun step(
        sample: FloatArray,
        modelOutput: FloatArray,
        stepIndex: Int
    ): FloatArray {
        val sigma = sigmas[stepIndex].toDouble()
        val sigmaTo = sigmas[stepIndex + 1].toDouble()
        val predictedOriginal = when (predictionType) {
            OnnxPredictionType.EPSILON -> {
                FloatArray(sample.size) { index ->
                    (sample[index] - (sigma.toFloat() * modelOutput[index]))
                }
            }
            OnnxPredictionType.V_PREDICTION -> {
                val sigmaSquaredPlusOne = (sigma * sigma) + 1.0
                val sampleScale = (1.0 / sigmaSquaredPlusOne).toFloat()
                val velocityScale = (-sigma / sqrt(sigmaSquaredPlusOne)).toFloat()
                FloatArray(sample.size) { index ->
                    (modelOutput[index] * velocityScale) + (sample[index] * sampleScale)
                }
            }
        }
        val sigmaUp = sqrt(
            ((sigmaTo * sigmaTo) * ((sigma * sigma) - (sigmaTo * sigmaTo)) / (sigma * sigma))
                .coerceAtLeast(0.0)
        )
        val sigmaDown = sqrt(((sigmaTo * sigmaTo) - (sigmaUp * sigmaUp)).coerceAtLeast(0.0))
        val dt = (sigmaDown - sigma).toFloat()
        return FloatArray(sample.size) { index ->
            val derivative = ((sample[index] - predictedOriginal[index]) / sigma.toFloat())
            val deterministic = sample[index] + (derivative * dt)
            (deterministic + (random.nextGaussian().toFloat() * sigmaUp.toFloat()))
        }
    }

    private fun buildBaseSigmas(trainTimesteps: Int): DoubleArray {
        val betas = FloatArray(trainTimesteps) { index ->
            val fraction = index.toFloat() / (trainTimesteps - 1).toFloat()
            val scaled = sqrt(ONNX_EULER_BETA_START) +
                (sqrt(ONNX_EULER_BETA_END) - sqrt(ONNX_EULER_BETA_START)) * fraction
            scaled * scaled
        }
        val alphasCumProd = FloatArray(trainTimesteps)
        var running = 1f
        for (index in 0 until trainTimesteps) {
            running *= 1f - betas[index]
            alphasCumProd[index] = running
        }
        return DoubleArray(trainTimesteps) { index ->
            val alpha = alphasCumProd[index].coerceAtLeast(1e-12f)
            sqrt(((1f - alpha) / alpha).toDouble())
        }
    }
}

private fun lineSpace(start: Double, end: Double, count: Int): DoubleArray {
    require(count > 0) { "count must be greater than 0" }
    if (count == 1) return doubleArrayOf(start)
    val step = (end - start) / (count - 1).toDouble()
    return DoubleArray(count) { index -> start + (step * index.toDouble()) }
}

private fun interpolate(
    positions: DoubleArray,
    values: DoubleArray
): DoubleArray {
    val maxIndex = values.lastIndex
    return DoubleArray(positions.size) { index ->
        val position = positions[index].coerceIn(0.0, maxIndex.toDouble())
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) {
            values[lower]
        } else {
            val weight = position - lower.toDouble()
            (values[lower] * (1.0 - weight)) + (values[upper] * weight)
        }
    }
}
