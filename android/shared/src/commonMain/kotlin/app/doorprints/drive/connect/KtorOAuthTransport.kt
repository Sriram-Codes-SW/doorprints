package app.doorprints.drive.connect

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.parameters

/** [OAuthTransport] on the shared Ktor client (`ApiHttp.client(engine)`: no redirects followed, no exception for a status). */
class KtorOAuthTransport(private val http: HttpClient) : OAuthTransport {
    override suspend fun postForm(url: String, fields: Map<String, String>): OAuthResponse {
        val form: Parameters = parameters { fields.forEach { (k, v) -> append(k, v) } }
        val r = http.post(url) { setBody(FormDataContent(form)) }
        return OAuthResponse(r.status.value, r.bodyAsText())
    }
}
