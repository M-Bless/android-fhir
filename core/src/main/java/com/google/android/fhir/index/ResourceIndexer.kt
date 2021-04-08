/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.index

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import ca.uhn.fhir.model.api.annotation.SearchParamDefinition
import com.google.android.fhir.index.entities.DateIndex
import com.google.android.fhir.index.entities.NumberIndex
import com.google.android.fhir.index.entities.PositionIndex
import com.google.android.fhir.index.entities.QuantityIndex
import com.google.android.fhir.index.entities.ReferenceIndex
import com.google.android.fhir.index.entities.StringIndex
import com.google.android.fhir.index.entities.TokenIndex
import com.google.android.fhir.index.entities.UriIndex
import com.google.android.fhir.logicalId
import java.math.BigDecimal
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext
import org.hl7.fhir.r4.model.Base
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.InstantType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.Money
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.Timing
import org.hl7.fhir.r4.model.UriType
import org.hl7.fhir.r4.model.codesystems.SearchParamType
import org.hl7.fhir.r4.utils.FHIRPathEngine

/**
 * Indexes a FHIR resource according to the
 * [search parameters](https://www.hl7.org/fhir/searchparameter-registry.html).
 */
internal object ResourceIndexer {
  private val context = FhirContext.forR4()
  private val fhirPathEngine =
    FHIRPathEngine(HapiWorkerContext(context, DefaultProfileValidationSupport(context)))

  fun <R : Resource> index(resource: R) = extractIndexValues(resource)

  private fun <R : Resource> extractIndexValues(resource: R): ResourceIndices {
    val indexBuilder = ResourceIndices.Builder(resource.resourceType, resource.logicalId)
    resource
      .javaClass
      .fields
      .asSequence()
      .mapNotNull { it.getAnnotation(SearchParamDefinition::class.java) }
      .filter { it.path.isNotEmpty() }
      .map { it to fhirPathEngine.evaluate(resource, it.path) }
      .flatMap { pair -> pair.second.map { pair.first to it } }
      .forEach { pair ->
        val (searchParam, value) = pair
        when (SearchParamType.fromCode(pair.first.type)) {
          SearchParamType.NUMBER ->
            numberIndex(searchParam, value)?.also { indexBuilder.addNumberIndex(it) }
          SearchParamType.DATE ->
            dateIndex(searchParam, value)?.also { indexBuilder.addDateIndex(it) }
          SearchParamType.STRING ->
            stringIndex(searchParam, value)?.also { indexBuilder.addStringIndex(it) }
          SearchParamType.TOKEN ->
            tokenIndex(searchParam, value).forEach { indexBuilder.addTokenIndex(it) }
          SearchParamType.REFERENCE ->
            referenceIndex(searchParam, value)?.also { indexBuilder.addReferenceIndex(it) }
          SearchParamType.QUANTITY ->
            quantityIndex(searchParam, value)?.also { indexBuilder.addQuantityIndex(it) }
          SearchParamType.URI -> uriIndex(searchParam, value)?.also { indexBuilder.addUriIndex(it) }
          SearchParamType.SPECIAL -> specialIndex(value)?.also { indexBuilder.addPositionIndex(it) }
        // TODO: Handle composite type https://github.com/google/android-fhir/issues/292.
        }
      }

    // Add 'lastUpdated' index to all resources.
    if (resource.meta.hasLastUpdated()) {
      val lastUpdatedElement = resource.meta.lastUpdatedElement
      indexBuilder.addDateIndex(
        DateIndex(
          name = "_lastUpdated",
          path = arrayOf(resource.fhirType(), "meta", "lastUpdated").joinToString(separator = "."),
          tsHigh = lastUpdatedElement.value.time,
          tsLow = lastUpdatedElement.value.time,
          temporalPrecision = lastUpdatedElement.precision
        )
      )
    }

    return indexBuilder.build()
  }

  private fun numberIndex(searchParam: SearchParamDefinition, value: Base): NumberIndex? =
    when (value.fhirType()) {
      "integer" ->
        NumberIndex(searchParam.name, searchParam.path, BigDecimal((value as IntegerType).value))
      "decimal" -> NumberIndex(searchParam.name, searchParam.path, (value as DecimalType).value)
      else -> null
    }

  private fun dateIndex(searchParam: SearchParamDefinition, value: Base): DateIndex? =
    when (value.fhirType()) {
      "date" -> {
        val date = value as DateType
        DateIndex(
          searchParam.name,
          searchParam.path,
          date.value.time,
          date.value.time,
          date.precision
        )
      }
      "dateTime" -> {
        val dateTime = value as DateTimeType
        DateIndex(
          searchParam.name,
          searchParam.path,
          dateTime.value.time,
          dateTime.value.time,
          dateTime.precision
        )
      }
      "instant" -> {
        val instant = value as InstantType
        DateIndex(
          searchParam.name,
          searchParam.path,
          instant.value.time,
          instant.value.time,
          instant.precision
        )
      }
      "Period" -> {
        val period = value as Period
        DateIndex(
          searchParam.name,
          searchParam.path,
          if (period.hasEnd()) period.end.time else Long.MAX_VALUE,
          if (period.hasStart()) period.start.time else Long.MIN_VALUE,
          when {
            (period.hasEnd() and period.hasStart()) ->
              maxOf(period.startElement.precision, period.endElement.precision)
            (period.hasEnd()) -> period.endElement.precision
            (period.hasStart()) -> period.startElement.precision
            else -> DateTimeType.DEFAULT_PRECISION
          }
        )
      }
      "Timing" -> {
        val timing = value as Timing
        DateIndex(
          searchParam.name,
          searchParam.path,
          timing.event.maxOf { it.value.time },
          timing.event.minOf { it.value.time },
          timing.event.maxOf { it.precision }
        )
      }
      else -> null
    }

  private fun stringIndex(searchParam: SearchParamDefinition, value: Base): StringIndex? =
    if (!value.isEmpty) {
      StringIndex(searchParam.name, searchParam.path, value.toString())
    } else {
      null
    }

  private fun tokenIndex(searchParam: SearchParamDefinition, value: Base): List<TokenIndex> =
    when (value.fhirType()) {
      "boolean" ->
        listOf(
          TokenIndex(searchParam.name, searchParam.path, system = null, value.primitiveValue())
        )
      "Identifier" -> {
        val identifier = value as Identifier
        if (identifier.value != null) {
          listOf(
            TokenIndex(searchParam.name, searchParam.path, identifier.system, identifier.value)
          )
        } else {
          listOf()
        }
      }
      "CodeableConcept" -> {
        val codeableConcept = value as CodeableConcept
        codeableConcept.coding.filter { it.code != null && it.code!!.isNotEmpty() }.map {
          TokenIndex(searchParam.name, searchParam.path, it.system ?: "", it.code)
        }
      }
      else -> listOf()
    }

  private fun referenceIndex(searchParam: SearchParamDefinition, value: Base): ReferenceIndex? {
    val reference = (value as Reference).reference
    return reference?.let { ReferenceIndex(searchParam.name, searchParam.path, it) }
  }

  private fun quantityIndex(searchParam: SearchParamDefinition, value: Base): QuantityIndex? =
    when (value.fhirType()) {
      "Money" -> {
        val money = value as Money
        QuantityIndex(
          searchParam.name,
          searchParam.path,
          FHIR_CURRENCY_CODE_SYSTEM,
          money.currency,
          money.value
        )
      }
      "Quantity" -> {
        val quantity = value as Quantity
        QuantityIndex(
          searchParam.name,
          searchParam.path,
          quantity.system ?: "",
          quantity.unit ?: "",
          quantity.value
        )
      }
      else -> null
    }

  private fun uriIndex(searchParam: SearchParamDefinition, value: Base?): UriIndex? {
    val uri = (value as UriType).value
    return if (uri.isNotEmpty()) {
      UriIndex(searchParam.name, searchParam.path, uri)
    } else {
      null
    }
  }

  private fun specialIndex(value: Base?): PositionIndex? {
    return when (value?.fhirType()) {
      "Location.position" -> {
        val location = (value as Location.LocationPositionComponent)
        return PositionIndex(location.latitude.toDouble(), location.longitude.toDouble())
      }
      else -> null
    }
  }

  /**
   * The FHIR currency code system. See: https://bit.ly/30YB3ML. See:
   * https://www.hl7.org/fhir/valueset-currencies.html.
   */
  private const val FHIR_CURRENCY_CODE_SYSTEM = "urn:iso:std:iso:4217"
}
