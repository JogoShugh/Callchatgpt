import android.os.StrictMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// BedCommand data classes
sealed class BedCommand() : BedId {
    abstract val bedId: String

    data class PrepareBedCommand(
        override val bedId: String,
        val name: String,
        val dimensions: Dimensions
    ) : BedCommand()

    data class PlantSeedlingInBedCommand(
        override val bedId: String,
        val rowPosition: Int,
        val cellPositionInRow: Int,
        val plantType: String,
        val plantCultivar: String
    ) : BedCommand()

    data class FertilizeBedCommand(
        override val bedId: String,
        val started: String, // ISO 8601 format
        val volume: Double,
        val fertilizer: String
    ) : BedCommand()

    data class WaterBedCommand(
        override val bedId: String,
        val started: String, // ISO 8601 format
        val volume: Double
    ) : BedCommand()

    data class HarvestBedCommand(
        override val bedId: String,
        val started: String, // ISO 8601 format
        val plantType: String,
        val plantCultivar: String,
        val quantity: Int? = null,
        val weight: Double? = null
    ) : BedCommand()
}

data class Dimensions(val width: Double, val length: Double)

interface BedId {
    val bedId: String
}

fun main(args: Array<String>) {
    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitNetwork().build())

    val command = args.firstOrNull() ?: "Planted tomatoes in row 1..."

    runBlocking {
        val jsonCommands = getCommandsFromChatGPT(command)
        for ((action, payload) in jsonCommands) {
            val response = sendCommandToBackend(action, payload)
            println("Response for $action: $response") // Print backend response for each command
        }
    }
}

suspend fun getCommandsFromChatGPT(command: String): Map<String, JsonElement> {
    val client = HttpClient.newHttpClient()

    val initialPrompt = buildString {
        appendLine("""
            You are a helpful assistant that generates a JSON object where keys represent actions for a garden bed (e.g., "plant", "water") and values are objects containing the details needed for those actions.
            The available actions and their expected parameters are defined by the following Kotlin data classes:
        """)

        for (subclass in BedCommand::class.sealedSubclasses) {
            appendLine(subclass.simpleName + ":")
            for (descriptor in subclass.serializer().descriptor.elementDescriptors) {
                appendLine("- ${descriptor.serialName}: ${descriptor.kind}")
            }
            appendLine()
        }

        appendLine("""
            The 'started' field should be in ISO 8601 format (e.g., "2024-05-15T12:00:00").
            
            Example:
            {
                "plant": {"bedId": "someId", "rowPosition": 1, "cellPositionInRow": 2, "plantType": "tomato", "plantCultivar": "Brandywine"},
                "water": {"bedId": "someId", "started": "2024-05-15T12:00:00", "volume": 0.5}
            }
        """)
    }

    val requestBody = buildJsonObject {
        put("model", "gpt-3.5-turbo")
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", initialPrompt)
            }
            addJsonObject {
                put("role", "user")
                put("content", command)
            }
        }
    }

    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.openai.com/v1/chat/completions"))
        .header("Authorization", "Bearer sk-proj-bzQNW0hWbxqoHG5damfmT3BlbkFJ2uGC7ykxAmswmuiOA2mt")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    println(response.body()) 
    
    if (response.statusCode() != 200) {
        throw Exception("ChatGPT API error: ${response.statusCode()} - ${response.body()}")
    }

    return extractCommandsFromJson(response.body())
}

fun extractCommandsFromJson(response: String): Map<String, JsonElement> {
    return try {
        val jsonObject = Json.parseToJsonElement(response)
        val commands = jsonObject.jsonObject["choices"]?.jsonArray
            ?.map { it.jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content }
            ?.firstOrNull()
            ?.let { Json.parseToJsonElement(it).jsonObject } ?: emptyMap()
        commands
    } catch (e: Exception) {
        println("Error parsing JSON response: ${e.message}")
        emptyMap()
    }
}

suspend fun sendCommandToBackend(action: String, payload: JsonElement): String? {
    val client = HttpClient.newHttpClient()
    val endpoint = "https://starbornag-vevkduzweq-uc.a.run.app/api/beds/67ea635a-8840-4bfa-899d-fed572de48a4/$action"

    val request = HttpRequest.newBuilder()
        .uri(URI.create(endpoint))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
        .build()

    return try {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            println("Backend API error for $action: ${response.statusCode()} - ${response.body()}")
            null
        } else {
            response.body()
        }
    } catch (e: Exception) {
        println("Error sending $action command: ${e.message}")
        null
    }
}
