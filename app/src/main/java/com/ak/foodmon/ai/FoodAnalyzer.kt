package com.ak.foodmon.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.ak.foodmon.data.VisionModel
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

data class AnalyzedItem(
    val name: String,
    val grams: Double,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
)

data class Analysis(val items: List<AnalyzedItem>, val notes: String)

class AnalysisException(message: String) : Exception(message)

/** Sends a meal photo to Claude and gets back per-item portion and nutrition estimates. */
class FoodAnalyzer {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    private fun clientFor(apiKey: String): AnthropicClient {
        if (clientKey != apiKey) {
            client?.close()
            client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            clientKey = apiKey
        }
        return client!!
    }

    suspend fun analyze(apiKey: String, model: VisionModel, jpeg: ByteArray, hint: String): Analysis =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw AnalysisException("Add your Anthropic API key in Settings first.")

            val image = ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .build()
                )
                .build()
            val prompt = buildString {
                append("Estimate the nutrition of the food in this photo.")
                if (hint.isNotBlank()) append("\n\nNote from the person who ate it: ").append(hint.trim())
            }

            val params = MessageCreateParams.builder()
                .model(model.id)
                .maxTokens(16000L)
                .system(SYSTEM_PROMPT)
                .outputConfig(
                    OutputConfig.builder()
                        .effort(OutputConfig.Effort.MEDIUM)
                        .format(JsonOutputFormat.builder().schema(RESPONSE_SCHEMA).build())
                        .build()
                )
                .addUserMessageOfBlockParams(
                    listOf(
                        ContentBlockParam.ofImage(image),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(prompt).build()),
                    )
                )
                .apply {
                    // If a safety classifier wrongly declines a request, retry it on a fallback
                    // model server-side instead of failing. Haiku has no server-side fallback.
                    if (model != VisionModel.HAIKU) {
                        putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                        putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    }
                }
                .build()

            val message = try {
                clientFor(apiKey).messages().create(params)
            } catch (e: UnauthorizedException) {
                throw AnalysisException("The API key was rejected. Check it in Settings.")
            } catch (e: AnthropicServiceException) {
                throw AnalysisException("Claude API error (${e.statusCode()}): ${e.message}")
            }

            when (message.stopReason().orElse(null)) {
                StopReason.REFUSAL -> throw AnalysisException("Claude declined to analyze this photo.")
                StopReason.MAX_TOKENS -> throw AnalysisException("The response was cut off. Please try again.")
                else -> Unit
            }

            val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            parse(text)
        }

    private fun parse(text: String): Analysis {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw AnalysisException("Couldn't read the response from Claude.")
        }
        val array = root.getJSONArray("items")
        val items = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            AnalyzedItem(
                name = o.getString("name"),
                grams = o.getDouble("estimated_grams"),
                kcal = o.getDouble("calories"),
                proteinG = o.getDouble("protein_g"),
                carbsG = o.getDouble("carbs_g"),
                fatG = o.getDouble("fat_g"),
            )
        }
        return Analysis(items, root.optString("notes"))
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are a nutrition estimator for a personal calorie-tracking app. The user photographs a meal and you estimate what it contains so they can log it.

List each distinct food or drink as its own item, including sauces, dressings, cooking oil and drinks when they add meaningful calories. For each item, estimate the portion weight in grams from visual cues such as plate size (a dinner plate is about 26 cm), cutlery, hands and packaging, then give calories and macronutrients for that portion using typical reference values such as USDA FoodData Central. If the user adds a note, trust it over what you see.

Name items in plain everyday terms ("grilled chicken breast", "white rice"). When unsure between options, pick the most likely one and mention the uncertainty in notes. Use notes for one or two short sentences the user should know, such as hidden oil or an assumed portion size. If the photo contains no food, return an empty items list and say so in notes."""

        private fun obj(vararg pairs: Pair<String, Any>): Map<String, Any> = mapOf(*pairs)

        private val ITEM_SCHEMA = obj(
            "type" to "object",
            "properties" to obj(
                "name" to obj("type" to "string"),
                "estimated_grams" to obj("type" to "number"),
                "calories" to obj("type" to "number"),
                "protein_g" to obj("type" to "number"),
                "carbs_g" to obj("type" to "number"),
                "fat_g" to obj("type" to "number"),
            ),
            "required" to listOf("name", "estimated_grams", "calories", "protein_g", "carbs_g", "fat_g"),
            "additionalProperties" to false,
        )

        private val RESPONSE_SCHEMA: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(obj("items" to obj("type" to "array", "items" to ITEM_SCHEMA), "notes" to obj("type" to "string"))),
            )
            .putAdditionalProperty("required", JsonValue.from(listOf("items", "notes")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()

        /**
         * Decodes a photo, applies its EXIF rotation and downsizes it to at most 1568 px on the
         * long side (larger images cost more tokens without improving the estimate).
         */
        fun prepareJpeg(file: File, maxSide: Int = 1568): ByteArray {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw AnalysisException("Couldn't read the photo.")

            val rotation = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
            val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            val bitmap: Bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }
    }
}