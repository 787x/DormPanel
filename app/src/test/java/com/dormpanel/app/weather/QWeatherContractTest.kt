package com.dormpanel.app.weather

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class QWeatherContractTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-25T00:00:00Z")
    private val source = WeatherSourceRef.QWeather(WeatherCoordinates("39.92456".toBigDecimal(), "116.41667".toBigDecimal()), "101010100")
    private fun parse(block: WeatherBlock, json: String) = QWeatherParser.parse(JSONObject(json), zone, WeatherQuery(block))

    @Test fun everyDocumentedEndpointUsesItsOwnParameters() {
        fun endpoint(block: WeatherBlock) = checkNotNull(QWeatherProvider.endpoint(source,
            WeatherQuery(block, hours = 240, days = 10, date = LocalDate.parse("2026-10-24"),
                indexTypes = setOf(3, 1, 5), indexDays = 3)))
        val paths = mapOf(WeatherBlock.CURRENT to "weather/v1/current", WeatherBlock.HOURLY to "weather/v1/hourly",
            WeatherBlock.DAILY to "weather/v1/daily", WeatherBlock.ALERTS to "weatheralert/v1/current",
            WeatherBlock.AIR_CURRENT to "airquality/v1/current", WeatherBlock.AIR_HOURLY to "airquality/v1/hourly",
            WeatherBlock.AIR_DAILY to "airquality/v1/daily")
        paths.forEach { (block, path) -> assertEquals("$path/39.92/116.42", endpoint(block).path) }
        assertEquals("240", endpoint(WeatherBlock.HOURLY).parameters["hours"])
        assertEquals("10", endpoint(WeatherBlock.DAILY).parameters["days"])
        assertEquals("v7/minutely/5m", endpoint(WeatherBlock.MINUTELY).path)
        assertEquals("116.42,39.92", endpoint(WeatherBlock.MINUTELY).parameters["location"])
        assertEquals("v7/indices/3d", endpoint(WeatherBlock.INDICES).path)
        assertEquals("1,3,5", endpoint(WeatherBlock.INDICES).parameters["type"])
        assertEquals("v7/astronomy/sun", endpoint(WeatherBlock.SUN).path)
        assertEquals("v7/astronomy/moon", endpoint(WeatherBlock.MOON).path)
        assertEquals("20261024", endpoint(WeatherBlock.SUN).parameters["date"])
        assertEquals("v7/historical/weather", endpoint(WeatherBlock.HISTORY).path)
        assertEquals("101010100", endpoint(WeatherBlock.HISTORY).parameters["location"])
        assertNull(QWeatherProvider.endpoint(source.copy(locationId = null), WeatherQuery(WeatherBlock.HISTORY)))
        assertNull(QWeatherProvider.endpoint(source, WeatherQuery(WeatherBlock.TWICE_DAILY)))
        assertEquals("39.92456", source.coordinates.latitude.toPlainString())
        assertThrows(IllegalArgumentException::class.java) { WeatherCoordinates("90.001".toBigDecimal(), "0".toBigDecimal()) }
    }

    @Test fun currentAndForecastUseV1ShapesNullableNumbersAndDst() {
        val current = parse(WeatherBlock.CURRENT, """{
            "metadata":{"tag":"revision","attributions":["attribution A","attribution B"]},
            "validTime":"2026-10-25T02:15+02:00","condition":{"code":"unrecognized","text":"test condition"},
            "temperature":{"value":31.71,"unit":"°C"},"humidity":0.69,"cloudCover":0.05,
            "feelsLike":{"value":"NaN","unit":"°C"},"wind":{"direction":{"degree":226,"compass":"sw"},
            "speed":{"value":4.74,"unit":"m/s"},"scale":3},"windGust":{"value":7.07,"unit":"m/s"},
            "precipitation":{"amount":{"value":0,"unit":"mm"},"probability":0.31,"type":"none"},
            "pressure":{"value":1001.5,"unit":"hPa"},"visibility":{"value":29020,"unit":"m"},"uvIndex":0
        }""")
        val c = (current.data as WeatherData.Current).conditions
        assertEquals("unrecognized", c.code)
        assertEquals(31.71, c.temperature!!, 0.0)
        assertNull(c.feelsLike)
        assertNull(c.dewPoint)
        assertEquals(69.0, c.humidity!!, 1e-8)
        assertEquals(4.74, c.windSpeed!!, 0.0)
        assertEquals(7.07, c.gust!!, 0.0)
        assertEquals(1001.5, c.pressure!!, 0.0)
        assertEquals(29020.0, c.visibility!!, 0.0)
        assertEquals(0.0, c.precipitation!!, 0.0)
        assertEquals(31.0, c.precipitationProbability!!, 1e-8)
        assertEquals(Instant.parse("2026-10-25T00:15:00Z"), current.dataAt)
        assertEquals(listOf("attribution A", "attribution B"), current.attribution.statements)
        assertEquals("revision", current.tag)
        assertNull(parse(WeatherBlock.CURRENT, """{"code":"200","now":{"temp":"1"}}""").data)
        val hourly = parse(WeatherBlock.HOURLY, """{"hours":[{"forecastTime":"2026-10-25T02:00+02:00"},
            {"forecastTime":"2026-10-25T02:00+01:00"}]}""").data as WeatherData.Forecast
        assertEquals(listOf(now, now.plusSeconds(3600)), hourly.entries.map { it.period.start })
        val daily = parse(WeatherBlock.DAILY, """{"days":[{"forecastStartTime":"2026-10-24T22:00Z",
            "forecastEndTime":"2026-10-25T23:00Z","uvIndexMax":4,"daytime":{"humidity":0.3},
            "nighttime":{"condition":{"code":"101"}},"astro":{"sunrise":"2026-10-25T06:00Z","moonPhase":"full"}}]}""")
            .data as WeatherData.Forecast
        val day = daily.entries.single()
        assertEquals(LocalDate.parse("2026-10-25"), day.period.date)
        assertEquals(25 * 3600L, day.period.end!!.epochSecond - day.period.start!!.epochSecond)
        assertEquals(30.0, day.day!!.conditions.humidity!!, 1e-8)
        assertEquals("101", day.night!!.conditions.code)
        assertEquals("full", day.astronomy!!.phase)
    }

    @Test fun specializedBlocksKeepNativeFieldsAndLegacyUnits() {
        val aqi = """{"indexes":[{"code":"qaqi","aqi":1.4,"aqiDisplay":"1.4","health":{"effect":"effect",
            "advice":{"generalPopulation":"general","sensitivePopulation":"sensitive"}}}],"pollutants":[
            {"code":"pm2p5","concentration":{"value":11.0,"unit":"μg/m3"},"subIndexes":[{"code":"qaqi","aqi":1.4}]}],
            "stations":[{"id":"station","name":"Station"}]}"""
        for ((block, raw) in listOf(WeatherBlock.AIR_CURRENT to aqi, WeatherBlock.AIR_HOURLY to """{"hours":[$aqi]}""",
            WeatherBlock.AIR_DAILY to """{"days":[$aqi]}""")) {
            val air = (parse(block, raw).data as WeatherData.Air).entries.single()
            assertEquals("sensitive", air.indexes.single().health!!.sensitive)
            assertEquals("μg/m3", air.pollutants.single().unit)
            assertEquals(1.4, air.pollutants.single().subIndexes.single().aqi!!, 0.0)
            assertEquals("station", air.stations.single().id)
        }
        val alert = parse(WeatherBlock.ALERTS, """{"alerts":[{"id":"2","messageType":{"code":"cancel","supersedes":["1"]},
            "senderName":"Authority","headline":"Warning","description":"Text","instruction":"Stay inside",
            "expireTime":"2026-10-25T06:00Z","responseTypes":["monitor"]}]}""").data as WeatherData.Alerts
        assertEquals(listOf("1"), alert.entries.single().supersedes)
        assertEquals("Authority", alert.entries.single().sender)
        assertEquals("Stay inside", alert.entries.single().instruction)
        assertTrue((parse(WeatherBlock.ALERTS, """{"metadata":{"zeroResult":true}}""").data as WeatherData.Alerts).entries.isEmpty())
        val rain = parse(WeatherBlock.MINUTELY, """{"code":"200","summary":"Rain","minutely":[
            {"fxTime":"2026-10-25T00:05Z","precip":"0.15","type":"rain"}],
            "refer":{"sources":["source A"],"license":["license B"]}}""")
        assertEquals(0.15, (rain.data as WeatherData.Minutely).entries.single().amountMm!!, 0.0)
        assertTrue("source A" in rain.attribution.sources)
        assertEquals(listOf("license B"), rain.attribution.licenses)
        val indices = parse(WeatherBlock.INDICES, """{"daily":[{"date":"2026-10-25","type":"3","text":"Clothing advice"}]}""")
        assertEquals("Clothing advice", (indices.data as WeatherData.Indices).entries.single().description)
        val sun = parse(WeatherBlock.SUN, """{"sunrise":"2026-10-25T07:30+01:00","sunset":""}""").data as WeatherData.Astronomy
        assertEquals(setOf(AstronomicalEvent.SUNRISE), sun.value.events.keys)
        val moon = parse(WeatherBlock.MOON, """{"moonPhase":[{"fxTime":"2026-10-25T01:00Z","illumination":"52","icon":"800"}]}""")
        assertEquals("800", (moon.data as WeatherData.Astronomy).value.hourlyPhases.single().code)
        val history = parse(WeatherBlock.HISTORY, """{"weatherDaily":{"date":"2026-10-24","sunrise":"07:00"},
            "weatherHourly":[{"time":"2026-10-24 02:00","windSpeed":"36","humidity":"71","temp":""}]}""")
            .data as WeatherData.History
        assertEquals(7, history.daily!!.astronomy!!.localEvents.getValue(AstronomicalEvent.SUNRISE).hour)
        assertEquals(10.0, history.hourly.single().conditions.windSpeed!!, 0.0)
        assertEquals(71.0, history.hourly.single().conditions.humidity!!, 0.0)
        assertNull(history.hourly.single().period.start)
        assertEquals(2, history.hourly.single().period.localTime!!.hour)
    }

    @Test fun hostJwtSignatureAndLifecycle() {
        assertEquals("project.xy.qweatherapi.com", qWeatherHost("HTTPS://PROJECT.XY.QWEATHERAPI.COM"))
        for (host in listOf("http://project.qweatherapi.com", "https://project.qweatherapi.com:443", "project.qweatherapi.com@evil.test",
            "project.qweatherapi.com/path", "project.qweatherapi.com.evil.test", "qweatherapi.com", "127.0.0.1")) {
            assertThrows(IllegalArgumentException::class.java) { qWeatherHost(host) }
        }
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val auth = QWeatherAuth.Jwt("testkid", "testissuer", "testsubject", Base64.getEncoder().encodeToString(key.private.encoded))
        val (name, value) = auth.header(now)
        assertEquals("Authorization", name)
        val parts = value.removePrefix("Bearer ").split(".")
        val decoder = Base64.getUrlDecoder()
        val jwtHeader = JSONObject(decoder.decode(parts[0]).decodeToString())
        val jwtClaims = JSONObject(decoder.decode(parts[1]).decodeToString())
        assertEquals("EdDSA", jwtHeader.getString("alg"))
        assertEquals("testkid", jwtHeader.getString("kid"))
        assertEquals("testissuer", jwtClaims.getString("iss"))
        assertEquals("testsubject", jwtClaims.getString("sub"))
        assertEquals(now.epochSecond - 30, jwtClaims.getLong("iat"))
        assertEquals(now.epochSecond + 870, jwtClaims.getLong("exp"))
        assertTrue(Signature.getInstance("Ed25519").run {
            initVerify(key.public)
            update("${parts[0]}.${parts[1]}".toByteArray())
            verify(decoder.decode(parts[2]))
        })
        assertEquals(name to value, auth.header(now.plusSeconds(600)))
        assertNotEquals(name to value, auth.header(now.plusSeconds(840)))
        assertNotEquals(name to value, auth.header(now.minusSeconds(90)))
    }

    @Test fun httpUsesHeaderNeverRedirectsAndClassifiesFailures() {
        val http = OkHttpClient()
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                val api = QWeatherApi(http, { now }) { _, endpoint -> server.url("/${endpoint.path}") }
                val config = QWeatherConfig("test", "test.qweatherapi.com", QWeatherAuth.ApiKey("test-fake-key"))
                val endpoint = QWeatherEndpoint("weather/v1/current/39.92/116.42")
                fun call(response: MockResponse): WeatherResult {
                    server.enqueue(response)
                    val result = LinkedBlockingQueue<WeatherResult>()
                    api.execute(config, endpoint, { QWeatherParser.parse(it, zone, WeatherQuery(WeatherBlock.CURRENT)) }, { result.add(it) })
                    return checkNotNull(result.poll(4, TimeUnit.SECONDS))
                }
                val redirect = call(MockResponse().setResponseCode(302).addHeader("Location", other.url("/receive")))
                assertTrue(redirect is WeatherResult.Failed)
                assertEquals(0, other.requestCount)
                val request = server.takeRequest()
                assertEquals("test-fake-key", request.getHeader("X-QW-Api-Key"))
                assertEquals("/weather/v1/current/39.92/116.42", request.path)
                for ((code, expected) in listOf(401 to WeatherState.UNAUTHORIZED, 403 to WeatherState.UNAUTHORIZED,
                    404 to WeatherState.UNSUPPORTED, 500 to WeatherState.ERROR)) {
                    assertEquals(expected, (call(MockResponse().setResponseCode(code)) as WeatherResult.Failed).failure.state)
                }
                for (response in listOf(MockResponse().setResponseCode(400),
                    MockResponse().setBody("""{"code":"400"}"""),
                    MockResponse().setBody("""{"error":{"status":400,"type":"https://dev.qweather.com/docs/resource/error-code/#invalid-parameters"}}"""),
                    MockResponse().setResponseCode(422))) {
                    val invalid = (call(response) as WeatherResult.Failed).failure
                    assertEquals(WeatherState.INVALID_REQUEST, invalid.state)
                    assertTrue(invalid.blocksRetry)
                }
                assertFalse((call(MockResponse().setResponseCode(503)) as WeatherResult.Failed).failure.blocksRetry)
                val rate = call(MockResponse().setResponseCode(429).addHeader("Retry-After", "123")) as WeatherResult.Failed
                assertEquals(now.plusSeconds(123), rate.failure.retryAt)
                assertNull((call(MockResponse().setResponseCode(204)) as WeatherResult.Data).parsed.data)
                assertEquals(WeatherState.UNAUTHORIZED,
                    (call(MockResponse().setBody("""{"code":"403"}""")) as WeatherResult.Failed).failure.state)
                assertTrue(call(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)) is WeatherResult.Failed)
            }
        }
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    @Test fun rfc8032PublicKeyHasDistinctPemTextAndDerSha256() {
        val pkcs8 = "302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
        val input = Base64.getEncoder().encodeToString(pkcs8.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val key = QWeatherSigningKey.read("\r\n-----BEGIN PRIVATE KEY-----\r\n $input \r\n-----END PRIVATE KEY-----\r\n")
        assertEquals("-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=\n-----END PUBLIC KEY-----\n",
            key.publicPem)
        assertEquals("06e3fd8fda29bb60ab59557de61edb0aecdb231134be30e75b455f8e1b792fa9", key.spkiSha256)
        assertEquals("874261a353575e072e5a25e3eb15c94b6d5838ed3430cb974071eabbcb9bffd5", key.qWeatherPemSha256)
        val restored = QWeatherSigningKey.read(key.encode())
        assertEquals(key.publicPem, restored.publicPem)
        assertEquals(key.qWeatherPemSha256, restored.qWeatherPemSha256)
    }

    @Test fun credentialEqualityUsesKeyMaterialAndAllJwtIdentifiers() {
        val key = QWeatherSigningKey.generate()
        val jwt = QWeatherAuth.Jwt("kid", "issuer", "project", key)
        val reimported = QWeatherSigningKey.read(key.encode())
        jwt.header(now)
        assertTrue(jwt.sameCredentials(QWeatherAuth.Jwt("kid", "issuer", "project", reimported)))
        assertFalse(jwt.sameCredentials(QWeatherAuth.Jwt("other", "issuer", "project", reimported)))
        assertFalse(jwt.sameCredentials(QWeatherAuth.Jwt("kid", "other", "project", reimported)))
        assertFalse(jwt.sameCredentials(QWeatherAuth.Jwt("kid", "issuer", "other", reimported)))
        assertFalse(jwt.sameCredentials(QWeatherAuth.Jwt("kid", "issuer", "project", QWeatherSigningKey.generate())))
        assertFalse(jwt.sameCredentials(QWeatherAuth.ApiKey("fixture")))
        assertTrue(QWeatherAuth.ApiKey("fixture").sameCredentials(QWeatherAuth.ApiKey("fixture")))
        assertFalse(QWeatherAuth.ApiKey("fixture").sameCredentials(QWeatherAuth.ApiKey("other")))
    }

    @Test fun generatedPemVerifiesProviderJwtAndSurvivesPkcs8Reload() {
        val key = QWeatherSigningKey.generate()
        val pem = key.publicPem.lines()
        assertEquals("-----BEGIN PUBLIC KEY-----", pem[0])
        assertEquals("-----END PUBLIC KEY-----", pem[2])
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(pem[1])))
        val restored = QWeatherSigningKey.read(key.encode())
        assertEquals(key.spkiSha256, restored.spkiSha256)
        assertEquals(key.qWeatherPemSha256, restored.qWeatherPemSha256)
        val config = QWeatherConfig("test", "test.qweatherapi.com",
            QWeatherAuth.Jwt("kid1", "issuer1", "project1", restored))
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val parts = request.getHeader("Authorization").orEmpty().removePrefix("Bearer ").split('.')
                    val decoder = Base64.getUrlDecoder()
                    val verified = parts.size == 3 && Signature.getInstance("Ed25519").run {
                        initVerify(publicKey)
                        update("${parts[0]}.${parts[1]}".toByteArray())
                        verify(decoder.decode(parts[2]))
                    }
                    val claims = if (verified) JSONObject(decoder.decode(parts[1]).decodeToString()) else JSONObject()
                    return if (claims.optString("sub") == "project1" && claims.optString("iss") == "issuer1")
                        MockResponse().setBody("""{"temperature":{"value":22.5,"unit":"°C"}}""")
                    else MockResponse().setResponseCode(401)
                }
            }
            val http = OkHttpClient()
            try {
                val api = QWeatherApi(http, { now }) { _, endpoint -> server.url("/${endpoint.path}") }
                val result = LinkedBlockingQueue<WeatherResult>()
                api.execute(config, QWeatherEndpoint("weather/v1/current/39.92/116.42"),
                    { QWeatherParser.parse(it, zone, WeatherQuery(WeatherBlock.CURRENT)) }, { result.add(it) })
                assertEquals(22.5, ((result.poll(5, TimeUnit.SECONDS) as WeatherResult.Data)
                    .parsed.data as WeatherData.Current).conditions.temperature!!, 0.0)
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun haUnitsAndForecastDateAreNormalized() {
        val wire = JSONObject("""{"attrs":{"temperature_unit":"°F","wind_speed_unit":"mph","pressure_unit":"inHg",
            "precipitation_unit":"in","visibility_unit":"mi","attribution":"Upstream"},"forecast":[
            {"datetime":"2026-10-24T22:00Z","temperature":68,"templow":50,"wind_speed":10,"pressure":30,
            "visibility":2,"precipitation":1,"precipitation_probability":60,"is_daytime":false}]}""")
        val parsed = HaWeatherParser.parse(wire, zone, WeatherBlock.TWICE_DAILY)
        val day = (parsed.data as WeatherData.Forecast).entries.single()
        assertEquals(LocalDate.of(2026, 10, 25), day.period.date)
        assertEquals(false, day.period.isDaytime)
        assertEquals(20.0, day.conditions.temperature!!, 1e-8)
        assertNull(day.conditions.maxTemperature)
        assertEquals(10.0, day.conditions.minTemperature!!, 1e-8)
        assertEquals(4.4704, day.conditions.windSpeed!!, 1e-8)
        assertEquals(1015.9167, day.conditions.pressure!!, 1e-8)
        assertEquals(3218.688, day.conditions.visibility!!, 1e-8)
        assertEquals(25.4, day.conditions.precipitation!!, 1e-8)
        assertTrue("Upstream" in parsed.attribution.sources)
    }
}
