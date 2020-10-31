package au.com.dius.pact.core.model

import au.com.dius.pact.core.model.messaging.MessagePact
import au.com.dius.pact.core.support.Json
import au.com.dius.pact.core.support.json.JsonValue
import au.com.dius.pact.core.support.json.map
import au.com.dius.pact.core.support.jsonObject
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import mu.KLogging
import mu.KotlinLogging
import org.apache.commons.lang3.builder.HashCodeBuilder
import java.util.Base64

private val logger = KotlinLogging.logger {}

enum class V4InteractionType {
  SynchronousHTTP,
  AsynchronousMessages,
  SynchronousMessages;

  override fun toString(): String {
    return when (this) {
      SynchronousHTTP -> "Synchronous/HTTP"
      AsynchronousMessages -> "Asynchronous/Messages"
      SynchronousMessages -> "Synchronous/Messages"
    }
  }

  companion object {
    fun fromString(str: String): Result<V4InteractionType, String> {
      return when (str) {
        "Synchronous/HTTP" -> Ok(SynchronousHTTP)
        "Asynchronous/Messages" -> Ok(AsynchronousMessages)
        "Synchronous/Messages" -> Ok(SynchronousMessages)
        else -> Err("'$str' is not a valid V4 interaction type")
      }
    }
  }
}

fun bodyFromJson(field: String, json: JsonValue, headers: Map<String, List<String>>): OptionalBody {
  var contentType = ContentType.UNKNOWN
  val contentTypeEntry = headers.entries.find { it.key.toUpperCase() == "CONTENT-TYPE" }
  if (contentTypeEntry != null) {
    contentType = ContentType(contentTypeEntry.value.first())
  }

  return if (json.has(field)) {
    when (val jsonBody = json[field]) {
      is JsonValue.Object -> if (jsonBody.has("content")) {
        if (jsonBody.has("contentType")) {
          contentType = ContentType(Json.toString(jsonBody["contentType"]))
        } else {
          logger.warn("Body has no content type set, will default to any headers or metadata")
        }

        val (encoded, encoding) = if (jsonBody.has("encoded")) {
          when (val encodedValue = jsonBody["encoded"]) {
            is JsonValue.StringValue -> true to encodedValue.toString()
            JsonValue.True -> true to "base64"
            else -> false to ""
          }
        } else {
          false to ""
        }

        val bodyBytes = if (encoded) {
          when (encoding) {
            "base64" -> Base64.getDecoder().decode(Json.toString(jsonBody["content"]))
            "json" -> Json.toString(jsonBody["content"]).toByteArray(contentType.asCharset())
            else -> {
              logger.warn("Unrecognised body encoding scheme '$encoding', will use the raw body")
              Json.toString(jsonBody["content"]).toByteArray(contentType.asCharset())
            }
          }
        } else {
          Json.toString(jsonBody["content"]).toByteArray(contentType.asCharset())
        }
        OptionalBody.body(bodyBytes, contentType)
      } else {
        OptionalBody.missing()
      }
      JsonValue.Null -> OptionalBody.nullBody()
      else -> {
        logger.warn("Body in attribute '$field' from JSON file is not formatted correctly, will load it as plain text")
        OptionalBody.body(Json.toString(jsonBody).toByteArray(contentType.asCharset()))
      }
    }
  } else {
    OptionalBody.missing()
  }
}

sealed class V4Interaction(
  val key: String,
  description: String,
  interactionId: String? = null,
  providerStates: List<ProviderState> = listOf()
) : BaseInteraction(interactionId, description, providerStates) {
  override fun conflictsWith(other: Interaction): Boolean {
    return false
  }

  override fun uniqueKey() = if (key.isEmpty()) { generateKey() } else { key }

  /** Created a copy of the interaction with the key calculated from contents */
  abstract fun withGeneratedKey(): V4Interaction

  /** Generate a unique key from the contents of the interaction */
  abstract fun generateKey(): String

  class SynchronousHttp(
    key: String,
    description: String,
    val request: HttpRequest,
    val response: HttpResponse,
    interactionId: String? = null,
    providerStates: List<ProviderState> = listOf()
  ) : V4Interaction(key, description, interactionId, providerStates) {

    @ExperimentalUnsignedTypes
    override fun withGeneratedKey(): V4Interaction {
      return SynchronousHttp(generateKey(), description, request, response, interactionId, providerStates)
    }

    @ExperimentalUnsignedTypes
    override fun generateKey(): String {
      return HashCodeBuilder(57, 11)
        .append(description)
        .append(request.hashCode())
        .append(response.hashCode())
        .append(providerStates.hashCode())
        .build().toUInt().toString(16)
    }

    override fun toMap(_pactSpecVersion: PactSpecVersion): Map<String, *> {
      return mapOf(
        "type" to V4InteractionType.SynchronousHTTP.toString(),
        "key" to uniqueKey(),
        "description" to description,
        "request" to request.toMap(),
        "response" to response.toMap()
      )
    }

    override fun validateForVersion(pactVersion: PactSpecVersion): List<String> {
      val errors = mutableListOf<String>()
      errors.addAll(request.validateForVersion(pactVersion))
      errors.addAll(response.validateForVersion(pactVersion))
      return errors
    }

    override fun asV4Interaction(): V4Interaction {
      return this
    }

    fun asV3Interaction(): RequestResponseInteraction {
      return RequestResponseInteraction(description, providerStates, request.toV3Request(), response.toV3Response(),
        interactionId)
    }
  }

  companion object : KLogging() {
    fun interactionFromJson(index: Int, json: JsonValue, source: PactSource): Result<V4Interaction, String> {
      return if (json.has("type")) {
        val type = Json.toString(json["type"])
        when (val result = V4InteractionType.fromString(type)) {
          is Ok -> {
            val id = json["_id"].asString()
            val key = Json.toString(json["key"])
            val description = Json.toString(json["description"])
            val providerStates = if (json.has("providerStates")) {
              json["providerStates"].asArray().map { ProviderState.fromJson(it) }
            } else {
              emptyList()
            }
            when (result.value) {
              V4InteractionType.SynchronousHTTP -> {
                Ok(SynchronousHttp(key, description, HttpRequest.fromJson(json["request"]),
                  HttpResponse.fromJson(json["response"]), id, providerStates))
              }
              V4InteractionType.AsynchronousMessages -> TODO()
              V4InteractionType.SynchronousMessages -> {
                val message = "Interaction type '$type' is currently unimplemented. It will be ignored. Source: $source"
                logger.warn(message)
                Err(message)
              }
            }
          }
          is Err -> {
            val message = "Interaction $index has invalid type attribute '$type'. It will be ignored. Source: $source"
            logger.warn(message)
            Err(message)
          }
        }
      } else {
        val message = "Interaction $index has no type attribute. It will be ignored. Source: $source"
        logger.warn(message)
        Err(message)
      }

      /*
        match i_type {
          V4InteractionType::Synchronous_HTTP => {
            let request = ijson.get("request").cloned().unwrap_or_default();
            let response = ijson.get("response").cloned().unwrap_or_default();
            Ok(V4Interaction::SynchronousHttp {
              id,
              key,
              description,
              provider_states,
              request: HttpRequest::from_json(&request),
              response: HttpResponse::from_json(&response)
            })
          }
          V4InteractionType::Asynchronous_Messages => {
            let metadata = match ijson.get("metadata") {
              Some(&Value::Object(ref v)) => v.iter().map(|(k, v)| {
                (k.clone(), v.clone())
              }).collect(),
              _ => hashmap!{}
            };
            let as_headers = metadata_to_headers(&metadata);
            Ok(V4Interaction::AsynchronousMessages {
              id,
              key,
              description,
              provider_states,
              metadata,
              contents: body_from_json(ijson, "contents", &as_headers),
              matching_rules: matchingrules::matchers_from_json(ijson, &None),
              generators: generators::generators_from_json(ijson)
            })
          }*/
    }
  }
}

open class V4Pact(
  consumer: Consumer,
  provider: Provider,
  override val interactions: List<V4Interaction>,
  metadata: Map<String, Any?> = DEFAULT_METADATA,
  source: PactSource = UnknownPactSource
) : BasePact(consumer, provider, metadata, source) {
  override fun sortInteractions(): Pact {
    return V4Pact(consumer, provider, interactions.sortedBy { interaction ->
      interaction.providerStates.joinToString { it.name.toString() } + interaction.description
    }, metadata, source)
  }

  override fun toMap(pactSpecVersion: PactSpecVersion): Map<String, Any?> {
    return mapOf(
      "provider" to objectToMap(provider),
      "consumer" to objectToMap(consumer),
      "interactions" to interactions.map { it.toMap(pactSpecVersion) },
      "metadata" to metaData(jsonObject(metadata.entries.map { it.key to Json.toJson(it.value) }), pactSpecVersion)
    )
  }

  override fun mergeInteractions(interactions: List<Interaction>): Pact {
    return V4Pact(consumer, provider, this.interactions + interactions.map { it.asV4Interaction() },
      metadata, source)
  }

  override fun asRequestResponsePact(): Result<RequestResponsePact, String> {
    return Ok(RequestResponsePact(provider, consumer,
      interactions.filterIsInstance<V4Interaction.SynchronousHttp>()
        .map { it.asV3Interaction() }))
  }

  override fun asMessagePact(): Result<MessagePact, String> {
//    return Ok(MessagePact(provider, consumer,
//      interactions.filterIsInstance<V4Interaction.>()
//        .map { it.toV3Interaction() }))
    TODO("Not yet implemented")
  }

  override fun asV4Pact() = Ok(this)
}