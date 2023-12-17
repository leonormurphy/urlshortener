@file:Suppress("WildcardImport")

package es.unizar.urlshortener.infrastructure.delivery

import es.unizar.urlshortener.core.*
import es.unizar.urlshortener.core.usecases.CreateShortUrlUseCase
import es.unizar.urlshortener.core.usecases.LogClickUseCase
import es.unizar.urlshortener.core.usecases.QrUseCase
import es.unizar.urlshortener.core.usecases.RedirectUseCase
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.hateoas.server.mvc.linkTo
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultHandlers.print
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

@WebMvcTest
@ContextConfiguration(
    classes = [
        UrlShortenerControllerImpl::class,
        RestResponseEntityExceptionHandler::class
    ]
)
class UrlShortenerControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var redirectUseCase: RedirectUseCase

    @MockBean
    private lateinit var logClickUseCase: LogClickUseCase

    @MockBean
    private lateinit var createShortUrlUseCase: CreateShortUrlUseCase

    @MockBean
    private lateinit var qrUseCase: QrUseCase

    @Suppress("UnusedPrivateMember")
    @MockBean
    private lateinit var qrQueue: BlockingQueue<Pair<String, String>>

    @Test
    fun `redirectTo returns a redirect when the key exists`() {
        given(redirectUseCase.redirectTo("key")).willReturn(Redirection("http://example.com/"))

        mockMvc.perform(get("/{id}", "key"))
            .andExpect(status().isTemporaryRedirect)
            .andExpect(redirectedUrl("http://example.com/"))

        verify(logClickUseCase).logClick("key", ClickProperties(ip = "127.0.0.1"))
    }

    @Test
    fun `redirectTo returns a not found when the key does not exist`() {
        given(redirectUseCase.redirectTo("key"))
            .willAnswer { throw RedirectionNotFound("key") }

        mockMvc.perform(get("/{id}", "key"))
            .andDo(print())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.statusCode").value(404))

        verify(logClickUseCase, never()).logClick("key", ClickProperties(ip = "127.0.0.1"))
    }

    // Se ha añadido el parametro qr False
    @Test
    fun `creates returns a basic redirect if it can compute a hash`() {
        given(
            createShortUrlUseCase.create(
                url = "http://example.com/",
                data = ShortUrlProperties(ip = "127.0.0.1")
            )
        ).willReturn(ShortUrl("f684a3c4", Redirection("http://example.com/")))

        mockMvc.perform(
            post("/api/link")
                .param("url", "http://example.com/")
                .param("qr", "false")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        )
            .andDo(print())
            .andExpect(status().isCreated)
            .andExpect(redirectedUrl("http://localhost/f684a3c4"))
            .andExpect(jsonPath("$.url").value("http://localhost/f684a3c4"))
    }

    //Test para comprobar que se devuelve un QR
    @Test
    fun `creates returns a basic redirect if it can compute a hash with qr`() {
        given(
                createShortUrlUseCase.create(
                        url = "http://example.com/",
                        data = ShortUrlProperties(ip = "127.0.0.1", qr = true)
                )
        ).willReturn(ShortUrl("f684a3c4", Redirection("http://example.com/")))

        mockMvc.perform(
                post("/api/link")
                        .param("url", "http://example.com/")
                        .param("qr", "true")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        )
                .andDo(print())
                .andExpect(status().isCreated)
                .andExpect(redirectedUrl("http://localhost/f684a3c4"))
                .andExpect(
                    content().json(
                        """
                        {
                          "url": "http://localhost/f684a3c4",
                          "properties": {
                            "qr": "http://localhost/f684a3c4/qr"
                          }
                        }
                        """.trimIndent()
                    )
                )
    }

    @Test
    fun `creates returns bad request if it can compute a hash`() {
        given(
            createShortUrlUseCase.create(
                url = "ftp://example.com/",
                data = ShortUrlProperties(ip = "127.0.0.1")
            )
        ).willAnswer { throw InvalidUrlException("ftp://example.com/") }

        mockMvc.perform(
            post("/api/link")
                .param("url", "ftp://example.com/")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        )
            .andExpect(status().isBadRequest)
    }

    /****************************************************************************************
     * Test para la comprobarción de QR
     ****************************************************************************************/
    @Test
    fun `generateQR returns a QR when the key exists`() {
        given(qrUseCase.getQR("key")).willReturn(byteArrayOf(0, 1, 2, 3))

        mockMvc.perform(get("/{id}/qr", "key"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andExpect(content().bytes(byteArrayOf(0, 1, 2, 3)))
    }

    @Test
    fun `generateQR returns a not found when the key does not exist`() {
        given(qrUseCase.getQR("key"))
            .willAnswer { throw RedirectionNotFound("key") }

        mockMvc.perform(get("/{id}/qr", "key"))
            .andDo(print())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.statusCode").value(404))
    }

    @Test
    fun `generateQR returns forbidden when the key exists but the qr is invalid`() {
        given(qrUseCase.getQR("key"))
                .willAnswer { throw InfoNotAvailable("key", "QR") }

        mockMvc.perform(get("/{id}/qr", "key"))
                .andDo(print())
                .andExpect(status().isForbidden)
    }

    /****************************************************************************************/
}
