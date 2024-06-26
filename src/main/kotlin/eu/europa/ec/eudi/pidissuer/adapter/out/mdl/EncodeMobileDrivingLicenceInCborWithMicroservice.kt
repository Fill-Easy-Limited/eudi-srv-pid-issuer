/*
 * Copyright (c) 2023 European Commission
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.europa.ec.eudi.pidissuer.adapter.out.mdl

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.right
import com.nimbusds.jose.jwk.ECKey
import eu.europa.ec.eudi.pidissuer.adapter.out.jose.toBase64UrlSafeEncodedPem
import eu.europa.ec.eudi.pidissuer.adapter.out.mdl.DrivingPrivilege.Restriction
import eu.europa.ec.eudi.pidissuer.adapter.out.mdl.DrivingPrivilege.Restriction.GenericRestriction
import eu.europa.ec.eudi.pidissuer.adapter.out.mdl.DrivingPrivilege.Restriction.ParameterizedRestriction
import eu.europa.ec.eudi.pidissuer.domain.HttpsUrl
import eu.europa.ec.eudi.pidissuer.port.input.IssueCredentialError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Implementation of [EncodeMobileDrivingLicenceInCbor] using a microservice.
 */
@Deprecated(
    message = "Introduces a dependency to external service",
)
class EncodeMobileDrivingLicenceInCborWithMicroservice(
    private val webClient: WebClient,
    private val service: HttpsUrl,
) : EncodeMobileDrivingLicenceInCbor {
    init {
        log.info("Initialized using: {}", service)
    }

    context(Raise<IssueCredentialError.Unexpected>)
    override suspend fun invoke(
        licence: MobileDrivingLicence,
        holderKey: ECKey,
    ): String {
        log.info("Encoding mDL in CBOR")
        val request = createRequest(licence, holderKey).also { log.debug("Request data {}", it) }
        return Either.catch {
            webClient.post()
                .uri(service.value.toExternalForm())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(request))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .awaitBody<Response>()
                .also { log.debug("Response data {}", it) }
                .fold()
                .bind()
        }.getOrElse { raise(IssueCredentialError.Unexpected("Unable to encode mDL in CBOR", it)) }
    }

    companion object {

        private val log = LoggerFactory.getLogger(EncodeMobileDrivingLicenceInCborWithMicroservice::class.java)

        private fun createRequest(licence: MobileDrivingLicence, holderKey: ECKey): Request =
            buildJsonObject {
                put("version", "0.3")
                put("country", "FC")
                put("doctype", MobileDrivingLicenceV1.docType)
                put("device_publickey", holderKey.toBase64UrlSafeEncodedPem())

                putJsonObject("data") {
                    putJsonObject(MobileDrivingLicenceV1Namespace) {
                        addDriver(licence.driver)
                        addIssueAndExpiry(licence.issueAndExpiry)
                        addIssuer(licence.issuer)
                        put(DocumentNumberAttribute.name, licence.documentNumber.value)
                        addDrivingPrivileges(licence.privileges)
                        licence.administrativeNumber?.let { put(AdministrativeNumberAttribute.name, it.value) }
                    }
                }
            }

        @OptIn(ExperimentalEncodingApi::class)
        private fun JsonObjectBuilder.addDriver(driver: Driver) {
            with(driver) {
                put(FamilyNameAttribute.name, familyName.latin.value)
                put(GivenNameAttribute.name, givenName.latin.value)
                put(BirthDateAttribute.name, birthDate.toString())
                with(portrait) {
                    put(PortraitAttribute.name, Base64.UrlSafe.encode(image.content))
                    capturedAt?.let { put(PortraitCaptureDateAttribute.name, it.toString()) }
                }
                sex?.let { put(SexAttribute.name, it.code.toInt()) }
                height?.let { put(HeightAttribute.name, it.value.toInt()) }
                weight?.let { put(WeightAttribute.name, it.value.toInt()) }
                eyeColour?.let { put(EyeColourAttribute.name, it.code) }
                hairColour?.let { put(HairColourAttribute.name, it.code) }
                birthPlace?.let { put(BirthPlaceAttribute.name, it.value) }
                residence?.let { residence ->
                    residence.address?.let { put(ResidentAddressAttribute.name, it.value) }
                    residence.city?.let { put(ResidentCityAttribute.name, it.value) }
                    residence.state?.let { put(ResidentStateAttribute.name, it.value) }
                    residence.postalCode?.let { put(ResidentPostalCodeAttribute.name, it.value) }
                    put(ResidentCountryAttribute.name, residence.country.code)
                }
                age?.let { age ->
                    put(AgeInYearsAttribute.name, age.value.value.toInt())
                    age.birthYear?.let { put(AgeBirthYearAttribute.name, it.value.toInt()) }
                    put(AgeOver18Attribute.name, age.over18)
                    put(AgeOver21Attribute.name, age.over21)
                }
                nationality?.let { put(NationalityAttribute.name, it.code) }
                familyName.utf8?.let { put(FamilyNameNationalCharacterAttribute.name, it) }
                givenName.utf8?.let { put(GivenNameNationalCharacterAttribute.name, it) }
                signature?.let { put(SignatureUsualMarkAttribute.name, Base64.UrlSafe.encode(it.content)) }
            }
        }

        private fun JsonObjectBuilder.addIssueAndExpiry(issueAndExpiry: IssueAndExpiry) {
            with(issueAndExpiry) {
                put(IssueDateAttribute.name, issuedAt.toString())
                put(ExpiryDateAttribute.name, expiresAt.toString())
            }
        }

        private fun JsonObjectBuilder.addIssuer(issuer: Issuer) {
            with(issuer) {
                put(IssuingCountryAttribute.name, country.countryCode.code)
                put(IssuingAuthorityAttribute.name, authority.value)
                put(IssuingCountryDistinguishingSignAttribute.name, country.distinguishingSign.code)
                jurisdiction?.let { put(IssuingJurisdictionAttribute.name, it.value) }
            }
        }

        private fun JsonObjectBuilder.addDrivingPrivileges(privileges: Set<DrivingPrivilege>) {
            putJsonArray(DrivingPrivilegesAttribute.name) {
                privileges.forEach { drivingPrivilege ->
                    addJsonObject {
                        put("vehicle_category_code", drivingPrivilege.vehicleCategory.code)

                        drivingPrivilege.issueAndExpiry?.let { issueAndExpiry ->
                            addIssueAndExpiry(issueAndExpiry)
                        }

                        drivingPrivilege.restrictions?.let { restrictions ->
                            putJsonArray("codes") {
                                restrictions.forEach { restriction -> addRestriction(restriction) }
                            }
                        }
                    }
                }
            }
        }

        private fun JsonArrayBuilder.addRestriction(restriction: Restriction) {
            val (code, sign, value) =
                when (restriction) {
                    is GenericRestriction -> Triple(restriction.code, null, null)
                    is ParameterizedRestriction.VehiclePower -> Triple(
                        restriction.code,
                        restriction.value.code,
                        restriction.value.value.value,
                    )

                    is ParameterizedRestriction.VehicleAuthorizedMass -> Triple(
                        restriction.code,
                        restriction.value.code,
                        restriction.value.value.value,
                    )

                    is ParameterizedRestriction.VehicleCylinderCapacity -> Triple(
                        restriction.code,
                        restriction.value.code,
                        restriction.value.value.value,
                    )

                    is ParameterizedRestriction.VehicleAuthorizedPassengerSeats -> Triple(
                        restriction.code,
                        restriction.value.code,
                        restriction.value.value.value,
                    )
                }

            addJsonObject {
                put("code", code)
                sign?.let { put("sign", sign) }
                value?.let { put("value", value.toString()) }
            }
        }
    }
}

private typealias Request = JsonObject

@Serializable
private data class Response(
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("mdoc") val mdoc: String? = null,
) {
    fun fold(): Either<IssueCredentialError.Unexpected, String> =
        if (!mdoc.isNullOrBlank()) {
            mdoc.right()
        } else {
            IssueCredentialError.Unexpected(
                "Unable to encode mDL in CBOR. Code: '$errorCode', Message: '$errorMessage'",
            ).left()
        }
}
