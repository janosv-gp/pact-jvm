package au.com.dius.pact.provider

import arrow.core.Either
import arrow.core.right
import au.com.dius.pact.com.github.michaelbull.result.Ok
import au.com.dius.pact.com.github.michaelbull.result.getError
import au.com.dius.pact.core.matchers.BodyTypeMismatch
import au.com.dius.pact.core.matchers.HeaderMismatch
import au.com.dius.pact.core.matchers.MetadataMismatch
import au.com.dius.pact.core.matchers.StatusMismatch
import au.com.dius.pact.core.model.Interaction
import au.com.dius.pact.core.model.OptionalBody
import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.core.model.ProviderState
import au.com.dius.pact.core.model.RequestResponseInteraction
import au.com.dius.pact.core.model.Response
import au.com.dius.pact.core.model.messaging.Message
import au.com.dius.pact.core.pactbroker.PactBrokerClient
import au.com.dius.pact.core.pactbroker.TestResult
import au.com.dius.pact.provider.reporters.AnsiConsoleReporter
import au.com.dius.pact.provider.reporters.VerifierReporter
import groovy.lang.GroovyObjectSupport
import io.github.classgraph.ClassGraph
import mu.KLogging
import java.lang.reflect.Method
import java.net.URL
import java.net.URLClassLoader
import java.util.function.BiConsumer
import java.util.function.Function
import java.util.function.Supplier

@JvmOverloads
@Deprecated("Use the VerificationReporter instead of this function",
  ReplaceWith("DefaultVerificationReporter.reportResults(pact, result, version, client)"))
fun <I> reportVerificationResults(pact: Pact<I>, result: Boolean, version: String, client: PactBrokerClient? = null)
  where I : Interaction = DefaultVerificationReporter.reportResults(pact, TestResult.fromBoolean(result), version, client)

enum class PactVerification {
  REQUEST_RESPONSE, ANNOTATED_METHOD
}

/**
 * Exception indicating failure to setup pact verification
 */
class PactVerifierException(
  override val message: String = "PactVerifierException",
  override val cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Annotation to mark a test method for provider verification
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class PactVerifyProvider(
  /**
   * the tested provider name.
   */
  val value: String
)

data class MessageAndMetadata(val messageData: ByteArray, val metadata: Map<String, Any>)

/**
 * Interface to the provider verifier
 */
interface IProviderVerifier {
  /**
   * List of the all reporters to report the results of the verification to
   */
  var reporters: List<VerifierReporter>

  /**
   * Callback to determine if something is a build specific task
   */
  var checkBuildSpecificTask: Function<Any, Boolean>

  /**
   * Consumer SAM to execute the build specific task
   */
  var executeBuildSpecificTask: BiConsumer<Any, ProviderState>

  /**
   * Callback to determine is the project has a particular property
   */
  var projectHasProperty: Function<String, Boolean>

  /**
   * Callback to return the instance for the provider method to invoke
   */
  var providerMethodInstance: Function<Method, Any>

  /**
   * Callback to return the project classpath to use for looking up methods
   */
  var projectClasspath: Supplier<List<URL>>

  /**
   * Reports the state of the interaction to all the registered reporters
   */
  fun reportStateForInteraction(state: String, provider: IProviderInfo, consumer: IConsumerInfo, isSetup: Boolean)

  /**
   * Finalise all the reports after verification is complete
   */
  fun finaliseReports()

  /**
   * Displays all the failures from the verification run
   */
  fun displayFailures(failures: Map<String, Any>)

  /**
   * Verifies the response from the provider against the interaction
   */
  fun verifyResponseFromProvider(
    provider: IProviderInfo,
    interaction: RequestResponseInteraction,
    interactionMessage: String,
    failures: MutableMap<String, Any>,
    client: ProviderClient
  ): TestResult

  /**
   * Verifies the response from the provider against the interaction
   */
  fun verifyResponseFromProvider(
    provider: IProviderInfo,
    interaction: RequestResponseInteraction,
    interactionMessage: String,
    failures: MutableMap<String, Any>,
    client: ProviderClient,
    context: Map<String, Any>
  ): TestResult

  /**
   * Verifies the interaction by invoking a method on a provider test class
   */
  fun verifyResponseByInvokingProviderMethods(
    providerInfo: IProviderInfo,
    consumer: IConsumerInfo,
    interaction: Interaction,
    interactionMessage: String,
    failures: MutableMap<String, Any>
  ): TestResult

  /**
   * Compares the expected and actual responses
   */
  fun verifyRequestResponsePact(
    expectedResponse: Response,
    actualResponse: Map<String, Any>,
    interactionMessage: String,
    failures: MutableMap<String, Any>,
    interactionId: String
  ): TestResult

  /**
   * If publishing of verification results has been disabled
   */
  fun publishingResultsDisabled(): Boolean

  /**
   * Display info about the interaction about to be verified
   */
  fun reportInteractionDescription(interaction: Interaction)
}

/**
 * Verifies the providers against the defined consumers in the context of a build plugin
 */
abstract class ProviderVerifierBase @JvmOverloads constructor (
  var pactLoadFailureMessage: Any? = null,
  override var checkBuildSpecificTask: Function<Any, Boolean> = Function { false },
  override var executeBuildSpecificTask: BiConsumer<Any, ProviderState> = BiConsumer { _, _ -> },
  override var projectClasspath: Supplier<List<URL>> = Supplier { emptyList<URL>() },
  override var reporters: List<VerifierReporter> = listOf(AnsiConsoleReporter()),
  override var providerMethodInstance: Function<Method, Any> = Function { m -> m.declaringClass.newInstance() },
  var providerVersion: Supplier<String> = Supplier { System.getProperty(PACT_PROVIDER_VERSION) }
) : GroovyObjectSupport(), IProviderVerifier {

  override var projectHasProperty = Function<String, Boolean> { name -> !System.getProperty(name).isNullOrEmpty() }
  var projectGetProperty = Function<String, String?> { name -> System.getProperty(name) }
  var verificationReporter: VerificationReporter = DefaultVerificationReporter
  var stateChangeHandler: StateChange = DefaultStateChange

  /**
   * This will return true unless the pact.verifier.publishResults property has the value of "true"
   */
  override fun publishingResultsDisabled(): Boolean {
    return !projectHasProperty.apply(PACT_VERIFIER_PUBLISH_RESULTS) ||
      projectGetProperty.apply(PACT_VERIFIER_PUBLISH_RESULTS)?.toLowerCase() != "true"
  }

  override fun verifyResponseByInvokingProviderMethods(
    providerInfo: IProviderInfo,
    consumer: IConsumerInfo,
    interaction: Interaction,
    interactionMessage: String,
    failures: MutableMap<String, Any>
  ): TestResult {
    try {
      val urls = projectClasspath.get()
      logger.debug { "projectClasspath = $urls" }

      val classGraph = ClassGraph().enableAllInfo()
      if (System.getProperty("pact.verifier.classpathscan.verbose") != null) {
        classGraph.verbose()
      }

      if (urls.isNotEmpty()) {
        classGraph.overrideClassLoaders(URLClassLoader(urls.toTypedArray()))
      }

      val scan = ProviderUtils.packagesToScan(providerInfo, consumer)
      if (scan.isNotEmpty()) {
        classGraph.whitelistPackages(*scan.toTypedArray())
      }

      val methodsAnnotatedWith = classGraph.scan().use { scanResult ->
        scanResult.getClassesWithMethodAnnotation(PactVerifyProvider::class.qualifiedName)
          .flatMap { classInfo ->
            logger.debug { "found class $classInfo" }
            val methodInfo = classInfo.methodInfo.filter {
              it.annotationInfo.any { info ->
                info.name == PactVerifyProvider::class.qualifiedName &&
                  info.parameterValues["value"].value == interaction.description
              }
            }
            logger.debug { "found method $methodInfo" }
            methodInfo.map { it.loadClassAndGetMethod() }
          }
      }

      logger.debug { "Found methods = $methodsAnnotatedWith" }
      if (methodsAnnotatedWith.isEmpty()) {
        reporters.forEach { it.errorHasNoAnnotatedMethodsFoundForInteraction(interaction) }
        throw RuntimeException("No annotated methods were found for interaction " +
          "'${interaction.description}'. You need to provide a method annotated with " +
          "@PactVerifyProvider(\"${interaction.description}\") on the classpath that returns the message contents.")
      } else {
        return if (interaction is Message) {
          verifyMessagePact(methodsAnnotatedWith.toHashSet(), interaction, interactionMessage, failures)
        } else {
          val expectedResponse = (interaction as RequestResponseInteraction).response
          var result: TestResult = TestResult.Ok
          methodsAnnotatedWith.forEach {
            val actualResponse = invokeProviderMethod(it, null) as Map<String, Any>
            result = result.merge(this.verifyRequestResponsePact(expectedResponse, actualResponse, interactionMessage,
              failures, interaction.interactionId.orEmpty()))
          }
          result
        }
      }
    } catch (e: Exception) {
      failures[interactionMessage] = e
      reporters.forEach { it.verificationFailed(interaction, e, projectHasProperty.apply(PACT_SHOW_STACKTRACE)) }
      return TestResult.Failed(listOf(mapOf("message" to "Request to provider method failed with an exception",
        "exception" to e, "interactionId" to interaction.interactionId)),
        "Request to provider method failed with an exception")
    }
  }

  fun displayBodyResult(
    failures: MutableMap<String, Any>,
    comparison: Either<BodyTypeMismatch, BodyComparisonResult>,
    comparisonDescription: String,
    interactionId: String
  ): TestResult {
    return if (comparison is Either.Right && comparison.b.mismatches.isEmpty()) {
      reporters.forEach { it.bodyComparisonOk() }
      TestResult.Ok
    } else {
      reporters.forEach { it.bodyComparisonFailed(comparison) }
      when (comparison) {
        is Either.Left -> {
          failures["$comparisonDescription has a matching body"] = comparison.a.description()
          TestResult.Failed(listOf(comparison.a.toMap() + mapOf("interactionId" to interactionId, "type" to "body")),
            "Body had differences")
        }
        is Either.Right -> {
          failures["$comparisonDescription has a matching body"] = comparison.b
          TestResult.Failed(listOf(comparison.b.mismatches + mapOf("interactionId" to interactionId, "type" to "body")),
            "Body had differences")
        }
      }
    }
  }

  fun verifyMessagePact(methods: Set<Method>, message: Message, interactionMessage: String, failures: MutableMap<String, Any>): TestResult {
    var result: TestResult = TestResult.Ok
    methods.forEach { method ->
      reporters.forEach { it.generatesAMessageWhich() }
      val messageResult = invokeProviderMethod(method, providerMethodInstance.apply(method))
      val actualMessage: OptionalBody
      var messageMetadata: Map<String, Any>? = null
      when (messageResult) {
        is MessageAndMetadata -> {
          actualMessage = OptionalBody.body(messageResult.messageData)
          messageMetadata = messageResult.metadata
        }
        is Pair<*, *> -> {
          actualMessage = OptionalBody.body(messageResult.first.toString().toByteArray())
          messageMetadata = messageResult.second as Map<String, Any>
        }
        is org.apache.commons.lang3.tuple.Pair<*, *> -> {
          actualMessage = OptionalBody.body(messageResult.left.toString().toByteArray())
          messageMetadata = messageResult.right as Map<String, Any>
        }
        else -> {
          actualMessage = OptionalBody.body(messageResult.toString().toByteArray())
        }
      }
      val comparison = ResponseComparison.compareMessage(message, actualMessage, messageMetadata)
      val s = " generates a message which"
      result = result.merge(displayBodyResult(failures, comparison.bodyMismatches,
        interactionMessage + s, message.interactionId.orEmpty()))
        .merge(displayMetadataResult(messageMetadata ?: emptyMap(), failures, comparison.metadataMismatches,
          interactionMessage + s, message.interactionId.orEmpty()))
    }
    return result
  }

  private fun displayMetadataResult(
    expectedMetadata: Map<String, Any>,
    failures: MutableMap<String, Any>,
    comparison: Map<String, List<MetadataMismatch>>,
    comparisonDescription: String,
    interactionId: String
  ): TestResult {
    return if (comparison.isEmpty()) {
      reporters.forEach { it.metadataComparisonOk() }
      TestResult.Ok
    } else {
      reporters.forEach { it.includesMetadata() }
      var result: TestResult = TestResult.Failed(emptyList(), "Metadata had differences")
      comparison.forEach { (key, metadataComparison) ->
        val expectedValue = expectedMetadata[key]
        if (metadataComparison.isEmpty()) {
          reporters.forEach { it.metadataComparisonOk(key, expectedValue) }
        } else {
          reporters.forEach { it.metadataComparisonFailed(key, expectedValue, metadataComparison) }
          failures["$comparisonDescription includes metadata \"$key\" with value \"$expectedValue\""] =
            metadataComparison
          result = result.merge(TestResult.Failed(listOf(mapOf(key to metadataComparison,
            "interactionId" to interactionId, "type" to "metadata"))))
        }
      }
      result
    }
  }

  override fun displayFailures(failures: Map<String, Any>) {
    reporters.forEach { it.displayFailures(failures) }
  }

  override fun finaliseReports() {
    reporters.forEach { it.finaliseReport() }
  }

  fun verifyInteraction(
    provider: IProviderInfo,
    consumer: IConsumerInfo,
    failures: MutableMap<String, Any>,
    interaction: Interaction
  ): TestResult {
    var interactionMessage = "Verifying a pact between ${consumer.name} and ${provider.name}" +
    " - ${interaction.description} "

    val providerClient = ProviderClient(provider, HttpClientFactory())
    val stateChangeResult = stateChangeHandler.executeStateChange(this, provider, consumer, interaction, interactionMessage,
      failures, providerClient)
    if (stateChangeResult.stateChangeResult is Ok) {
      interactionMessage = stateChangeResult.message
      reportInteractionDescription(interaction)

      val context = mapOf(
        "providerState" to stateChangeResult.stateChangeResult.value,
        "interaction" to interaction
      )

      val result = if (ProviderUtils.verificationType(provider, consumer) == PactVerification.REQUEST_RESPONSE) {
        logger.debug { "Verifying via request/response" }
        verifyResponseFromProvider(provider, interaction as RequestResponseInteraction, interactionMessage, failures,
          providerClient, context)
      } else {
        logger.debug { "Verifying via annotated test method" }
        verifyResponseByInvokingProviderMethods(provider, consumer, interaction, interactionMessage, failures)
      }

      if (provider.stateChangeTeardown) {
        stateChangeHandler.executeStateChangeTeardown(this, interaction, provider, consumer, providerClient)
      }

      return result
    } else {
      return TestResult.Failed(listOf(mapOf("message" to "State change request failed",
        "exception" to stateChangeResult.stateChangeResult.getError(),
        "interactionId" to interaction.interactionId)), "State change request failed")
    }
  }

  override fun reportInteractionDescription(interaction: Interaction) {
    reporters.forEach { it.interactionDescription(interaction) }
  }

  override fun verifyRequestResponsePact(
    expectedResponse: Response,
    actualResponse: Map<String, Any>,
    interactionMessage: String,
    failures: MutableMap<String, Any>,
    interactionId: String
  ): TestResult {
    val comparison = ResponseComparison.compareResponse(expectedResponse, actualResponse,
      actualResponse["statusCode"] as Int, actualResponse["headers"] as Map<String, List<String>>,
      actualResponse["data"] as String?)

    reporters.forEach { it.returnsAResponseWhich() }

    val s = " returns a response which"
    return displayStatusResult(failures, expectedResponse.status, comparison.statusMismatch, interactionMessage + s, interactionId)
      .merge(displayHeadersResult(failures, expectedResponse.headers, comparison.headerMismatches, interactionMessage + s, interactionId))
      .merge(displayBodyResult(failures, comparison.bodyMismatches, interactionMessage + s, interactionId))
  }

  fun displayStatusResult(
    failures: MutableMap<String, Any>,
    status: Int,
    mismatch: StatusMismatch?,
    comparisonDescription: String,
    interactionId: String
  ): TestResult {
    return if (mismatch == null) {
      reporters.forEach { it.statusComparisonOk(status) }
      TestResult.Ok
    } else {
      reporters.forEach { it.statusComparisonFailed(status, mismatch.description()) }
      failures["$comparisonDescription has statusResult code $status"] = mismatch.description()
      TestResult.Failed(listOf(mismatch.toMap() + mapOf("interactionId" to interactionId,
        "type" to "status")), "Response status did not match")
    }
  }

  fun displayHeadersResult(
    failures: MutableMap<String, Any>,
    expected: Map<String, List<String>>,
    headers: Map<String, List<HeaderMismatch>>,
    comparisonDescription: String,
    interactionId: String
  ): TestResult {
    return if (headers.isEmpty()) {
      TestResult.Ok
    } else {
      reporters.forEach { it.includesHeaders() }
      var result: TestResult = TestResult.Ok
      headers.forEach { (key, headerComparison) ->
        val expectedHeaderValue = expected[key]
        if (headerComparison.isEmpty()) {
          reporters.forEach { it.headerComparisonOk(key, expectedHeaderValue!!) }
        } else {
          reporters.forEach { it.headerComparisonFailed(key, expectedHeaderValue!!, headerComparison) }
          failures["$comparisonDescription includes headers \"$key\" with value \"$expectedHeaderValue\""] =
            headerComparison.joinToString(", ") { it.description() }
          result = result.merge(TestResult.Failed(headerComparison.map { it.toMap() }, "Headers had differences"))
        }
      }
      result
    }
  }

  override fun verifyResponseFromProvider(
    provider: IProviderInfo,
    interaction: RequestResponseInteraction,
    interactionMessage: String,
    failures: MutableMap<String, Any>,
    client: ProviderClient
  ) = verifyResponseFromProvider(provider, interaction, interactionMessage, failures, client, mapOf())

  override fun verifyResponseFromProvider(
    provider: IProviderInfo,
    interaction: RequestResponseInteraction,
    interactionMessage: String,
    failures: MutableMap<String, Any>,
    client: ProviderClient,
    context: Map<String, Any>
  ): TestResult {
    return try {
      val expectedResponse = interaction.response.generatedResponse(context)
      val actualResponse = client.makeRequest(interaction.request.generatedRequest(context))

      verifyRequestResponsePact(expectedResponse, actualResponse, interactionMessage, failures,
        interaction.interactionId.orEmpty())
    } catch (e: Exception) {
      failures[interactionMessage] = e
      reporters.forEach {
        it.requestFailed(provider, interaction, interactionMessage, e, projectHasProperty.apply(PACT_SHOW_STACKTRACE))
      }
      TestResult.Failed(listOf(mapOf("message" to "Request to provider failed with an exception",
        "exception" to e, "interactionId" to interaction.interactionId)),
        "Request to provider method failed with an exception")
    }
  }

  companion object : KLogging() {
    const val PACT_VERIFIER_PUBLISH_RESULTS = "pact.verifier.publishResults"
    const val PACT_FILTER_CONSUMERS = "pact.filter.consumers"
    const val PACT_FILTER_DESCRIPTION = "pact.filter.description"
    const val PACT_FILTER_PROVIDERSTATE = "pact.filter.providerState"
    const val PACT_SHOW_STACKTRACE = "pact.showStacktrace"
    const val PACT_SHOW_FULLDIFF = "pact.showFullDiff"
    const val PACT_PROVIDER_VERSION = "pact.provider.version"
    const val PACT_PROVIDER_VERSION_TRIM_SNAPSHOT = "pact.provider.version.trimSnapshot"

    fun invokeProviderMethod(m: Method, instance: Any?): Any? {
      try {
        return m.invoke(instance)
      } catch (e: Throwable) {
        throw RuntimeException("Failed to invoke provider method '${m.name}'", e)
      }
    }
  }
}